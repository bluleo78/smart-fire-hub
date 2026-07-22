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

export function normalizeProperty(
  dataType: 'text' | 'number' | 'date', unit: string | undefined, raw: string,
): number | string | null {
  if (typeof raw !== 'string') return null;
  switch (dataType) {
    case 'number': return parseKoreanNumber(raw);
    case 'date': return parseDate(raw);
    case 'text': { const t = raw.trim().replace(/\s+/g, ' '); return t === '' ? null : t; }
    default: return null;
  }
}
