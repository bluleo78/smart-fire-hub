/**
 * 리포트 수치 출처 대조 (WD-767).
 *
 * 리포트에 적힌 숫자가 이 실행의 데이터 조회 도구 결과에 실제로 있는지 확인해,
 * 계산하거나 지어낸 값일 수 있는 수치를 경고 목록으로 돌려준다. LLM 호출 없이 규칙만으로 판정한다.
 */

/** 출처를 못 찾은 수치 하나 (표기와 앞뒤 문맥) */
export interface UnverifiedNumber {
  text: string;
  context: string;
}

export interface NumberCheck {
  /** 대조를 실제로 했는가 (데이터 조회 결과가 없으면 false) */
  checked: boolean;
  /** 출처를 못 찾은 수치 (같은 표기는 한 번, 최대 20개) */
  unverified: UnverifiedNumber[];
  /** 중복 제거 후 전체 미확인 수치 수 */
  unverifiedTotal: number;
}

/** 대조하지 않았음을 뜻하는 빈 결과. 호출처마다 새 객체를 받아 공유 배열이 바뀌지 않게 한다. */
export function emptyNumberCheck(): NumberCheck {
  return { checked: false, unverified: [], unverifiedTotal: 0 };
}

const MAX_UNVERIFIED = 20;
const CONTEXT_CHARS = 15;
const EPSILON = 1e-9;

/** 데이터 조회 도구의 끝 이름 (접두사 mcp__firehub__·firehub_ 는 무시) */
const DATA_QUERY_TOOLS = new Set(['execute_analytics_query', 'execute_sql_query', 'query_dataset_data']);

/** 표시 단위 → 배수 */
const UNIT_MULTIPLIER: Record<string, number> = {
  천: 1e3,
  만: 1e4,
  억: 1e8,
  조: 1e12,
  // 복합 단위 (「5천만원」 = 5 × 1e7)
  십만: 1e5,
  백만: 1e6,
  천만: 1e7,
  십억: 1e9,
  백억: 1e10,
  천억: 1e11,
};

/** 도구 이름이 데이터 조회 도구인지 판정한다. 출처 수치를 모을 도구 결과를 고르는 데 쓴다. */
export function isDataQueryTool(toolName: string): boolean {
  return DATA_QUERY_TOOLS.has(toolName.replace(/^(mcp__firehub__|firehub_)/, ''));
}

/** 날짜·시각·분기 표기는 수치 검증 대상이 아니므로 같은 길이의 공백으로 가린다. */
function maskNonNumbers(text: string): string {
  const blank = (m: string) => ' '.repeat(m.length);
  return text
    .replace(/\d{4}[-./]\d{1,2}[-./]\d{1,2}/g, blank)
    // 짧은 날짜(MM-DD, MM/DD, M/D): 월 1~12, 일 1~31 일 때만 (소수·쉼표 숫자의 일부는 제외)
    .replace(/(?<![\d.,/-])(?:1[0-2]|0?[1-9])[-/](?:3[01]|[12]\d|0?[1-9])(?![\d/-]|\.\d|[조억만천%원])/g, blank)
    .replace(/\b\d{1,2}:\d{2}(?::\d{2})?\b/g, blank)
    .replace(/\bQ[1-4]\b/gi, blank);
}

interface Token {
  /** 숫자+단위(+%) 표기. 「4.5억 원」이면 「4.5억」 */
  text: string;
  value: number;
  decimals: number;
  multiplier: number;
  percent: boolean;
  start: number;
  end: number;
}

// 숫자 바로 뒤에 붙으면 개수가 아니라 날짜·순서 표기인 접미사 (분기를 분보다 먼저)
const SKIP_SUFFIX = /^(분기|월|일|시|분|초|주차|주|순위|위|번째|차|등)/;
// 위 접미 글자로 시작하지만 날짜·서수가 아니라 명사인 흔한 낱말 — 「1,250차량」「35시설」은 개수이므로 대조한다
const SKIP_SUFFIX_NOUN = /^(차량|차로|시설|시군|분석|분야|분류|일반|위원|위험|주택|주민|등급)/;

// 숫자 바로 뒤의 표시 단위. 뒤에 단위가 아닌 단어(만큼·천천히·조금·억지 등)가 이어질 때만 단위가 아니다.
// 조사(은·이고·의 …)·개수 단위·원·한글 아닌 문자·끝이 오면 단위로 본다 (「3.5억이고」「5억은」).
const UNIT_RE = /^(?:(?:십만|백만|천만)(?!큼)|(?:십억|백억|천억)(?!지)|만(?!큼)|천(?!천히)|조(?!금|사|건|치|정|회|직)|억(?!지))/;

/**
 * 「3만5천」「1억2천만」처럼 큰 단위 바로 뒤(공백 없이)에 더 작은 단위의 수가 이어지면 한 수로 합친다.
 * 따로 읽으면 「3만」「5천」이 각각 출처와 안 맞아 거짓 경고가 나므로, 합친 값과 가장 작은 단위를 돌려준다.
 * 이어지는 부분이 없으면 null.
 */
function extendCompound(text: string, from: number, firstValue: number, firstMultiplier: number): { end: number; value: number; multiplier: number } | null {
  let end = from;
  let total = firstValue * firstMultiplier;
  let multiplier = firstMultiplier;
  for (;;) {
    const part = /^(\d{1,4})(?![\d,.])/.exec(text.slice(end));
    if (!part) break;
    const unit = UNIT_RE.exec(text.slice(end + part[0].length))?.[0];
    // 더 작은 단위일 때만 이어 붙인다 (「3만5만」 같은 표기는 합치지 않는다)
    if (!unit || UNIT_MULTIPLIER[unit] >= multiplier) break;
    multiplier = UNIT_MULTIPLIER[unit];
    total += Number(part[1]) * multiplier;
    end += part[0].length + unit.length;
  }
  // 가장 작은 단위 기준 정수로 나눈 값까지 돌려줘 호출 쪽이 다시 계산하지 않게 한다
  return end === from ? null : { end, value: Math.round(total / multiplier), multiplier };
}

/** 텍스트에서 검증 대상 숫자 토큰을 뽑는다. skip=true 면 날짜·연도·서수·작은 정수를 제외한다. */
function tokenize(raw: string, skip: boolean): Token[] {
  const text = skip ? maskNonNumbers(raw) : raw;
  const re = /(?<![A-Za-z0-9_.])(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?/g;
  const tokens: Token[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    const intPart = m[1].replace(/,/g, '');
    const frac = m[2] ?? '';
    let end = m.index + m[0].length;
    const rest = text.slice(end);
    const unitChar = UNIT_RE.exec(rest)?.[0];
    const percent = !unitChar && rest.startsWith('%');
    if (skip && !unitChar && !percent) {
      if (SKIP_SUFFIX.test(rest) && !SKIP_SUFFIX_NOUN.test(rest)) continue;
      // 연도: 19xx·20xx 단독 4자리 (쉼표·소수 없음)
      if (!frac && !m[1].includes(',') && /^(19|20)\d{2}$/.test(m[1])) continue;
      // 단위·% 없는 0~10 정수는 개수·서수로 본다
      if (!frac && Number(intPart) <= 10) continue;
    }
    if (unitChar) end += unitChar.length;
    else if (percent) end += 1;
    let value = Number(intPart + frac);
    let decimals = frac ? frac.length - 1 : 0;
    let multiplier = unitChar ? UNIT_MULTIPLIER[unitChar] : 1;
    // 섞인 한글 수 표기(「1억2천만」): 소수 없는 정수 + 단위 뒤에만 이어 붙인다. 표시 자릿수는 가장 작은 단위 기준 정수.
    const compound = unitChar && !frac ? extendCompound(text, end, value, multiplier) : null;
    if (compound) {
      end = compound.end;
      multiplier = compound.multiplier;
      value = compound.value;
      decimals = 0;
      // 이어 붙인 뒤쪽 숫자를 다음 토큰으로 다시 읽지 않게 검색 위치를 옮긴다
      re.lastIndex = end;
    }
    tokens.push({
      text: text.slice(m.index, end),
      value,
      decimals,
      multiplier,
      percent,
      start: m.index,
      end,
    });
  }
  return tokens;
}

/** 소수 k자리 반올림 (부동소수 오차를 EPSILON 으로 흡수) */
function roundTo(x: number, k: number): number {
  const p = 10 ** k;
  return Math.round(x * p + EPSILON) / p;
}

/**
 * 출처 텍스트 하나의 숫자 리터럴(JSON 숫자·따옴표 숫자·쉼표 천단위·소수·지수)의 절댓값을 into 에 모은다.
 * 라우트가 도구 결과를 받을 때마다 바로 불러, 조회 결과 원문을 실행 끝까지 들고 있지 않게 한다.
 */
export function collectSourceNumbers(text: string, into: Set<number>): void {
  const add = (n: number) => {
    const abs = Math.abs(n);
    if (Number.isFinite(abs)) into.add(abs);
  };
  // 1) 숫자 하나하나 (JSON 배열 [100,200] 의 쉼표는 구분자이므로 합치지 않는다).
  //    영문자·숫자·_ 에 바로 붙은 숫자(col_2023·a1b2)와, 뒤에 영문자를 거쳐 다시 숫자가 이어지는 숫자(41d4 같은
  //    UUID 조각)는 값이 아니라 식별자의 일부라 세지 않는다. 「12km」「1200ms」처럼 문자만 붙은 단위 값과
  //    ISO 시각 「16T09:30」의 각 부분은 출처로 센다. 뒤의 (?!\d) 는 역추적으로 숫자 일부만 잘려 잡히는 것을 막는다.
  const re = /(?:(?<![A-Za-z0-9_.])|(?<=\dT))\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?!\d|\.\d|(?!T\d{2}:)[A-Za-z_]+\d)/g;
  for (const m of text.matchAll(re)) {
    // `-` 는 바로 앞 낱말에 영문자·_ 가 섞였을 때만 식별자 연결로 보고 막는다 — 음수 부호("-3.5", ": -12")와
    // 날짜 2026-11-16 의 각 부분은 센다. 가변 길이 lookbehind 는 긴 영숫자 열에서 이차 시간이 들므로
    // 매치 앞 40자만 잘라 확인한다.
    const before = text.slice(Math.max(0, m.index - 40), m.index);
    if (/[A-Za-z_][A-Za-z0-9_]*-$/.test(before)) continue;
    add(Number(m[0]));
  }
  // 2) 따옴표 문자열 전체가 쉼표 천단위 숫자인 경우만 합친 값을 더한다 ("1,234")
  for (const m of text.matchAll(/"(-?\d{1,3}(?:,\d{3})+(?:\.\d+)?)"/g)) {
    add(Number(m[1].replace(/,/g, '')));
  }
}

/** 표시값이 출처 값 중 하나를 표시 자릿수로 반올림한 값과 같은지 본다. */
function matchesSource(token: Token, sources: Iterable<number>): boolean {
  const k = token.decimals;
  const d = roundTo(token.value, k);
  for (const s of sources) {
    if (token.percent) {
      if (Math.abs(roundTo(s, k) - d) < EPSILON || Math.abs(roundTo(s * 100, k) - d) < EPSILON) return true;
    } else if (Math.abs(roundTo(s / token.multiplier, k) - d) < EPSILON) {
      return true;
    }
  }
  return false;
}

/** 줄바꿈을 공백으로 바꾼 토큰 앞뒤 15자 문맥 */
function contextOf(text: string, token: Token): string {
  const from = Math.max(0, token.start - CONTEXT_CHARS);
  const to = Math.min(text.length, token.end + CONTEXT_CHARS);
  return text.slice(from, to).replace(/\s*[\r\n]+\s*/g, ' ');
}

/**
 * 리포트 숫자를 출처(데이터 조회 결과에서 collectSourceNumbers 로 모은 숫자)와 대조한다.
 *
 * 검증하지 않는(checked=false) 경우:
 * - 출처 숫자가 하나도 없을 때 — 데이터 조회 결과가 없거나, 있어도 숫자가 하나도 없으면(빈 결과 등)
 *   모든 수치가 경고가 되는 소음이 나므로 대조하지 않는다.
 * - 대조할 리포트 본문이 전부 비었거나 공백일 때 — 대조하지 않은 것을 「경고 없음(검증 통과)」로 보이지 않게 한다.
 */
export function checkReportNumbers(input: {
  reportTexts: string[];
  sourceNumbers: Set<number>;
  allowedTexts: string[];
}): NumberCheck {
  if (input.sourceNumbers.size === 0 || input.reportTexts.every((t) => !t.trim())) {
    return emptyNumberCheck();
  }
  const allowed = new Set(input.allowedTexts.flatMap((t) => tokenize(t, false).map((x) => x.text)));
  const seen = new Set<string>();
  const unverified: UnverifiedNumber[] = [];
  for (const report of input.reportTexts) {
    for (const token of tokenize(report, true)) {
      if (seen.has(token.text)) continue;
      if (allowed.has(token.text) || matchesSource(token, input.sourceNumbers)) continue;
      seen.add(token.text);
      if (unverified.length < MAX_UNVERIFIED) {
        unverified.push({ text: token.text, context: contextOf(report, token) });
      }
    }
  }
  return { checked: true, unverified, unverifiedTotal: seen.size };
}

/**
 * tool_use ↔ tool_result 짝짓기 (WD-767). tool_result 는 tool_use_id 만 싣는 공급자(sdk·cli)가 있어
 * id → 도구 이름 맵으로 결과가 어느 도구의 것인지 정한다.
 */
export function createToolNameResolver() {
  const nameById = new Map<string, string>();
  let lastToolName = '';
  const idOf = (event: Record<string, unknown>) => (typeof event.toolUseId === 'string' ? event.toolUseId : '');
  return {
    /** tool_use 이벤트를 기록한다 (마지막 도구 이름과 id 짝). */
    onUse(event: Record<string, unknown>): void {
      lastToolName = String(event.toolName ?? '');
      const id = idOf(event);
      if (id) nameById.set(id, lastToolName);
    },
    /**
     * tool_result 가 속한 도구 이름. 결과가 id(toolUseId, 또는 sdk·cli 처럼 toolName 자리에 실린 tool_use_id)로
     * 맵에 있으면 그 도구다. id 를 명시해 실었는데 맵에 없으면 알 수 없는 호출이라 빈 문자열(출처로 치지 않음).
     * id 가 전혀 없는 공급자일 때만 직전 tool_use 이름으로 판단한다(경고 전용이라 비데이터 결과가 섞여도 감수).
     */
    resolve(event: Record<string, unknown>): string {
      const explicitId = idOf(event);
      const mapped = nameById.get(explicitId || String(event.toolName ?? ''));
      return mapped ?? (explicitId ? '' : lastToolName);
    },
  };
}
