/**
 * CodeGraph store — minimal wrapper around LadybugDB.
 *
 * This is a deliberately thin abstraction: it opens a database,
 * executes Cypher queries, and closes cleanly. Retry logic,
 * WAL recovery, and FTS extensions are added in later phases.
 */

import fs from 'node:fs/promises';
import path from 'node:path';
import { setTimeout } from 'node:timers/promises';
import lbug, { Database, Connection, QueryResult } from '@ladybugdb/core';
import { SCHEMA_QUERIES } from './schema';

export interface GraphStoreOptions {
  /** Maximum DB size in bytes. Default: 16 GiB. */
  maxDbSize?: number;
  /** Open in read-only mode. Default: false. */
  readOnly?: boolean;
}

/**
 * A per-workspace graph database handle.
 *
 * Each workspace gets its own LadybugDB database file under
 * runtime-data/intelligence/<workspace-id>/codegraph.db
 */
export class GraphStore {
  private db: Database | null = null;
  private conn: Connection | null = null;
  private closed = false;

  constructor(
    private readonly dbPath: string,
    private readonly options: GraphStoreOptions = {},
  ) {}

  /**
   * Initialise the database and connection.
   *
   * If the database file does not exist and readOnly is false,
   * it is created automatically. If readOnly is true and the
   * file does not exist, this throws.
   */
  async init(): Promise<void> {
    if (this.closed) {
      throw new Error('GraphStore has been closed.');
    }
    if (this.conn) {
      return;
    }

    await fs.mkdir(path.dirname(this.dbPath), { recursive: true });

    const maxDbSize = this.options.maxDbSize ?? 16 * 1024 * 1024 * 1024;
    const readOnly = this.options.readOnly ?? false;

    this.db = new (lbug.Database as any)(
      this.dbPath,
      0, // bufferManagerSize — use LadybugDB default
      false, // enableCompression — pinned for v0.16.0 compat
      readOnly,
      maxDbSize,
      true, // autoCheckpoint
      64 * 1024 * 1024, // checkpointThreshold — 64 MiB
      true, // throwOnWalReplayFailure
      true, // enableChecksums
    ) as Database;

    this.conn = new lbug.Connection(this.db);
    await this.conn.init();
  }

  /**
   * Execute a Cypher query and return all rows.
   *
   * The QueryResult handle is always closed, even on error.
   */
  async query(cypher: string): Promise<unknown[]> {
    if (!this.conn) {
      throw new Error('GraphStore not initialised. Call init() first.');
    }
    let result: QueryResult | null = null;
    try {
      result = await this.conn.query(cypher);
      const rows: unknown[] = [];
      while (true) {
        try {
          const row = await result.getNext();
          rows.push(row);
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          if (msg.includes('No more tuples')) {
            break;
          }
          throw err;
        }
      }
      return rows;
    } finally {
      if (result) {
        try {
          result.close();
        } catch {
          // Best-effort cleanup
        }
      }
    }
  }

  /**
   * Close the connection and database.
   */
  async close(): Promise<void> {
    this.closed = true;
    if (this.conn) {
      await this.conn.close().catch(() => {});
      this.conn = null;
    }
    if (this.db) {
      await this.db.close().catch(() => {});
      this.db = null;
    }
  }

  /**
   * Create the graph schema (node tables, relationship tables, indexes).
   * Idempotent — "already exists" errors are suppressed.
   *
   * Lock errors get bounded retry (3 attempts, 100 ms backoff) instead of
   * silent suppression, so a partial schema never goes undetected.
   */
  async createSchema(): Promise<void> {
    if (!this.conn) {
      throw new Error('GraphStore not initialised. Call init() first.');
    }
    for (const ddl of SCHEMA_QUERIES) {
      let lastErr: Error | undefined;
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          await this.query(ddl);
          lastErr = undefined;
          break;
        } catch (err) {
          const msg = err instanceof Error ? err.message : String(err);
          // "already exists" is expected on re-runs
          if (/already exists/i.test(msg)) {
            lastErr = undefined;
            break;
          }
          // Transient Windows lock race — retry with backoff
          if (/could not set lock on file/i.test(msg) && attempt < 3) {
            lastErr = err instanceof Error ? err : new Error(msg);
            await setTimeout(100 * attempt);
            continue;
          }
          throw err;
        }
      }
      if (lastErr) {
        throw new Error(`Schema DDL failed after 3 attempts: ${lastErr.message}`);
      }
    }
  }

  async exists(): Promise<boolean> {
    try {
      await fs.access(this.dbPath);
      return true;
    } catch {
      return false;
    }
  }
}
