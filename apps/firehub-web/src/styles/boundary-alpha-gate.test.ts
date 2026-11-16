// @vitest-environment node
/**
 * 흐린 경계 금지 게이트 (WD-755)
 *
 * 무엇: `src/` 전역의 클래스 문자열을 훑어 **구조 경계에 붙은 알파 수식자**를 찾아내고,
 *       명시적 allowlist(파일·유틸·사유)에 없으면 실패시킨다.
 *       - 구조 테두리 알파: `border-border/50`·`border-b-border/40`·`divide-border/30`·
 *         `border-divider/NN`·`divide-divider/NN`·`border-muted-foreground/25`·`border-muted/NN` 등
 *       - 영역을 가르는 흐린 면: `bg-muted/NN`·`bg-secondary/NN`·`bg-accent/NN` (NN < 50)
 * 왜:   경계 단계(docs/design-system/01-design-tokens.md §1-7)는 바탕 3단(background < card < muted)과
 *       테두리 3단(divider < border < input)을 토큰 값으로 정하고 대비를 시험으로 고정한다.
 *       화면이 `/30`·`/50` 같은 알파로 단계를 직접 만들면 토큰을 아무리 진하게 해도
 *       카드·패널·목록 행이 다시 한 면에 붙어 보인다(WD-755 「원인 2」). 그래서 구조 경계에서는
 *       알파를 금지하고 역할 토큰(`border`·`divide-y`/`row-divider`·`bg-muted`)을 쓰게 한다.
 * 범위: 상태 접두(`hover:`·`focus:`·`data-[state=…]:`·`aria-selected:`·`group-hover:`·`peer-*:` 등)가
 *       붙은 유틸은 「경계」가 아니라 상태 표시라 대상이 아니다. `dark:`·`md:`·`even:`·`[&_tr]:` 처럼
 *       상태가 아닌 접두는 여전히 구조라서 잡는다.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 예외 — 구조 경계가 아니라 장식·내용 표현인 곳만. 새 항목은 반드시 사유를 함께 적는다.
 */
const ALLOWLIST: { file: string; util: string; reason: string }[] = [];

/** 구조 테두리 색 토큰에 붙은 알파 — 값과 상관없이 전부 금지(단계는 토큰이 정한다). */
const STRUCT_BORDER =
  /^(?:border(?:-[trblxyse])?|divide(?:-[xy])?)-(?:border|divider|muted|muted-foreground|input|sidebar-border)\/\d{1,3}$/;

/** 영역을 가르는 흐린 면 — 50% 미만이면 카드 위에서 머리 띠·패널이 갈라지지 않는다. */
const FAINT_SURFACE = /^bg-(?:muted|secondary|accent)\/(\d{1,3})$/;
const FAINT_SURFACE_MAX = 50;

/**
 * 상태 접두 — 이것이 하나라도 붙으면 상태 표시(hover·선택·열림 등)라 게이트 대상이 아니다.
 * `data-*`·`aria-*`·`group-*`·`peer-*`·`has-*` 는 모두 상태(또는 상태를 가진 조상) 조건이다.
 * 임의 변형(`[&…]:`)은 그 안에 상태 셀렉터(`:hover`·`[data-state`·`[aria-`)가 있을 때만 상태로 본다.
 */
const STATE_VARIANT =
  /^(?:hover|focus|focus-visible|focus-within|active|visited|target|disabled|enabled|checked|indeterminate|open|invalid|aria-.+|data-.+|group-.+|peer-.+|has-.+)$/;
const STATE_ARBITRARY = /^\[.*(?::hover|:focus|:active|\[data-state|\[aria-|data-\[|aria-\[).*\]$/;

/** `a:b:[c:d]:util` 을 대괄호 안의 `:` 는 건드리지 않고 나눈다. */
function splitVariants(token: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let cur = '';
  for (const ch of token) {
    if (ch === '[') depth++;
    if (ch === ']') depth--;
    if (ch === ':' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else cur += ch;
  }
  parts.push(cur);
  return parts;
}

/** 클래스 토큰 하나가 게이트 위반이면 그 유틸(접두 포함 원문)을, 아니면 null 을 돌려준다. */
function boundaryViolation(token: string): string | null {
  // 알파 수식자(`/NN`)가 없으면 게이트 대상이 아니다 — 변형 분해 전에 빨리 거른다
  if (!token.includes('/')) return null;
  const parts = splitVariants(token);
  const util = parts.pop()!.replace(/^!|!$/g, '');
  const isState = parts.some((v) => STATE_VARIANT.test(v) || STATE_ARBITRARY.test(v));
  if (isState) return null;
  if (STRUCT_BORDER.test(util)) return token;
  const m = util.match(FAINT_SURFACE);
  if (m && Number(m[1]) < FAINT_SURFACE_MAX) return token;
  return null;
}

/** 줄 하나에서 클래스 후보 토큰을 뽑는다(공백·따옴표·백틱·중괄호로 나눈다). */
function tokens(line: string): string[] {
  return line.split(/[\s'"`{}(),]+/).filter(Boolean);
}

/** 주석 줄은 렌더되지 않으므로 건너뛴다(주석에 옛 클래스명을 적어 설명하는 경우). */
const COMMENT_LINE = /^\s*(?:\/\/|\/\*|\*|\{\/\*)/;

/** src/ 아래 .ts·.tsx(시험 파일 제외)를 모은다 — Dirent 로 종류를 읽어 파일마다 stat 을 다시 부르지 않는다. */
function walk(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = join(dir, entry.name);
    if (entry.isDirectory()) walk(full, acc);
    else if (/\.tsx?$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name)) acc.push(full);
  }
  return acc;
}

describe('흐린 경계 금지 게이트 (WD-755)', () => {
  it('분류기가 구조 경계 알파는 잡고 상태 접두는 놓아준다(정규식 공허화 방지)', () => {
    const flagged = [
      'border-border/50',
      'border-b-border/40',
      'border-t-border/30',
      'border-x-border/50',
      'divide-border/50',
      'divide-y-border/30',
      'border-divider/60',
      'divide-divider/40',
      'border-muted-foreground/25',
      'border-muted/50',
      'bg-muted/30',
      'bg-muted/20',
      'bg-secondary/10',
      'bg-accent/40',
      'dark:bg-muted/30',
      'md:border-border/50',
      'even:bg-muted/20',
      '[&_tr]:border-border/50',
      '!bg-muted/30',
    ];
    const passed = [
      'border-border',
      'border',
      'border-divider',
      'bg-muted',
      'bg-muted/50',
      'bg-muted/60',
      'bg-secondary/80',
      'bg-primary/10',
      'border-primary/30',
      'border-warning/20',
      'hover:bg-muted/30',
      'hover:bg-muted/20',
      'focus:bg-accent/40',
      'focus-visible:border-border/50',
      'active:bg-muted/30',
      'group-hover:bg-muted/30',
      'peer-checked:bg-muted/20',
      'data-[state=open]:bg-muted/30',
      'data-[state=selected]:bg-muted/40',
      'aria-selected:bg-muted/30',
      'aria-[current=page]:bg-muted/30',
      'disabled:bg-muted/30',
      'dark:hover:bg-muted/30',
      '[a&]:hover:bg-secondary/40',
      '[&[data-state=open]]:bg-muted/30',
    ];
    for (const t of flagged) expect(boundaryViolation(t), t).toBe(t);
    for (const t of passed) expect(boundaryViolation(t), t).toBeNull();
  });

  it('구조 테두리·영역 구분 면에 알파 수식자가 없다 (allowlist 제외)', () => {
    const violations: string[] = [];
    for (const full of walk(SRC)) {
      const rel = relative(SRC, full).split('\\').join('/');
      readFileSync(full, 'utf8')
        .split('\n')
        .forEach((line, i) => {
          // 알파 수식자가 없는 줄과 주석 줄은 건너뛴다(대부분의 줄이라 토큰 분해를 아낀다)
          if (!line.includes('/') || COMMENT_LINE.test(line)) return;
          for (const tok of tokens(line)) {
            const hit = boundaryViolation(tok);
            if (!hit) continue;
            if (ALLOWLIST.some((a) => a.file === rel && a.util === hit)) continue;
            violations.push(`${rel}:${i + 1}  ${hit}`);
          }
        });
    }
    expect(violations, `흐린 구조 경계(${violations.length}건):\n${violations.join('\n')}`).toEqual([]);
  });

  it('allowlist 항목이 실제로 존재한다 (죽은 예외 방지)', () => {
    for (const { file, util } of ALLOWLIST) {
      const src = readFileSync(join(SRC, file), 'utf8');
      expect(src, `${file}에 ${util}가 더는 없다 — allowlist에서 지워라`).toContain(util);
    }
  });
});
