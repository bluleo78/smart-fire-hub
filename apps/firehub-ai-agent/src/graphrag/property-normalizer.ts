// 추출된 원시 속성값(문자열)을 온톨로지 data_type 에 맞는 타입값으로 정규화한다.
// 순수 함수 — 실패 시 null(속성 누락). 실문서 추출 품질 증명은 A1 게이트로 이연(여기선 배관).

// 한국어 수량 표현을 정수로 환산한다. '1억 2천만' → 120000000. 실패 시 null.
function parseKoreanNumber(raw: string): number | null {
  let s = raw.replace(/약|,|\s|원/g, '');
  if (s === '') return null;
  // 순수 숫자면 그대로.
  if (/^\d+(\.\d+)?$/.test(s)) return Number(s);
  // 억/천만/만/천 단위 누적 파싱. 매칭된 부분을 소비(strip)해 '천만'이 '천'으로 재매칭되는 중복합산을 막는다.
  const units: Array<[RegExp, number]> = [[/([\d.]+)억/, 1e8], [/([\d.]+)천만/, 1e7], [/([\d.]+)만/, 1e4], [/([\d.]+)천/, 1e3]];
  let total = 0; let matched = false;
  for (const [re, mult] of units) {
    const m = s.match(re);
    if (m) { total += parseFloat(m[1]) * mult; matched = true; s = s.replace(m[0], ''); }
  }
  return matched ? Math.round(total) : null;
}

// 날짜 표기를 'YYYY-MM-DD' 로 정규화한다. 실패 시 null.
function parseDate(raw: string): string | null {
  const m = raw.match(/(\d{4})\s*[-.년]\s*(\d{1,2})\s*[-.월]\s*(\d{1,2})/);
  if (!m) return null;
  const [, y, mo, d] = m;
  return `${y}-${mo.padStart(2, '0')}-${d.padStart(2, '0')}`;
}

// 정규화 결과 + 파싱 상태. status='failed'는 검수 큐(HITL) 탐지 트리거로 쓰인다.
export interface NormalizeResult { value: number | string | null; status: 'ok' | 'failed'; }

// raw를 dataType에 맞게 정규화하고, 실패 여부까지 반환한다(검수 큐 탐지용).
// 빈 입력(공백/빈문자)은 값 없음이지만 status=ok — 원문에 값이 없었던 것이므로 검수 대상이 아니다.
// 비어있지 않은데 파싱이 실패하면 status=failed — 사람이 정정할 수 있게 검수 큐로 보낸다.
export function normalizePropertyChecked(
  dataType: 'text' | 'number' | 'date', unit: string | undefined, raw: string,
): NormalizeResult {
  if (typeof raw !== 'string') return { value: null, status: 'ok' };
  const isBlank = raw.trim() === '';
  let value: number | string | null;
  switch (dataType) {
    case 'number': value = parseKoreanNumber(raw); break;
    case 'date': value = parseDate(raw); break;
    case 'text': { const t = raw.trim().replace(/\s+/g, ' '); value = t === '' ? null : t; break; }
    default: value = null;
  }
  const status: 'ok' | 'failed' = value === null && !isBlank ? 'failed' : 'ok';
  return { value, status };
}

// 하위호환 래퍼 — 값만 필요한 기존 호출부용.
export function normalizeProperty(
  dataType: 'text' | 'number' | 'date', unit: string | undefined, raw: string,
): number | string | null {
  return normalizePropertyChecked(dataType, unit, raw).value;
}
