/** Default Claude model for agent execution */
export const DEFAULT_MODEL = 'claude-sonnet-4-6';

/** Default max agent turns */
export const DEFAULT_MAX_TURNS = 10;

/** Default text truncation length */
export const DEFAULT_TRUNCATE_LENGTH = 200;

/** Heartbeat interval for waiting on Claude API (ms) */
export const HEARTBEAT_INTERVAL_MS = 10_000;

/** Default server port */
export const DEFAULT_PORT = 3001;

/** MCP server name */
export const MCP_SERVER_NAME = 'firehub';

/** MCP server version */
export const MCP_SERVER_VERSION = '1.0.0';

/** API error message prefix */
export const API_ERROR_PREFIX = 'API 오류';

/** Token threshold for compaction */
export const DEFAULT_COMPACTION_THRESHOLD = 50_000;

/** Approximate bytes per token for file size estimation */
export const BYTES_PER_TOKEN = 1.45;

/** Number of recent messages to keep during compaction */
export const COMPACTION_RECENT_MESSAGES = 20;

/** Max length of content snippet in compaction summary */
export const COMPACTION_CONTENT_MAX_LENGTH = 500;

/** Max tokens for compaction summary generation */
export const COMPACTION_SUMMARY_MAX_TOKENS = 1024;

/** Model used for compaction summary */
export const COMPACTION_MODEL = 'claude-haiku-4-5-20251001';
