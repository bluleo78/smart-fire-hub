/**
 * 테넌트 데이터 저장소 방언(WD-79).
 *
 * api 가 `/agent/chat`·`/agent/proactive` 본문에 `dbDialect` 로 실어 보낸다. 방언에 따라
 * Tibero 에서 지원하지 않는 도구(행 검색)를 숨기고 도구 설명을 Tibero 표현으로 바꾼다.
 */
export type DbDialect = 'POSTGRESQL' | 'TIBERO';

/**
 * 방언을 모를 때(요청에 없음·옛 호출부) 쓰는 기본값. 기존 PG 테넌트의 동작을 바꾸지 않도록 PG 다.
 * 기본값을 호출부마다 리터럴로 쓰지 않고 이 상수 하나로 둔다.
 */
export const DEFAULT_DB_DIALECT: DbDialect = 'POSTGRESQL';

/** stdio MCP 자식 프로세스(agent-cli·agent-opencode)에 방언을 전달하는 환경변수 이름 */
export const DB_DIALECT_ENV = 'DB_DIALECT';

/**
 * 임의 입력을 방언으로 해석한다.
 * 정확히 `'TIBERO'` 일 때만 TIBERO 이고 그 외(없음·소문자·오타·비문자열)는 POSTGRESQL 이다 —
 * 기존 PG 테넌트의 동작을 절대 바꾸지 않기 위해 모르는 값은 PG 로 떨어뜨린다.
 */
export function parseDbDialect(v: unknown): DbDialect {
  return v === 'TIBERO' ? 'TIBERO' : DEFAULT_DB_DIALECT;
}
