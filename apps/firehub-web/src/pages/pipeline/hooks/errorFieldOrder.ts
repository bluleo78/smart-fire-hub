import {
  AI_OUTPUT_COLUMN_NAME_KEY,
  AI_OUTPUT_COLUMNS_FIELD,
  AI_PROMPT_FIELD,
  API_CUSTOM_URL_FIELD,
  API_PAGE_SIZE_FIELD,
  API_PATH_FIELD,
  KV_ROW_KEY,
  MAPPING_ROW_KEY,
  MAPPING_SOURCE_KEY,
  MAPPING_TARGET_KEY,
  PYTHON_OUTPUT_COLUMN_NAME_KEY,
  type RowFieldKey,
} from './stepColumnValidation';

/**
 * 저장 검증 오류 칸의 화면 순서와 API 호출 설정의 접는 절(WD-633).
 * 리듀서는 DOM 을 모르고 하위 설정은 지연 로드·접힌 절이라, 오류 키를 스텝 설정 패널의 배치 순서
 * (이름 → 스크립트 | API 호출 | AI 분류 → 출력 데이터셋 → Python 출력 컬럼)로 매긴다. 배치를 바꾸면 이 표도 바꾼다.
 */

/** 순위 — 앞 원소부터 사전식으로 비교한다(구역, 구역 안 순서, 행 번호, 행 안 칸 순서) */
type Rank = readonly number[];

/** API 호출 설정에서 접을 수 있는 절 */
export type ApiConfigSection = 'headers' | 'queryParams' | 'fieldMappings' | 'pagination';

interface Rule {
  /** 오류 키가 맞으면 순위, 아니면 null */
  rank: (field: string) => Rank | null;
  /** 이 칸이 들어 있는 API 호출 설정 절(접힌 절을 펼 때 쓴다) */
  section?: ApiConfigSection;
}

const exact = (key: string, rank: Rank, section?: ApiConfigSection): Rule => ({
  rank: (field) => (field === key ? rank : null),
  section,
});

const row = (rowKey: RowFieldKey, rankOf: (i: number) => Rank, section?: ApiConfigSection): Rule => ({
  rank: (field) => {
    const i = rowKey.parse(field);
    return i === null ? null : rankOf(i);
  },
  section,
});

/** 위에서 아래 순서로 처음 맞는 규칙을 쓴다 */
const RULES: readonly Rule[] = [
  exact('name', [0]),
  exact('scriptContent', [1]),
  // API 호출: URL·추가 경로 → 헤더 → 쿼리 파라미터 → 필드 매핑(행 → 응답 필드 → 대상 컬럼) → 페이지네이션
  exact(API_CUSTOM_URL_FIELD, [2, 0]),
  exact(API_PATH_FIELD, [2, 0]),
  row(KV_ROW_KEY.headers, (i) => [2, 1, i], 'headers'),
  row(KV_ROW_KEY.queryParams, (i) => [2, 2, i], 'queryParams'),
  row(MAPPING_ROW_KEY, (i) => [2, 3, i, 0], 'fieldMappings'),
  row(MAPPING_SOURCE_KEY, (i) => [2, 3, i, 0], 'fieldMappings'),
  row(MAPPING_TARGET_KEY, (i) => [2, 3, i, 1], 'fieldMappings'),
  exact(API_PAGE_SIZE_FIELD, [2, 4], 'pagination'),
  // AI 분류: 프롬프트 → 출력 컬럼(0개면 「컬럼 추가」, 있으면 행별 이름)
  exact(AI_PROMPT_FIELD, [2, 0]),
  exact(AI_OUTPUT_COLUMNS_FIELD, [2, 1]),
  row(AI_OUTPUT_COLUMN_NAME_KEY, (i) => [2, 1, i]),
  exact('outputDatasetId', [3]),
  row(PYTHON_OUTPUT_COLUMN_NAME_KEY, (i) => [4, i]),
];

/** 오류 키에 맞는 규칙과 순위 — 포커스할 칸이 없는 오류(스키마의 기타 경로 등)는 null */
function matchRule(field: string): { rule: Rule; rank: Rank } | null {
  for (const rule of RULES) {
    const rank = rule.rank(field);
    if (rank) return { rule, rank };
  }
  return null;
}

function compareRank(a: Rank, b: Rank): number {
  const len = Math.min(a.length, b.length);
  for (let i = 0; i < len; i++) {
    if (a[i] !== b[i]) return a[i] - b[i];
  }
  return a.length - b.length;
}

/** 오류 키 목록에서 화면상 첫 번째로 포커스할 칸을 고른다 — 포커스할 칸이 없으면 null */
export function firstFocusableErrorField(fields: Iterable<string>): string | null {
  let best: { field: string; rank: Rank } | null = null;
  for (const field of fields) {
    const rank = matchRule(field)?.rank;
    if (rank && (!best || compareRank(rank, best.rank) < 0)) best = { field, rank };
  }
  return best?.field ?? null;
}

/** 오류 키가 들어 있는 API 호출 설정 절 — 접는 절 밖의 칸이면 null */
export function sectionOf(field: string): ApiConfigSection | null {
  return matchRule(field)?.rule.section ?? null;
}
