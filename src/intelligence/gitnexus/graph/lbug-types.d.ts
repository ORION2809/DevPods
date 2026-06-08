/**
 * Minimal type declarations for @ladybugdb/core.
 *
 * The package ships lbug.d.ts but TypeScript resolution can be
 * unreliable in mixed CJS/ESM projects. These declarations mirror
 * the subset we actually use.
 */

declare module '@ladybugdb/core' {
  export interface NodeID {
    offset: number;
    table: number;
  }

  export type LbugValue =
    | null
    | boolean
    | number
    | bigint
    | string
    | Date
    | NodeID
    | { _label: string | null; _id: NodeID | null; [key: string]: any }
    | { _src: NodeID | null; _dst: NodeID | null; _label: string | null; _id: any; [key: string]: any }
    | { _nodes: any[]; _rels: any[] };

  export class QueryResult {
    hasNext(): boolean;
    getNext(): Record<string, LbugValue>;
    getAll(): Promise<Record<string, LbugValue>[]>;
    close(): void;
  }

  export class PreparedStatement {
    execute(params: Record<string, unknown>): Promise<QueryResult>;
    close(): void;
  }

  export class Connection {
    constructor(database: Database, numThreads?: number | null);
    init(): Promise<void>;
    query(cypher: string): Promise<QueryResult>;
    prepare(cypher: string): Promise<PreparedStatement>;
    close(): Promise<void>;
  }

  export class Database {
    constructor(
      databasePath: string,
      bufferManagerSize?: number,
      enableCompression?: boolean,
      readOnly?: boolean,
      maxDbSize?: number,
      autoCheckpoint?: boolean,
      checkpointThreshold?: number,
      throwOnWalReplayFailure?: boolean,
      enableChecksums?: boolean,
    );
    close(): Promise<void>;
  }

  const lbug: {
    Database: typeof Database;
    Connection: typeof Connection;
    PreparedStatement: typeof PreparedStatement;
    QueryResult: typeof QueryResult;
    VERSION: string;
    STORAGE_VERSION: bigint;
  };

  export default lbug;
}
