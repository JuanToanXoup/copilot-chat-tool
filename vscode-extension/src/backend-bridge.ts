import { ChildProcess, spawn } from 'child_process';
import { EventEmitter } from 'events';
import * as path from 'path';
import * as readline from 'readline';

/**
 * Bridge to the shared Kotlin backend process.
 * Spawns the fat JAR as a child process and communicates via stdio JSON lines.
 */
export class BackendBridge extends EventEmitter {
    private process: ChildProcess | null = null;
    private nextId = 1;
    private pending = new Map<number, { resolve: (v: any) => void; reject: (e: Error) => void }>();

    constructor(private jarPath: string) {
        super();
    }

    /**
     * Start the backend JVM process.
     */
    async start(): Promise<void> {
        this.process = spawn('java', ['-jar', this.jarPath], {
            stdio: ['pipe', 'pipe', 'pipe'],
        });

        const rl = readline.createInterface({ input: this.process.stdout! });

        rl.on('line', (line: string) => {
            try {
                const msg = JSON.parse(line);

                // Response to a request
                if (msg.id !== undefined) {
                    const pending = this.pending.get(msg.id);
                    if (pending) {
                        this.pending.delete(msg.id);
                        if (msg.error) {
                            pending.reject(new Error(msg.error.message));
                        } else {
                            pending.resolve(msg.result);
                        }
                    }
                }

                // Server-pushed event
                if (msg.event) {
                    this.emit(msg.event, msg.data);
                }
            } catch {
                // Ignore malformed lines
            }
        });

        this.process.stderr?.on('data', (data: Buffer) => {
            // Backend logs go to stderr — forward to VS Code output channel
            this.emit('log', data.toString());
        });

        this.process.on('exit', (code) => {
            this.emit('exit', code);
            this.process = null;
            // Reject all pending requests
            for (const [, pending] of this.pending) {
                pending.reject(new Error(`Backend process exited with code ${code}`));
            }
            this.pending.clear();
        });

        // Wait for the ready event
        return new Promise((resolve, reject) => {
            const timeout = setTimeout(() => reject(new Error('Backend startup timeout')), 15000);
            this.once('ready', () => {
                clearTimeout(timeout);
                resolve();
            });
            this.process?.once('error', (err) => {
                clearTimeout(timeout);
                reject(err);
            });
        });
    }

    /**
     * Send a request to the backend and wait for the response.
     */
    async request<T = any>(method: string, params: Record<string, any> = {}): Promise<T> {
        if (!this.process?.stdin) {
            throw new Error('Backend not running');
        }

        const id = this.nextId++;
        const msg = JSON.stringify({ id, method, params });

        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            this.process!.stdin!.write(msg + '\n');
        });
    }

    /**
     * Initialize the backend for a project.
     */
    async init(projectSlug: string): Promise<void> {
        await this.request('init', { projectSlug });
    }

    async getSessions(): Promise<any[]> {
        return this.request('getSessions');
    }

    async getSession(sessionId: string): Promise<any> {
        return this.request('getSession', { sessionId });
    }

    async getPlaybooks(): Promise<any[]> {
        return this.request('getPlaybooks');
    }

    async handleEvent(sessionId: string, event: any): Promise<void> {
        await this.request('handleEvent', { sessionId, event });
    }

    async recordPrompt(sessionId: string, prompt: string): Promise<void> {
        await this.request('recordPrompt', { sessionId, prompt });
    }

    async ping(): Promise<boolean> {
        const result = await this.request<{ pong: boolean }>('ping');
        return result.pong;
    }

    /**
     * Stop the backend process.
     */
    dispose(): void {
        if (this.process) {
            this.process.stdin?.end();
            this.process.kill();
            this.process = null;
        }
    }
}
