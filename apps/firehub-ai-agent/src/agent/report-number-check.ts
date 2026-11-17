/**
 * 리포트 수치 출처 대조 (WD-767).
 *
 * 리포트에 적힌 숫자가 이 실행의 데이터 조회 도구 결과에 실제로 있는지 확인해,
 * 계산하거나 지어낸 값일 수 있는 수치를 경고 목록으로 돌려준다. LLM 호출 없이 규칙만으로 판정한다.
 */

import { ROW_SEARCH_TOOLS } from './db-dialect.js';

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

/**
 * 결과에 데이터 값이 실리는 조회 도구의 끝 이름 (접두사 mcp__firehub__·firehub_ 는 무시).
 * 빠진 도구의 결과로 쓴 수치는 모두 출처 없는 수치로 경고된다 (WD-775).
 */
const DATA_QUERY_TOOLS = new Set([
  'execute_analytics_query',
  'execute_sql_query',
  'query_dataset_data',
  'run_saved_query',
  'get_chart_data',
  'get_row_count',
  'summarize_dataset_files',
  'graphrag_structured_query',
  ...ROW_SEARCH_TOOLS,
]);

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

/**
 * WD-775: 짧은 날짜 뒤에 바로 붙으면 날짜가 아니라 분수·범위(「10/20건」「3-15건」)로 보는 개수 단위.
 * 날짜 뒤에 흔히 붙는 낱말(11/16대비·11/16회의·11/16개최·11/16종합 …)은 단위가 아니므로 뒤 글자로 걸러 계속 날짜로 가린다.
 */
const COUNT_UNIT_AFTER_DATE = '건(?![강설의])|개(?![최시장강막통편선])|명(?![단절])|곳|회(?![의차담견])|종(?![합료])|가구|대(?![비상회책응])';

/**
 * WD-775: 조문 번호(「제3조」「제 3조」「제13조의2」)와 「제N」 서수는 금액·개수가 아니라 번호다. 「조」를 조(兆) 단위로 읽어
 * 「3조」 경고를 내지 않도록 통째로 가린다. 「제」 뒤 공백 한 칸(「제 3조」)도 같은 표기로 본다. 앞에 한글이 붙은 경우
 * (「규제 3조원」「경제3조원」처럼 「제」가 낱말 끝)는 접두사가 아니라 가리지 않는다.
 */
const ORDINAL_JE = /(?<![가-힣])제[ \t]?\d+(?:조(?:의\d+)?)?/g;

/** WD-775: 줄 첫머리 — 앞에 공백·마크다운 제목 `#` 만 있는 자리. 목차·절 번호 판정에 함께 쓰고, 이 부분은 가리지 않고 남긴다. */
const LINE_LEAD = String.raw`^([ \t]*(?:#+[ \t]*)?)`;

/**
 * WD-775: 줄 첫머리의 「11. 」「## 12. 」 같은 목차·목록 번호. 마침표 뒤에 공백이 와야 하므로 「11.5%」(소수)는 해당하지
 * 않고, 줄 첫머리가 아닌 문장 안 「11. 」도 그대로 대조한다.
 */
const LIST_NUMBER = new RegExp(String.raw`${LINE_LEAD}(\d+\.)(?=[ \t])`, 'gm');

/**
 * WD-775: 줄 첫머리의 다단계 절 번호 「## 3.1 출동 현황」「### 2.4 소결」「12.3. 기타」「4.10.2 세부」.
 * 소수처럼 생겨 예전엔 출처 없는 수치로 경고됐다. 번호 뒤에 공백이 오고, 그 다음이 숫자·%·금액 단위(「13.5 억」
 * 「3.5 천만원」처럼 천·만·억·조·원이 한글 없이 끝나는 낱말)가 아닐 때만 번호로 본다 — 「3.5억 원」「12.5% 증가」는
 * 숫자 뒤에 공백이 없어 해당하지 않고, 「원인」「조사」「만족도」 같은 제목 낱말은 단위가 아니라 번호로 가린다.
 * WD-783: 줄 첫머리 2단계 「N.N」은 표지(`#`)·끝 마침표가 없으면 소수로 보고 대조한다(「<td>4.5 시간</td>」을 놓치지 않으려는 것 —
 * 표지 없는 「3.1 개요」는 경고될 수 있으나 경고 전용이라 감수). 3단계 이상은 늘 번호다.
 */
const SECTION_NUMBER = new RegExp(
  String.raw`${LINE_LEAD}((?<=#[ \t]*)\d+(?:\.\d+)+\.?|\d+(?:\.\d+){2,}\.?|\d+(?:\.\d+)+\.)(?=[ \t]+(?![\d%]|(?:[천만억조]+원?|원)(?![가-힣]))\S)`,
  'gm',
);

/**
 * 짧은 날짜(MM-DD, MM/DD, M/D): 월 1~12, 일 1~31 일 때만 (소수·쉼표 숫자의 일부는 제외).
 * WD-775: 뒤에 개수 단위가 바로 붙으면(「10/20건」) 분수·범위라 가리지 않는다. 호출마다 정규식을 새로 만들지 않게
 * 모듈 상수로 둔다(g 플래그지만 String.replace 만 쓰므로 lastIndex 가 호출 사이에 남지 않는다).
 */
const SHORT_DATE = new RegExp(
  String.raw`(?<![\d.,/-])(?:1[0-2]|0?[1-9])[-/](?:3[01]|[12]\d|0?[1-9])(?![\d/-]|\.\d|[조억만천%원]|${COUNT_UNIT_AFTER_DATE})`,
  'g',
);

/** 가린 자리의 글자 위치가 그대로 남도록 같은 길이의 공백으로 바꾼다 (토큰 문맥 위치가 원문과 맞게). */
function blank(m: string): string {
  return ' '.repeat(m.length);
}

/** WD-775: LINE_LEAD 로 시작하는 번호 정규식의 치환 — 앞의 공백·# 은 남기고 번호만 공백으로 가린다. */
function blankNumberKeepLead(_m: string, lead: string, num: string): string {
  return lead + blank(num);
}

/** 날짜·시각·분기·조문 번호·목차 번호 표기는 수치 검증 대상이 아니므로 같은 길이의 공백으로 가린다. */
function maskNonNumbers(text: string): string {
  return text
    .replace(/\d{4}[-./]\d{1,2}[-./]\d{1,2}/g, blank)
    .replace(SHORT_DATE, blank)
    .replace(/\b\d{1,2}:\d{2}(?::\d{2})?\b/g, blank)
    .replace(/\bQ[1-4]\b/gi, blank)
    .replace(ORDINAL_JE, blank)
    .replace(LIST_NUMBER, blankNumberKeepLead)
    .replace(SECTION_NUMBER, blankNumberKeepLead);
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
 * WD-775: 조(組, 모둠) 쓰임이 분명한 「조」 뒷말. 조(兆)와 글자만으로는 가를 수 없어, 이 뒷말이 오고 앞 수가 10 이하
 * 정수일 때만(조 편성·교대 인원은 작은 수) 단위에서 뺀다 — 「추경 15조 편성」은 조(兆)로 읽고, 「3조 편성」 예산은 경고
 * 전용이라 놓치는 쪽을 감수한다. 「조가」「조로」 단독은 금액 쓰임(「3조가 투입」「2조로 늘어」)이 훨씬 흔해 넣지 않는다.
 */
const JO_GROUP_FOLLOW = /^(?:\s*(?:편성|편제|교대|근무|구성|분할|순환|\d+교대)|으?로\s*(?:나누|나눠|편성|구성|분할))/;

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
    // 쉼표·소수 없는 정수인가, 그중 10 이하인가 — 조(組)·연도·작은 정수 판정에 함께 쓴다 (WD-775)
    const plainInt = !frac && !m[1].includes(',');
    const small = plainInt && Number(intPart) <= 10;
    let unitChar = UNIT_RE.exec(rest)?.[0];
    // WD-775: 작은 정수 + 조(組) 쓰임(「2조 편성」)은 조(兆) 단위가 아니다 (JO_GROUP_FOLLOW 주석 참고)
    if (unitChar === '조' && small && JO_GROUP_FOLLOW.test(rest.slice(1))) unitChar = undefined;
    const percent = !unitChar && rest.startsWith('%');
    if (skip && !unitChar && !percent) {
      if (SKIP_SUFFIX.test(rest) && !SKIP_SUFFIX_NOUN.test(rest)) continue;
      // 연도: 19xx·20xx 단독 4자리 (쉼표·소수 없음)
      if (plainInt && /^(19|20)\d{2}$/.test(m[1])) continue;
      // 단위·% 없는 0~10 정수는 개수·서수로 본다
      if (small) continue;
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

/** 소수 k자리 반올림 값을 10^k 배한 정수 키 (부동소수 오차를 EPSILON 으로 흡수). 소수끼리 오차 비교를 피한다 (WD-775). */
function roundKey(x: number, k: number): number {
  return Math.round(x * 10 ** k + EPSILON);
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

/**
 * 출처 숫자를 토큰 표시 방식(퍼센트 여부·단위 배수·소수 자릿수)별 반올림 키로 찾는 색인 (WD-775).
 * 토큰마다 숫자 전체를 훑으면(토큰 수 × 숫자 수) 출처가 수만 개일 때 수 초가 걸려, 조합마다 처음 쓰일 때 한 번만 키 Set 을
 * 만들어 둔다. 숫자는 복사하지 않고 받아 빌드할 때 훑는다.
 * WD-783: 출처 숫자(sourceNumbers)만 받는다 — 허용 숫자는 반올림하면 무관한 값까지 허용돼 AllowedValues 로 따로 본다.
 */
class NumberIndex {
  private readonly cache = new Map<string, Set<number>>();

  constructor(private readonly numbers: Iterable<number>) {}

  /**
   * 표시값이 숫자 중 하나를 표시 자릿수로 반올림한 값과 같은지 본다.
   * 단위(천·만·억·조·복합)가 붙은 토큰은 숫자 ÷ 배수 외에 숫자 그대로도 비교한다 (WD-781) — 조회가 이미 그 단위로
   * 집계한 열(「올해억: 1.3」)을 「1.3억」으로 옮겨 적은 경우를 거짓 경고하지 않기 위해서다. 다만 소수가 있는 표시값만
   * 이렇게 본다 — 정수(「5억」「15조」)는 출처에 흔한 개수·순서 값(5·15)에 우연히 맞아 지어낸 금액이 통과하기 쉬워서다.
   */
  matches(token: Token): boolean {
    if (this.keysFor(token.percent, token.multiplier, token.decimals).has(roundKey(token.value, token.decimals))) {
      return true;
    }
    const k = token.decimals;
    if (token.percent || token.multiplier <= 1 || k === 0) return false;
    return this.keysFor(false, 1, k).has(roundKey(token.value, k));
  }

  /** (퍼센트 여부·배수·자릿수) 조합의 반올림 키 Set — 처음 쓰일 때 한 번만 만든다. */
  private keysFor(percent: boolean, multiplier: number, k: number): Set<number> {
    // 퍼센트 토큰은 단위가 붙지 않아 배수가 늘 1 이지만, 키를 분명히 가르려고 p/n 접두사를 따로 둔다
    const key = `${percent ? 'p' : 'n'}:${multiplier}:${k}`;
    let keys = this.cache.get(key);
    if (!keys) {
      keys = new Set<number>();
      for (const s of this.numbers) {
        if (percent) {
          // 퍼센트는 출처가 비율(0.123 → 12.3%)이든 이미 퍼센트 값(12.3)이든 맞는 것으로 본다
          keys.add(roundKey(s, k));
          keys.add(roundKey(s * 100, k));
        } else {
          keys.add(roundKey(s / multiplier, k));
        }
      }
      this.cache.set(key, keys);
    }
    return keys;
  }
}

/**
 * WD-783: 양식·프롬프트·컨텍스트에서 모은 허용 값. 출처와 달리 표시 자릿수 반올림으로 맞추지 않고, 토큰 값이 허용 값과
 * 같을 때(상대오차 EPSILON 이내)만 허용한다. 허용 텍스트에는 datasetId·페이지 크기 같은 값과 무관한 숫자가 섞여, 반올림으로
 * 맞추면 「1024」가 「1천명」을 허용하는 식으로 지어낸 반올림 수치를 놓친다. 정렬 배열 + 이진 탐색으로 토큰마다 O(log n).
 */
class AllowedValues {
  private readonly sorted: number[];

  constructor(values: Iterable<number>) {
    this.sorted = [...values].sort((a, b) => a - b);
  }

  /** 리포트 토큰의 실제 값(퍼센트는 표시값, 그 밖엔 표시값 × 단위 배수)이 허용 값 중 하나와 같은지 본다. */
  matches(token: Token): boolean {
    if (token.percent) {
      // 퍼센트는 허용 값이 이미 퍼센트(12.5)든 비율(0.125)이든 같은 값으로 본다 (출처 색인과 같은 규칙)
      return this.has(token.value) || this.has(token.value / 100);
    }
    return this.has(token.value * token.multiplier);
  }

  /** v 와 상대오차 EPSILON 이내인 값이 있는가 — v 이상 첫 값과 그 바로 앞 값만 보면 된다. */
  private has(v: number): boolean {
    const a = this.sorted;
    let lo = 0;
    let hi = a.length;
    while (lo < hi) {
      const mid = (lo + hi) >>> 1;
      if (a[mid] < v) lo = mid + 1;
      else hi = mid;
    }
    const near = (x: number | undefined) => x !== undefined && Math.abs(x - v) <= EPSILON * Math.max(Math.abs(x), Math.abs(v));
    return near(a[lo]) || near(a[lo - 1]);
  }
}

/**
 * % 토큰이 같은 줄의 확인된 두 수치로 계산한 증감률·비율인지 본다 (WD-781).
 * 순서쌍 (a, b) 마다 증감률 (b−a)/a×100 과 비율 b/a×100 의 절댓값을 표시 자릿수로 반올림해 표시값과 비교한다.
 * 「-74.2%」의 부호는 토큰에 들어가지 않으므로 절댓값으로 비교하고, a=0 은 나눗셈이 정의되지 않아 건너뛴다.
 * verifiedValues 에는 출처·허용 텍스트로 확인된 값만 넣는다 — 지어낸 두 값으로 만든 비율을 통과시키지 않기 위해서다.
 */
function matchesComputedRate(token: Token, verifiedValues: number[]): boolean {
  const k = token.decimals;
  const d = roundKey(token.value, k);
  for (let i = 0; i < verifiedValues.length; i++) {
    const a = verifiedValues[i];
    if (a === 0) continue;
    for (let j = 0; j < verifiedValues.length; j++) {
      if (i === j) continue;
      const b = verifiedValues[j];
      if (roundKey(Math.abs(((b - a) / a) * 100), k) === d || roundKey(Math.abs((b / a) * 100), k) === d) return true;
    }
  }
  return false;
}

/** 위치 pos 가 몇 번째 줄(0부터)에 있는지. lineStarts 는 각 줄의 시작 위치(오름차순)라 이분 탐색한다(긴 리포트에서 토큰마다 줄을 훑지 않게). */
function lineIndexOf(lineStarts: number[], pos: number): number {
  let lo = 0;
  let hi = lineStarts.length - 1;
  while (lo < hi) {
    const mid = (lo + hi + 1) >> 1;
    if (lineStarts[mid] <= pos) lo = mid;
    else hi = mid - 1;
  }
  return lo;
}

/**
 * 계산한 증감률 판정에 쓰는 한 줄의 재료 수 상한 (WD-781). 순서쌍 수가 재료 수의 제곱이라, 넓은 표 행처럼 한 줄에 수치가
 * 아주 많으면 판정을 하지 않고(경고는 남는다) 대조 시간이 늘지 않게 한다. 또 재료가 많을수록 지어낸 % 가 우연히 맞기 쉬워진다.
 */
const MAX_RATE_OPERANDS = 30;

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
 *
 * 출처 숫자는 리포트 표시 자릿수로 반올림해 맞추고(「1,234,567」 → 「123만」), 허용 텍스트 숫자는 같은 값일 때만 허용한다
 * (WD-783 — AllowedValues 주석 참고).
 */
export function checkReportNumbers(input: {
  reportTexts: string[];
  sourceNumbers: Set<number>;
  allowedTexts: string[];
}): NumberCheck {
  if (input.sourceNumbers.size === 0 || input.reportTexts.every((t) => !t.trim())) {
    return emptyNumberCheck();
  }
  // WD-775: 양식·프롬프트·컨텍스트의 숫자는 값으로 허용한다(「1234」 → 리포트 「1,234개」). 허용 텍스트는 JSON 이 아닌 날 글자라
  // JSON 지향인 collectSourceNumbers(따옴표 밖 「1,234」를 1·234 로 나눔) 대신 리포트와 같은 tokenize 로 값(표시값 × 단위 배수,
  // % 는 그 값)을 모은다. 위의 checked 판정은 sourceNumbers 만 본다.
  // WD-783: 허용 값은 반올림 색인(NumberIndex)에 넣지 않고 같은 값일 때만 허용한다 — datasetId 1024 가 「1천명」을 풀어 주지 않게.
  const allowedNumbers = new Set<number>();
  for (const t of input.allowedTexts) {
    for (const x of tokenize(t, false)) allowedNumbers.add(x.percent ? x.value : x.value * x.multiplier);
  }
  const index = new NumberIndex(input.sourceNumbers);
  const allowed = new AllowedValues(allowedNumbers);
  const seen = new Set<string>();
  const unverified: UnverifiedNumber[] = [];
  // WD-783 이후 허용 값은 반올림 색인이 아니라 AllowedValues(같은 값만)로 본다 — 증감률 재료 판정도 같은 기준을 쓴다
  const isVerified = (token: Token) => index.matches(token) || allowed.matches(token);
  for (const report of input.reportTexts) {
    // 계산한 증감률·비율 판정의 재료 — 줄마다 확인된 % 아닌 수치의 실제 값(value × multiplier) (WD-781).
    // 날짜·시각·분기만 가리고 접미사·작은 정수는 건너뛰지 않는다: 「28.8시간」처럼 대조 대상에서 빠지는 수치도 출처와 맞으면
    // 증감률의 재료가 되기 때문이다. 줄(표 한 행·목록 한 항목) 안에서만 짝을 짓고, 확인 안 된 % 가 나온 줄만 늦게 계산한다.
    const lineStarts = [0];
    for (let i = 0; i < report.length; i++) if (report[i] === '\n') lineStarts.push(i + 1);
    let candidatesByLine: Map<number, Token[]> | null = null;
    const operandsByLine = new Map<number, number[]>();
    const operandsOf = (line: number): number[] => {
      let values = operandsByLine.get(line);
      if (values) return values;
      if (!candidatesByLine) {
        candidatesByLine = new Map();
        for (const t of tokenize(maskNonNumbers(report), false)) {
          if (t.percent) continue;
          const l = lineIndexOf(lineStarts, t.start);
          const list = candidatesByLine.get(l) ?? [];
          list.push(t);
          candidatesByLine.set(l, list);
        }
      }
      const verified = (candidatesByLine.get(line) ?? []).filter(isVerified);
      values = verified.length > MAX_RATE_OPERANDS ? [] : verified.map((t) => t.value * t.multiplier);
      operandsByLine.set(line, values);
      return values;
    };
    for (const token of tokenize(report, true)) {
      if (seen.has(token.text) || isVerified(token)) continue;
      if (token.percent && matchesComputedRate(token, operandsOf(lineIndexOf(lineStarts, token.start)))) continue;
      seen.add(token.text);
      if (unverified.length < MAX_UNVERIFIED) {
        unverified.push({ text: token.text, context: contextOf(report, token) });
      }
    }
  }
  return { checked: true, unverified, unverifiedTotal: seen.size };
}

/**
 * tool_use ↔ tool_result 짝짓기 (WD-767). 병렬 호출이면 결과 순서가 tool_use 순서와 달라, id → 도구 이름 맵으로
 * 결과가 어느 도구의 것인지 정한다. WD-775: sdk·cli·opencode(callID 있을 때) 모두 tool_result 에 toolUseId 를 싣는다.
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
     * tool_result 가 속한 도구 이름.
     * - toolUseId 가 있으면 그 id 로만 찾는다 — 맵에 없으면 알 수 없는 호출이라 빈 문자열(출처로 치지 않음).
     * - 없으면 toolName 자리 값: 실제 도구 이름(opencode, callID 없음)이면 그대로, 맵의 id(toolUseId 를 싣기 전 모양)면
     *   그 도구. sdk 의 'unknown' 같은 그 밖의 값은 데이터 조회 도구 이름이 아니라 출처가 되지 않는다.
     * - 둘 다 없는 공급자일 때만 직전 tool_use 이름으로 판단한다(경고 전용이라 비데이터 결과가 섞여도 감수).
     */
    resolve(event: Record<string, unknown>): string {
      const explicitId = idOf(event);
      if (explicitId) return nameById.get(explicitId) ?? '';
      const nameField = String(event.toolName ?? '');
      return nameField ? (nameById.get(nameField) ?? nameField) : lastToolName;
    },
  };
}
