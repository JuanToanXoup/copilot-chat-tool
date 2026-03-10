import * as vscode from 'vscode';
import Database from 'better-sqlite3';
import * as path from 'path';
import * as fs from 'fs';

/**
 * DatabaseManager — equivalent of DatabaseManager.kt
 *
 * Raw SQLite via better-sqlite3. Per-workspace DB at globalStorageUri.
 * Schema migration via ALTER TABLE ADD COLUMN (same pattern as Kotlin).
 */
export class DatabaseManager implements vscode.Disposable {
  private db: Database.Database | null = null;

  constructor(private readonly storageUri: vscode.Uri) {}

  getDb(): Database.Database {
    if (!this.db) {
      this.db = this.openDatabase();
    }
    return this.db;
  }

  private openDatabase(): Database.Database {
    const dbDir = this.storageUri.fsPath;
    fs.mkdirSync(dbDir, { recursive: true });

    const dbPath = path.join(dbDir, 'sessions.db');
    const db = new Database(dbPath);

    // WAL mode for concurrent access (same as Kotlin)
    db.pragma('journal_mode = WAL');

    this.initSchema(db);
    return db;
  }

  private initSchema(db: Database.Database): void {
    db.exec(`
      CREATE TABLE IF NOT EXISTS playbook_runs (
        id TEXT PRIMARY KEY,
        start_time INTEGER,
        end_time INTEGER
      );

      CREATE TABLE IF NOT EXISTS chat_sessions (
        session_id TEXT PRIMARY KEY,
        playbook_id TEXT,
        start_time INTEGER,
        end_time INTEGER,
        status TEXT DEFAULT 'ACTIVE',
        title TEXT,
        FOREIGN KEY (playbook_id) REFERENCES playbook_runs(id)
      );

      CREATE TABLE IF NOT EXISTS session_entries (
        id TEXT PRIMARY KEY,
        chat_session_id TEXT NOT NULL,
        entry_type TEXT NOT NULL,
        turn_id TEXT,
        round_id TEXT,
        start_time INTEGER,
        end_time INTEGER,
        status TEXT,
        duration_ms INTEGER,

        -- message fields
        prompt TEXT,
        response TEXT,
        reply_length INTEGER,

        -- tool_call fields
        tool_name TEXT,
        tool_type TEXT,
        input TEXT,
        input_message TEXT,
        output TEXT,
        error TEXT,
        progress_message TEXT,

        -- step fields
        title TEXT,
        description TEXT,

        FOREIGN KEY (chat_session_id) REFERENCES chat_sessions(session_id)
      );
    `);

    // Schema migrations (same pattern as Kotlin: ALTER TABLE ADD COLUMN)
    this.migrateSchema(db);
  }

  private migrateSchema(db: Database.Database): void {
    const migrations: Array<{ table: string; column: string; type: string }> = [
      // Add columns that may be missing from older schemas
      { table: 'chat_sessions', column: 'title', type: 'TEXT' },
      { table: 'session_entries', column: 'round_id', type: 'TEXT' },
      { table: 'session_entries', column: 'progress_message', type: 'TEXT' },
    ];

    for (const { table, column, type } of migrations) {
      try {
        db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${type}`);
      } catch {
        // Column already exists — ignore
      }
    }
  }

  dispose(): void {
    this.db?.close();
    this.db = null;
  }
}
