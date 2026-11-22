/**
 * SelectTrigger 높이 회귀 게이트 (WD-913)
 *
 * 무엇: src/ 전역의 *.tsx 를 훑어 `<SelectTrigger` 태그 블록(시작부터 최상위 `>`·`/>`) 안에서
 *       className 문자열·{...} 식 안의 `h-<숫자>` 높이 토큰을 찾고, 있으면 `파일:줄` 을
 *       실패 메시지에 적어 멈춘다.
 * 왜:   공용 select.tsx 는 size 생략 시 `data-[size=default]:h-9`(36px)를 속성 선택자로 붙인다 —
 *       속성 선택자는 일반 클래스(h-7·h-8)보다 특이도 가 높아 호출부 높이를 덮고,
 *       tailwind-merge 도 둘을 같은 묶음으로 보지 않아 지우지 못한다(WD-913).
 *       높이는 `size="sm"`(32px)으로만 정하는 규칙을 이 시험이 지킨다.
 *       (금지 대상은 components/ui/select.tsx 임 — 그 파일에는 `<SelectTrigger` 호출부가 없다.)
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** `<SelectTrigger` 태그 시작 — 경계 문자가 뒤에 오는 호출부만 잡는다(정의부 SelectTrigger 는 제외). */
const TAG_START = /<SelectTrigger\b/g;

/**
 * 높이 토큰 `h-<숫자>` — 앞에 단어 문자·하이픈이 붙으면 다른 토큰이다
 * (`max-h-40`·`min-h-8` 은 높이 토큰 h-40·h-8 이 아니라 자기 이름의 일부라 잡지 않는다).
 */
const HEIGHT_TOKEN = /(?<![\w-])h-\d+\b/;

/**
 * 태그 블록의 닫는 `>` 위치 — 문자열 리터럴과 중괄호 식({...}) 안은 태그 밖 괄호가 아니므로
 * 세지 않고(식 안의 `=>`·비교 `<`·중첩 태그가 여기 해당), 중괄호 밖 최상위 `>` 에서 멈춘다.
 * 닫히지 않으면 -1 — scanText 가 위반으로 기록해 통과시키지 않는다(fail-closed).
 */
function findTagEnd(text: string, start: number): number {
  let angle = 0;
  let brace = 0;
  let inStr: string | null = null;
  for (let i = start; i < text.length; i += 1) {
    const c = text[i];
    if (inStr) {
      if (c === '\\') {
        i += 1; // 이스케이프 다음 문자를 한 칸 건너뛴다(for 증가와 합쳐 두 칸 진행)
        continue;
      }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === "'" || c === '"' || c === '`') inStr = c;
    // 중괄호 식 안의 < > 는 식 내용(화살표·비교·중첩 태그)이라 태그 구조가 아니다 — 밖에서만 센다
    else if (c === '{') brace += 1;
    else if (c === '}') {
      if (brace > 0) brace -= 1;
    } else if (brace === 0 && c === '<') angle += 1;
    else if (brace === 0 && c === '>') {
      angle -= 1;
      if (angle === 0) return i;
    }
  }
  return -1;
}

/**
 * 태그 블록에서 className 값만 뽑는다 — 문자열 리터럴은 그대로, `{...}` 식은 중괄호 짝이 맞을 때까지
 * 이어 붙인다(삼항 연산자·cn(...) 조합의 높이 클래스도 놓치지 않기 위해).
 */
function classNamePayload(block: string): string {
  const parts: string[] = [];
  const re = /className\s*=\s*/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(block)) !== null) {
    const j = m.index + m[0].length;
    if (j < block.length && block[j] === '{') {
      let depth = 0;
      let k = j;
      for (; k < block.length; k += 1) {
        if (block[k] === '{') depth += 1;
        else if (block[k] === '}') {
          depth -= 1;
          if (depth === 0) break;
        }
      }
      parts.push(block.slice(j, k + 1));
    } else {
      const q = block[j];
      if (q === "'" || q === '"') {
        const end = block.indexOf(q, j + 1);
        parts.push(end === -1 ? block.slice(j) : block.slice(j, end + 1));
      }
    }
  }
  return parts.join(' ');
}

/** 파일 본문 하나를 훑어 높이 토큰 위반 `파일:줄` 목록을 돌려준다. */
function scanText(text: string, rel: string): string[] {
  const violations: string[] = [];
  TAG_START.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = TAG_START.exec(text)) !== null) {
    const line = text.slice(0, m.index).split('\n').length;
    const end = findTagEnd(text, m.index);
    if (end === -1) {
      // 닫힘을 못 찾으면 건너뛰면 그 자리 위반이 통과한다(fail-open) — 기록해 실패시킨다
      violations.push(`${rel}:${line} (닫는 > 를 찾지 못함)`);
      continue;
    }
    const block = text.slice(m.index, end + 1);
    if (!HEIGHT_TOKEN.test(classNamePayload(block))) continue;
    violations.push(`${rel}:${line}`);
  }
  return violations;
}

/** src/ 아래 *.tsx 를 전부 훑어 className 높이 토큰 위반 `파일:줄` 목록을 돌려준다. */
function scan(): string[] {
  const files: string[] = [];
  const walk = (dir: string): void => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name);
      if (statSync(full).isDirectory()) walk(full);
      else if (name.endsWith('.tsx')) files.push(full);
    }
  };
  walk(SRC);

  const violations: string[] = [];
  for (const full of files.sort()) {
    const rel = relative(SRC, full).split('\\').join('/');
    violations.push(...scanText(readFileSync(full, 'utf8'), rel));
  }
  return violations;
}

describe('SelectTrigger 높이 게이트 (WD-913)', () => {
  it('SelectTrigger 호출부 className 에 h-* 높이 토큰이 없다', () => {
    const violations = scan();
    expect(
      violations,
      `SelectTrigger 호출부 className 의 높이 토큰 위반 ${violations.length}곳:\n` +
        `${violations.join('\n')}\n` +
        '높이 토큰을 지우고 size="sm"(32px) 로 옮겨라 (WD-913).'
    ).toEqual([]);
  });
});

describe('태그 끝 파싱 회귀 — 중괄호 안 화살표·비교 (WD-913)', () => {
  it('속성 안 `=>` 에서 끊기지 않아 높이 위반을 잡는다', () => {
    expect(scanText('<SelectTrigger onClick={() => x} className="h-8">', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('중괄호 안 비교 `<` 로 닫힘이 사라져도 위반을 잡는다', () => {
    expect(scanText("<SelectTrigger className={a < b ? 'h-8' : 'x'}>", 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('닫는 `>` 를 못 찾으면 통과시키지 않고 위반으로 기록한다', () => {
    expect(scanText('<SelectTrigger className="h-8"', 'x.tsx')).toEqual(['x.tsx:1 (닫는 > 를 찾지 못함)']);
  });

  it('findTagEnd 는 태그 끝 `>` 를 돌려준다(식 안 `=>`·`<` 제외)', () => {
    const arrow = '<SelectTrigger onClick={() => x} className="h-8">';
    const cmp = "<SelectTrigger className={a < b ? 'h-8' : 'x'}>";
    expect(findTagEnd(arrow, 0)).toBe(arrow.length - 1);
    expect(findTagEnd(cmp, 0)).toBe(cmp.length - 1);
  });
});