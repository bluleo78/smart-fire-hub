/**
 * 테넌트 데이터 저장소 방언(WD-79).
 *
 * api 가 `/agent/chat`·`/agent/proactive` 본문에 `dbDialect` 로 실어 보낸다. 방언에 따라 도구 설명·SQL 지침을
 * Tibero 표현으로 바꾼다. 행 검색 도구 노출은 방언이 아니라 아래 「행 검색 가능」 플래그로 가른다(WD-744).
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

/**
 * 「행 검색 가능」 플래그(WD-744) — api 가 `vectorStoreFeatures.supportsRowSearch(backend)` 를 요청 본문 `rowSearch` 로 싣는다.
 *
 * <p>왜 방언과 따로 두는가: 행 검색 가능 여부는 저장소 방언이 아니라 「벡터 저장소 조합 × 데이터 저장소」로 정해진다.
 * qdrant 조합은 Tibero 테넌트도 행 검색이 되고(WD-723), platform(pgvector) 조합의 Tibero 는 안 된다. 방언만 보면 앞의 경우를 놓친다.
 */
export const ROW_SEARCH_ENV = 'ROW_SEARCH';

/** 행 검색 도구 이름 — 행 검색이 꺼진 테넌트에서 숨긴다(api 가 422 로 거절하므로 에이전트가 헛걸음하지 않게). */
export const ROW_SEARCH_TOOLS: ReadonlySet<string> = new Set(['search_dataset_rows']);

/**
 * 임의 입력을 행 검색 플래그로 해석한다. 불리언 true/false 또는 문자열 'true'/'false'(stdio env) 만 값이고, 그 밖은 undefined
 * (= 모름 — {@link resolveRowSearch} 가 방언 기준으로 떨어뜨린다).
 */
export function parseRowSearch(v: unknown): boolean | undefined {
  if (v === true || v === 'true') return true;
  if (v === false || v === 'false') return false;
  return undefined;
}

/**
 * 실제로 행 검색을 노출할지. 플래그가 오면 그 값을, 없으면(옛 api·옛 호출부) WD-79 동작대로 방언으로 정한다 —
 * PG 는 켜고 Tibero 는 끈다(후방호환).
 */
export function resolveRowSearch(dialect: DbDialect | undefined, rowSearch: boolean | undefined): boolean {
  return rowSearch ?? dialect !== 'TIBERO';
}
