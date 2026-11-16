/**
 * URL 패턴을 파싱하여 현재 화면 정보를 한국어 텍스트로 반환한다.
 * AI 에이전트에게 매 메시지마다 전달되어 사용자의 작업 맥락을 제공한다.
 */

import { parseRouteId } from '../../lib/route-id';

/** 화면 컨텍스트에 이름을 붙일 수 있는 상세 화면의 대상 종류 (WD-759). */
export type ScreenEntityKind = 'dataset' | 'pipeline' | 'dashboard';

/**
 * 대상 종류·id 로 이미 알고 있는 이름을 돌려주는 함수 (WD-759).
 * 호출자가 새 요청 없이(예: TanStack Query 캐시) 아는 이름만 돌려주고, 모르면 undefined 를 돌려준다.
 */
export type ScreenNameLookup = (kind: ScreenEntityKind, id: number) => string | undefined;

/** 화면 컨텍스트에 넣는 이름의 최대 길이 — 사용자 입력이 LLM 프롬프트에 들어가므로 주입면을 줄인다 (WD-759). */
const SCREEN_NAME_MAX_LENGTH = 100;

/**
 * 사용자가 입력한 이름을 프롬프트에 넣어도 안전한 한 줄로 정리한다 (WD-759).
 * 줄바꿈은 프롬프트에 새 지시처럼 보이는 줄을 만들 수 있고, 대괄호는 `[현재 화면]` 같은 태그를 흉내 낼 수 있어 지운다.
 * 이름은 화면 문구에서 「」 로 감싼 데이터로 넣으므로, 이름 안의 「·」 도 지워 인용을 닫고 지시문을 이어 붙이지 못하게 한다.
 * 공백을 하나로 모으고 앞뒤를 자른 뒤 100자로 자른다 — UTF-16 단위로 자르면 이모지 같은 서로게이트 쌍이 반쪽으로
 * 잘려 깨진 글자가 남으므로 코드 포인트 단위로 자른다. 남는 글자가 없으면 이름이 없는 것으로 본다(undefined).
 */
export function sanitizeScreenName(raw: string | null | undefined): string | undefined {
  if (typeof raw !== 'string') return undefined;
  const normalized = raw
    .replace(/[\r\n]+/g, ' ')
    .replace(/[[\]「」]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  const cleaned = Array.from(normalized).slice(0, SCREEN_NAME_MAX_LENGTH).join('').trim();
  return cleaned.length > 0 ? cleaned : undefined;
}

/**
 * 상세 화면 문구를 만든다 — 이름을 알면 `(ID: n, 이름: 「…」)`, 모르면 지금과 같은 `(ID: n)` (WD-759).
 * 이름을 붙여야 에이전트가 질문이 지금 보는 대상과 관련 있는지 판단할 수 있다.
 * 이름은 사용자가 정한 글이라 「」 로 감싸 지시가 아닌 인용된 데이터로 읽히게 한다(서버가 시스템 프롬프트에 붙인다).
 * id 를 조회용 숫자로 읽지 못하면(0·범위 밖) lookup 을 부르지 않고 ID 만 쓴다.
 */
function buildDetail(
  label: string,
  kind: ScreenEntityKind,
  rawId: string,
  lookupName: ScreenNameLookup | undefined,
): string {
  const id = parseRouteId(rawId);
  const name = lookupName && typeof id === 'number' ? sanitizeScreenName(lookupName(kind, id)) : undefined;
  return name ? `${label} (ID: ${rawId}, 이름: 「${name}」)` : `${label} (ID: ${rawId})`;
}

const SCREEN_CONTEXT_MAP: {
  pattern: RegExp;
  build: (match: RegExpMatchArray, lookupName?: ScreenNameLookup) => string;
}[] = [
  { pattern: /^\/$/, build: () => '홈 대시보드' },
  { pattern: /^\/data\/categories$/, build: () => '카테고리 목록' },
  { pattern: /^\/data\/datasets$/, build: () => '데이터셋 목록' },
  { pattern: /^\/data\/datasets\/new$/, build: () => '새 데이터셋 생성 페이지' },
  { pattern: /^\/data\/datasets\/(\d+)$/, build: (m, lookup) => buildDetail('데이터셋 상세', 'dataset', m[1], lookup) },
  { pattern: /^\/pipelines$/, build: () => '파이프라인 목록' },
  { pattern: /^\/pipelines\/new$/, build: () => '새 파이프라인 생성' },
  { pattern: /^\/pipelines\/(\d+)\/executions\/(\d+)$/, build: (m) => `파이프라인 실행 상세 (파이프라인 ID: ${m[1]}, 실행 ID: ${m[2]})` },
  { pattern: /^\/pipelines\/(\d+)$/, build: (m, lookup) => buildDetail('파이프라인 편집', 'pipeline', m[1], lookup) },
  { pattern: /^\/analytics\/queries$/, build: () => '쿼리 목록' },
  { pattern: /^\/analytics\/queries\/new$/, build: () => '새 쿼리 작성' },
  { pattern: /^\/analytics\/queries\/(\d+)$/, build: (m) => `쿼리 편집 (ID: ${m[1]})` },
  { pattern: /^\/analytics\/charts$/, build: () => '차트 목록' },
  { pattern: /^\/analytics\/charts\/new$/, build: () => '새 차트 생성' },
  { pattern: /^\/analytics\/charts\/(\d+)$/, build: (m) => `차트 편집 (ID: ${m[1]})` },
  { pattern: /^\/analytics\/dashboards$/, build: () => '대시보드 목록' },
  { pattern: /^\/analytics\/dashboards\/(\d+)$/, build: (m, lookup) => buildDetail('대시보드 편집', 'dashboard', m[1], lookup) },
  { pattern: /^\/ai-insights\/jobs$/, build: () => 'AI 인사이트 작업 목록' },
  { pattern: /^\/ai-insights\/reports$/, build: () => 'AI 인사이트 리포트' },
  { pattern: /^\/admin\/settings$/, build: () => '시스템 설정' },
  { pattern: /^\/admin\/users\/(\d+)$/, build: (m) => `사용자 상세 (ID: ${m[1]})` },
  { pattern: /^\/admin\/roles\/(\d+)$/, build: (m) => `역할 상세 (ID: ${m[1]})` },
  { pattern: /^\/admin\//, build: () => '관리자 페이지' },
  { pattern: /^\/profile$/, build: () => '프로필 페이지' },
];

/**
 * 현재 경로의 화면 컨텍스트 문자열을 만든다.
 * lookupName 을 주면 데이터셋 상세·파이프라인 편집·대시보드 편집에 이미 아는 이름을 붙인다 (WD-759).
 * lookupName 이 없거나 이름을 모르면 예전과 똑같은 문자열을 돌려준다(뒤 호환).
 */
export function buildScreenContext(pathname: string, lookupName?: ScreenNameLookup): string | undefined {
  for (const { pattern, build } of SCREEN_CONTEXT_MAP) {
    const match = pathname.match(pattern);
    if (match) {
      return `[현재 화면] ${build(match, lookupName)}`;
    }
  }
  return undefined;
}
