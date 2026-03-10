import * as vscode from 'vscode';
import { SilentChatEvent } from '../model/events';
import { ChatSession, SessionEntry, PlaybookRun, SessionStatus } from '../model/types';
import { DatabaseManager } from './DatabaseManager';
import { randomUUID } from 'crypto';

/**
 * SessionStore — equivalent of SessionStore.kt
 *
 * Event-sourced session persistence. Subscribes to chat events and
 * persists all state transitions to SQLite.
 */
export class SessionStore implements vscode.Disposable {
  private _onStatusChanged = new vscode.EventEmitter<[string, boolean]>();
  readonly onStatusChanged = this._onStatusChanged.event;

  // In-memory tracking (same as Kotlin: lastPrompt, currentTurnId, liveStatus)
  private lastPrompt = new Map<string, string>();
  private currentTurnId = new Map<string, string>();
  private liveStatus = new Map<string, SessionStatus>();

  private disposables: vscode.Disposable[] = [];

  constructor(private readonly dbManager: DatabaseManager) {}

  /**
   * Handle a chat event — called from the event subscription.
   * Equivalent of SessionStore.onEvent() in Kotlin.
   */
  handleEvent(sessionId: string, event: SilentChatEvent): void {
    const db = this.dbManager.getDb();

    switch (event.type) {
      case 'SessionReady':
        this.upsertSession(db, sessionId, 'ACTIVE', event.timestamp);
        this.liveStatus.set(sessionId, 'ACTIVE');
        break;

      case 'TurnIdSync':
        this.currentTurnId.set(sessionId, event.turnId);
        // Insert message entry for this turn, associating pending prompt
        const prompt = this.lastPrompt.get(sessionId);
        if (prompt) {
          db.prepare(`
            INSERT OR IGNORE INTO session_entries (id, chat_session_id, entry_type, turn_id, start_time, prompt, status)
            VALUES (?, ?, 'message', ?, ?, ?, 'active')
          `).run(randomUUID(), sessionId, event.turnId, event.timestamp, prompt);
          this.lastPrompt.delete(sessionId);
        }
        break;

      case 'Reply': {
        const turnId = this.currentTurnId.get(sessionId);
        if (turnId) {
          db.prepare(`
            UPDATE session_entries
            SET response = ?, reply_length = ?, end_time = ?
            WHERE chat_session_id = ? AND turn_id = ? AND entry_type = 'message'
          `).run(event.accumulated, event.accumulated.length, event.timestamp, sessionId, turnId);
        }
        break;
      }

      case 'ToolCallUpdate': {
        const turnId = this.currentTurnId.get(sessionId);
        const existsStmt = db.prepare(
          `SELECT id FROM session_entries WHERE id = ?`
        );
        const existing = existsStmt.get(event.id);

        if (existing) {
          // Update existing tool call entry
          db.prepare(`
            UPDATE session_entries
            SET status = ?, output = ?, error = ?, progress_message = ?,
                duration_ms = ?, end_time = ?
            WHERE id = ?
          `).run(
            event.status,
            event.output ?? null,
            event.error ?? null,
            event.progressMessage ?? null,
            event.durationMs ?? null,
            event.timestamp,
            event.id
          );
        } else {
          // Insert new tool call entry
          db.prepare(`
            INSERT INTO session_entries
            (id, chat_session_id, entry_type, turn_id, round_id, start_time, status,
             tool_name, tool_type, input, input_message)
            VALUES (?, ?, 'tool_call', ?, ?, ?, ?, ?, ?, ?, ?)
          `).run(
            event.id,
            sessionId,
            turnId ?? null,
            event.roundId ?? null,
            event.timestamp,
            event.status,
            event.toolName,
            event.toolType ?? null,
            event.input ?? null,
            event.inputMessage ?? null
          );
        }
        break;
      }

      case 'Steps': {
        const turnId = this.currentTurnId.get(sessionId);
        for (const step of event.steps) {
          const existsStmt = db.prepare(
            `SELECT id FROM session_entries WHERE id = ?`
          );
          const existing = existsStmt.get(step.id);

          if (existing) {
            db.prepare(`
              UPDATE session_entries SET status = ?, description = ?, end_time = ?
              WHERE id = ?
            `).run(step.status, step.description ?? null, event.timestamp, step.id);
          } else {
            db.prepare(`
              INSERT INTO session_entries
              (id, chat_session_id, entry_type, turn_id, start_time, status, title, description)
              VALUES (?, ?, 'step', ?, ?, ?, ?, ?)
            `).run(
              step.id,
              sessionId,
              turnId ?? null,
              event.timestamp,
              step.status,
              step.title,
              step.description ?? null
            );
          }
        }
        break;
      }

      case 'Complete':
        this.updateSessionStatus(db, sessionId, 'COMPLETED', event.timestamp);
        this.closeOpenEntries(db, sessionId, event.timestamp);
        break;

      case 'Error':
        this.updateSessionStatus(db, sessionId, 'ERROR', event.timestamp);
        break;

      case 'Cancel':
        this.updateSessionStatus(db, sessionId, 'CANCELLED', event.timestamp);
        break;

      case 'SuggestedTitle':
        db.prepare(`UPDATE chat_sessions SET title = ? WHERE session_id = ?`)
          .run(event.title, sessionId);
        break;
    }
  }

  /**
   * Record a prompt before session creation (for association with turn ID later).
   */
  recordPrompt(sessionId: string, prompt: string): void {
    this.lastPrompt.set(sessionId, prompt);
  }

  private upsertSession(db: any, sessionId: string, status: SessionStatus, timestamp: number): void {
    db.prepare(`
      INSERT INTO chat_sessions (session_id, start_time, status)
      VALUES (?, ?, ?)
      ON CONFLICT(session_id) DO UPDATE SET status = ?
    `).run(sessionId, timestamp, status, status);
  }

  private updateSessionStatus(db: any, sessionId: string, status: SessionStatus, timestamp: number): void {
    db.prepare(`
      UPDATE chat_sessions SET status = ?, end_time = ? WHERE session_id = ?
    `).run(status, timestamp, sessionId);
    this.liveStatus.set(sessionId, status);
  }

  private closeOpenEntries(db: any, sessionId: string, timestamp: number): void {
    db.prepare(`
      UPDATE session_entries
      SET status = 'completed', end_time = ?
      WHERE chat_session_id = ? AND entry_type = 'message' AND status = 'active'
    `).run(timestamp, sessionId);
  }

  // --- Query Methods ---

  getAllSessions(): ChatSession[] {
    const db = this.dbManager.getDb();
    const sessions = db.prepare(`
      SELECT * FROM chat_sessions ORDER BY start_time DESC
    `).all() as any[];

    return sessions.map((s) => ({
      sessionId: s.session_id,
      playbookId: s.playbook_id,
      startTime: s.start_time,
      endTime: s.end_time,
      status: s.status,
      title: s.title,
      entries: this.getSessionEntries(s.session_id),
    }));
  }

  getSession(sessionId: string): ChatSession | undefined {
    const db = this.dbManager.getDb();
    const session = db.prepare(`
      SELECT * FROM chat_sessions WHERE session_id = ?
    `).get(sessionId) as any;

    if (!session) return undefined;

    return {
      sessionId: session.session_id,
      playbookId: session.playbook_id,
      startTime: session.start_time,
      endTime: session.end_time,
      status: session.status,
      title: session.title,
      entries: this.getSessionEntries(sessionId),
    };
  }

  private getSessionEntries(sessionId: string): SessionEntry[] {
    const db = this.dbManager.getDb();
    const entries = db.prepare(`
      SELECT * FROM session_entries WHERE chat_session_id = ? ORDER BY start_time ASC
    `).all(sessionId) as any[];

    return entries.map((e) => ({
      id: e.id,
      chatSessionId: e.chat_session_id,
      entryType: e.entry_type,
      turnId: e.turn_id,
      roundId: e.round_id,
      startTime: e.start_time,
      endTime: e.end_time,
      status: e.status,
      durationMs: e.duration_ms,
      prompt: e.prompt,
      response: e.response,
      replyLength: e.reply_length,
      toolName: e.tool_name,
      toolType: e.tool_type,
      input: e.input,
      inputMessage: e.input_message,
      output: e.output,
      error: e.error,
      progressMessage: e.progress_message,
      title: e.title,
      description: e.description,
    }));
  }

  getAllPlaybooks(): PlaybookRun[] {
    const db = this.dbManager.getDb();
    const playbooks = db.prepare(`
      SELECT * FROM playbook_runs ORDER BY start_time DESC
    `).all() as any[];

    return playbooks.map((p) => ({
      id: p.id,
      startTime: p.start_time,
      endTime: p.end_time,
      sessions: this.getPlaybookSessions(p.id),
    }));
  }

  private getPlaybookSessions(playbookId: string): ChatSession[] {
    const db = this.dbManager.getDb();
    const sessions = db.prepare(`
      SELECT * FROM chat_sessions WHERE playbook_id = ? ORDER BY start_time ASC
    `).all(playbookId) as any[];

    return sessions.map((s) => ({
      sessionId: s.session_id,
      playbookId: s.playbook_id,
      startTime: s.start_time,
      endTime: s.end_time,
      status: s.status,
      title: s.title,
      entries: this.getSessionEntries(s.session_id),
    }));
  }

  createPlaybook(): string {
    const id = randomUUID();
    const db = this.dbManager.getDb();
    db.prepare(`INSERT INTO playbook_runs (id, start_time) VALUES (?, ?)`).run(id, Date.now());
    return id;
  }

  assignSessionToPlaybook(sessionId: string, playbookId: string): void {
    const db = this.dbManager.getDb();
    db.prepare(`UPDATE chat_sessions SET playbook_id = ? WHERE session_id = ?`).run(
      playbookId,
      sessionId
    );
  }

  completePlaybook(playbookId: string): void {
    const db = this.dbManager.getDb();
    db.prepare(`UPDATE playbook_runs SET end_time = ? WHERE id = ?`).run(Date.now(), playbookId);
  }

  dispose(): void {
    this._onStatusChanged.dispose();
    this.disposables.forEach((d) => d.dispose());
  }
}
