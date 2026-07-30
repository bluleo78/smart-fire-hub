// AI 검수 인박스의 속성 정규화 정정값을 입력 시점에 검증한다(#311).
//
// 왜 클라이언트에도 두는가: 서버(ai-agent property-mutation.ts coercePropertyValue)가 최종 관문이지만,
// 위반을 서버 왕복 후에야 알면 검수자는 "정정 적용"을 누르고 나서야 실패 토스트를 본다.
// 여기서 같은 규칙을 미리 적용해 버튼을 막고 무엇이 잘못됐는지 즉시 알린다.
// 규칙은 서버와 동기화되어야 한다 — 서버가 허용하는 범위의 부분집합이 아니라 동일 규칙으로 맞춘다.

/** text 정정값 상한 — 서버 MAX_TEXT_LENGTH와 동일해야 한다. */
const MAX_TEXT_LENGTH = 1000;

/** 서버 property-normalizer.parseDate와 같은 관용 표기(2026-1-5 / 2026.1.5 / 2026년 1월 5일)를 받는다. */
const DATE_PATTERN = /^(\d{4})\s*[-.년]\s*(\d{1,2})\s*[-.월]\s*(\d{1,2})\s*일?$/;

/** YYYY-MM-DD 구성요소가 달력상 실재하는지 확인한다(2026-02-31처럼 다음 달로 굴러가는 값 배제). */
function isRealDate(y: number, m: number, d: number): boolean {
  if (m < 1 || m > 12 || d < 1) return false;
  const dt = new Date(Date.UTC(y, m - 1, d));
  return dt.getUTCFullYear() === y && dt.getUTCMonth() === m - 1 && dt.getUTCDate() === d;
}

/**
 * 정정값이 속성의 dataType 규약을 만족하는지 검사한다.
 * 위반 시 사용자에게 보여줄 한국어 사유를, 문제가 없으면 null을 돌려준다.
 */
export function validatePropertyCorrection(dataType: 'text' | 'number' | 'date', value: string): string | null {
  const trimmed = value.trim();
  if (trimmed === '') return '정정값을 입력하세요.';

  if (dataType === 'number') {
    // Number('')는 0이므로 공백 검사를 먼저 통과시킨 뒤에만 숫자 판정한다.
    return Number.isFinite(Number(trimmed)) ? null : '숫자만 입력할 수 있습니다(예: 30000000).';
  }

  if (dataType === 'date') {
    const m = trimmed.match(DATE_PATTERN);
    if (!m) return 'YYYY-MM-DD 형식의 날짜를 입력하세요(예: 2026-01-05).';
    return isRealDate(Number(m[1]), Number(m[2]), Number(m[3])) ? null : '존재하지 않는 날짜입니다.';
  }

  const collapsed = trimmed.replace(/\s+/g, ' ');
  return collapsed.length > MAX_TEXT_LENGTH ? `${MAX_TEXT_LENGTH}자 이하로 입력하세요(현재 ${collapsed.length}자).` : null;
}
