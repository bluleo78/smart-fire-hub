/**
 * Tibero 프롬프트 검사용 테스트 픽스처(WD-79).
 *
 * <p>Tibero 테넌트에게 가는 프롬프트(메인·opencode·Proactive·서브에이전트)에 PostgreSQL 전용 문법이
 * 예시나 규칙으로 남아 있는지 줄 단위로 찾는다. 남아 있으면 모델이 그 문법을 그대로 따라 써서
 * Tibero 에서 SQL 이 실패한다(예: `'x'::VARCHAR` → JDBC-8006, `DATE_TRUNC(...)` → JDBC-8036).
 *
 * <p>"`ILIKE` 는 쓰지 않는다" 처럼 <b>쓰지 말라고 안내하는 문장</b>은 금지어를 일부러 담으므로
 * 검사에서 뺀다. 예외는 "쓰지 않는다"·"대신" 이 들어간 줄로만 좁게 둔다 — 넓히면 예시 코드 블록까지
 * 빠져 검사가 아무것도 잡지 못한다(같은 검사가 PG 프롬프트에서는 걸리는지 테스트로 확인한다).
 *
 * <p>파일명이 `*.test.ts` 가 아닌 이유: vitest 가 테스트 파일로 수집하면 "테스트 없음" 으로 실패한다.
 */

/** Tibero 프롬프트에 나오면 안 되는 PostgreSQL 전용 문법(태스크 브리프 고정 목록). */
export const PG_ONLY_PATTERNS: readonly RegExp[] = [
  /::[A-Za-z]/,
  /DATE_TRUNC\(/,
  /\bILIKE\b/,
  /\bLIMIT\s+\d/,
  /ST_DWithin/,
  /psycopg2/,
];

/** 금지 안내 문장 — 금지어를 일부러 담는 줄이라 검사에서 뺀다. */
const PROHIBITION_LINE = /쓰지 않는다|대신/;

/**
 * 텍스트에서 PG 전용 문법이 남은 줄을 찾아 "패턴 :: 줄" 목록으로 돌려준다(없으면 빈 배열).
 * 실패 메시지에 줄이 그대로 보여 어디를 고칠지 바로 알 수 있게 한다.
 */
export function findPgOnlySyntax(text: string): string[] {
  const hits: string[] = [];
  for (const line of text.split('\n')) {
    if (PROHIBITION_LINE.test(line)) continue;
    for (const re of PG_ONLY_PATTERNS) {
      if (re.test(line)) hits.push(`${re} :: ${line.trim()}`);
    }
  }
  return hits;
}

/**
 * Tibero 사본(`tibero/*.md`)이 원본에서 지운·바꾼 줄 가운데 "PG 전용 내용" 으로 볼 수 없는 줄을 돌려준다.
 *
 * <p>왜: Tibero 사본은 원본을 복사한 뒤 PG 전용 부분만 바꾸기로 했다. 원본 쪽 안전 가드(파괴 확인·PII·
 * 환각 금지 등)는 각 서브에이전트 safeguard 테스트가 원본 파일만 검사하므로, 사본에서 가드 문장이 빠져도
 * 아무 테스트도 실패하지 않는다. 그래서 "원본 줄은 PG 전용 표지(pgMarker)가 있거나 코드 블록 안이 아니면
 * 사본에 그대로 남아야 한다" 로 고정한다.
 */
export function removedNonPgLines(original: string, tibero: string, pgMarker: RegExp): string[] {
  const kept = new Set(tibero.split('\n'));
  const removed: string[] = [];
  let inFence = false;
  for (const line of original.split('\n')) {
    const fence = line.trimStart().startsWith('```');
    if (fence) inFence = !inFence;
    if (fence || inFence || line.trim() === '') continue;
    if (kept.has(line)) continue;
    if (pgMarker.test(line)) continue;
    removed.push(line);
  }
  return removed;
}

/** Tibero 프롬프트가 분석 SQL 핵심 규칙(스키마 한정·날짜 묶기·상위 N)을 담고 있는가. */
export function hasTiberoCoreRules(text: string): boolean {
  return text.includes('genia_t') && text.includes('TRUNC(') && /FETCH FIRST|ROWNUM/.test(text);
}
