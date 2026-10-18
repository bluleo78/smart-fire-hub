/** Default Claude model for agent execution */
export const DEFAULT_MODEL = 'claude-sonnet-5';

/** Default max agent turns */
export const DEFAULT_MAX_TURNS = 10;

/**
 * WD-59: 최대 턴 수(설정 › AI 에이전트)에 닿아 실행이 끝났을 때의 안내. SDK·CLI 는 result `error_max_turns` 로 끝나는데,
 * 예전엔 SDK 가 'max_turns_exceeded', CLI 가 영문 기본 문구를 그대로 내보냈다. opencode 는 상한에서 오류 없이 마지막
 * 스텝에 "텍스트로만 답하라"는 지시를 덧붙여 정상 종료하므로 이 문구를 쓰지 않는다.
 */
export const MAX_TURNS_REACHED_MESSAGE =
  '최대 턴 수에 도달해 작업을 끝까지 마치지 못했습니다. 요청 범위를 좁혀 다시 시도하거나, 관리자에게 설정 › AI 에이전트의 최대 턴 수를 늘려 달라고 요청해 주세요.';

/** Default text truncation length */
export const DEFAULT_TRUNCATE_LENGTH = 200;

/** Heartbeat interval for waiting on Claude API (ms) */
export const HEARTBEAT_INTERVAL_MS = 10_000;

/** Default server port */
export const DEFAULT_PORT = 5020;

/** MCP server name */
export const MCP_SERVER_NAME = 'firehub';

/** MCP server version */
export const MCP_SERVER_VERSION = '1.0.0';

/**
 * 내부 서비스 대행 호출의 와이어 계약 헤더 이름. api 쪽 짝은
 * `apps/firehub-api/.../global/security/InternalCallHeaders.java` 다 — 언어가 달라 공유가
 * 불가능하므로 두 파일이 서로를 가리킨다. 이름이 어긋나면 컴파일이 아니라 런타임 403/400 으로만
 * 드러나기 때문에 리터럴을 흩지 않는다.
 */
export const ON_BEHALF_OF_HEADER = 'X-On-Behalf-Of';
export const ON_BEHALF_OF_TENANT_HEADER = 'X-On-Behalf-Of-Tenant';

/**
 * 대행 호출 목적 헤더(S3). 'share' = 결과가 공유 저장소·발송으로 간다(api 가 AI 판정에 SHARE 판정을 더한다),
 * 'none' = LLM 을 거치지 않는 대행(웹 그래프 뷰어·HITL 승인 — api 가 AI 경로로 판정하지 않는다).
 * 생략 = 채팅(AI 판정). api 대응 상수: InternalCallHeaders.AI_PURPOSE.
 */
export const AI_PURPOSE_HEADER = 'X-AI-Purpose';

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

