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

/**
 * numEnv: 환경변수에서 양수를 읽고, 없거나 NaN/0/음수면 기본값으로 폴백한다.
 * 비용 가드레일 임계값을 운영에서 env로 조정할 수 있도록 한다(#277).
 */
export function numEnv(name: string, def: number): number {
  const n = Number(process.env[name]);
  return Number.isFinite(n) && n > 0 ? n : def;
}

/** #277 비용 가드레일: 쿼리당 USD 예산 하드 캡(초과 시 자동 중단). */
export const MAX_BUDGET_USD = numEnv('MAX_BUDGET_USD', 5);

/** #277 소프트 알람: 누적 토큰 임계(초과 시 cost_alarm 1회, 중단 안 함). */
export const COST_ALARM_TOKENS = numEnv('COST_ALARM_TOKENS', 5_000_000);

/** #277 소프트 알람: 턴 수 임계(초과 시 cost_alarm 1회, 중단 안 함). */
export const COST_ALARM_TURNS = numEnv('COST_ALARM_TURNS', 50);

