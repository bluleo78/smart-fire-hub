/**
 * 리포트 양식 섹션 지시(instruction) 입력 안내 (WD-774).
 *
 * WD-767 이후 리포트 수치는 그 실행의 조회(SQL) 결과와 대조돼, 출처를 찾지 못한 수치에는 경고가 붙는다.
 * 그런데 섹션 지시가 데이터에 없는 값(목표·전망·달성률)을 요구하면 모델이 빈칸을 숫자로 채우거나 「데이터 없음」이 나온다.
 * 리포트가 만들어진 뒤에 경고를 보는 것보다, 지시를 쓰는 곳에서 미리 알려 양식 작성자가 SQL 쪽에서 값을 준비하게 한다.
 */

/** 섹션 지시 입력 칸 아래에 늘 보이는 안내 — 리포트 수치가 어디서 오는지 알린다 (WD-774). */
export const REPORT_NUMBER_SOURCE_GUIDE =
  '리포트의 수치는 조회(SQL) 결과에 있는 값만 쓰입니다. 목표·전망·비율처럼 데이터에 없는 값을 요구하면 「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다. 합계·증감률은 SQL 에서 계산해 주세요.';

/**
 * 데이터에 보통 없는 값을 가리키는 주의 낱말 (WD-774).
 * 이슈가 든 목표·전망·달성률에 전망과 같은 갈래인 예측, 영어로 지시를 쓰는 경우를 위한 영어 낱말을 더했다.
 * 영어 낱말은 소문자로 두고 지시를 소문자로 바꿔 대소문자 없이 비교한다.
 * 「비율」은 SQL 로 계산할 수 있는 값도 많아 오탐이 잦으므로 주의 낱말에서 뺐다(안내 문구에는 남겨 둔다).
 */
const UNSUPPORTED_VALUE_WORDS = [
  '목표',
  '전망',
  '달성률',
  '예측',
  'target',
  'goal',
  'forecast',
  'projection',
] as const;

/** 주의 낱말 하나라도 찾는 정규식 — 낱말끼리 겹치지 않아 대안 순서와 무관하다 (WD-774). */
const UNSUPPORTED_VALUE_PATTERN = new RegExp(UNSUPPORTED_VALUE_WORDS.join('|'), 'g');

/**
 * 금지·제외를 뜻하는 표지 (WD-774).
 * 「목표·전망 수치는 쓰지 마세요」처럼 그 값을 쓰지 말라는 문장은 오히려 바른 지시이므로 주의하지 않는다.
 */
const PROHIBITION_MARKERS = ['마세요', '말 것', '말고', '금지', '제외'] as const;

/**
 * 「지 않」 부정이 금지를 뜻하는 꼴 (WD-783). 둘 중 하나면 그 문장은 금지로 본다.
 *
 * (가) 쓰기·포함 동사의 부정 — 「넣지 않고 실적만」「포함하지 않는 요약」처럼 어미가 무엇이든 그 값을 쓰지 말라는 뜻이다.
 *      어미만 보면 「않고」「않는(관형형)」이 요청 문장에도 나오므로, 동사 줄기로 금지를 가린다.
 * (나) 그 밖의 「지 않」은 서술·연결을 끝맺는 어미(는다·습니다·도록·아요·아야·고·기·겠)일 때만 금지다.
 *      「달성하지 않은 지점」「달성하지 않게 된 지점」처럼 관형·부사형(은·는·을·게·음·으면)은 대상을 가리키는 요청이라 뺀다.
 *      「고」는 「않고 」「않고,」처럼 연결이 끝날 때만 본다(「않고서는」 같은 더 긴 꼴과 섞이지 않게).
 * 「달성/않음」처럼 「지」 없는 「않」은 부정 서술이 아니므로 어느 쪽에도 걸리지 않는다.
 */
const WRITING_VERB_NEGATION = /(?:쓰|넣|포함하|언급하|적|표시하|다루|보여 ?주|만들|기재하|제시하)지 않/;
const NEGATIVE_CLAUSE_ENDING = /지 않(?:는다|습니다|도록|아요|아야|고(?=\s|,|$)|기|겠)/;

/** 문장이 「지 않」 부정으로 금지를 뜻하는지 — 위 두 꼴 중 하나라도 맞으면 금지다 (WD-783). */
function isNegatedProhibition(sentence: string): boolean {
  return WRITING_VERB_NEGATION.test(sentence) || NEGATIVE_CLAUSE_ENDING.test(sentence);
}

/** 문장 경계 — 금지 표지를 문장 단위로 보기 위해 지시를 나눈다 (WD-774). */
const SENTENCE_BOUNDARY = /[.。!?\n]/;

/**
 * 지시에 들어 있는 주의 낱말을 지시에서 처음 나온 순서대로, 중복 없이 돌려준다 (WD-774).
 *
 * 지시를 문장 단위로 나눠, 금지 표지가 있는 문장(「~는 쓰지 마세요」)은 건너뛴다 — 한 문장에서 금지한 낱말이
 * 다른 문장에서 요구되면 그 문장 기준으로 잡힌다.
 * 단순 포함 검사라 「목표치」도 잡는다(의도). 「전망대」 같은 오탐은 저장을 막지 않는 가벼운 주의라 감수한다.
 * 정규식 찾기는 문장 안 나온 순서대로 돌려주므로, 낱말 목록 순서가 아니라 사용자가 쓴 순서로 문구가 읽힌다.
 */
export function findUnsupportedValueWords(instruction: string): string[] {
  const found = new Set<string>();
  for (const sentence of instruction.toLowerCase().split(SENTENCE_BOUNDARY)) {
    if (PROHIBITION_MARKERS.some((marker) => sentence.includes(marker)) || isNegatedProhibition(sentence)) continue;
    for (const match of sentence.matchAll(UNSUPPORTED_VALUE_PATTERN)) found.add(match[0]);
  }
  return [...found];
}

/**
 * 찾은 주의 낱말로 지시 칸 아래 주의 문구를 만든다 (WD-774).
 * 결과는 WD-767 동작(「데이터 없음」 또는 출처 미확인 경고)과 같은 말로 알려, 늘 보이는 안내와 어긋나지 않게 한다.
 * 낱말이 없으면 빈 문자열 — 호출부는 이때 문구를 그리지 않는다.
 */
export function formatUnsupportedValueHint(words: string[]): string {
  if (words.length === 0) return '';
  const quoted = words.map((word) => `「${word}」`).join('·');
  return `지시에 ${quoted} 같은 값이 있습니다 — 조회 결과에 그 값이 없으면 「데이터 없음」으로 나오거나 출처 미확인 경고가 붙습니다.`;
}
