/**
 * SelectTrigger 높이 회귀 게이트 (WD-913)
 *
 * 무엇: src/ 전역의 *.tsx 를 TypeScript 파서로 읽어 `<SelectTrigger` 호출 요소를 찾고,
 *       그 className 안에서 `h-<숫자>` 높이 토큰이 나오면 `파일:줄` 을 실패 메시지에 적어 멈춘다.
 * 왜:   공용 select.tsx 는 size 생략 시 `data-[size=default]:h-9`(36px)를 속성 선택자로 붙인다 —
 *       속성 선택자는 일반 클래스(h-7·h-8)보다 특이도 가 높아 호출부 높이를 덮고,
 *       tailwind-merge 도 둘을 같은 묶음으로 보지 않아 지우지 못한다(WD-913).
 *       높이는 `size="sm"`(32px)으로만 정하는 규칙을 이 시험이 지킨다.
 *       (금지 대상은 components/ui/select.tsx 임 — 그 파일에는 `<SelectTrigger` 호출부가 없다.)
 *
 * 왜 파서로 읽는가(리뷰 후속 — 앞 PR #1173): 앞 판은 손으로 짠 렉서로 닫는 `>` 를 찾았는데,
 *       주석·정규식 안의 따옴표(예: don't 이 든 JSX 주석, 문자 패턴 정규식)를 문자열 시작으로
 *       읽어 올바른 코드에서도 「닫는 > 를 찾지 못함」 으로 실패하거나, 주석에 적힌 예시 태그를
 *       위반으로 오인할 수 있었다. `ts.createSourceFile` 로 읽으면 주석·문자열·정규식을 파서가
 *       이미 갈라 놓아 그런 오판이 없다. 회복하지 못한 태그는 타입 검사(`pnpm typecheck`)가 먼저 막는다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 높이 토큰 `h-<숫자>` — 앞에 단어 문자·하이픈이 붙으면 다른 토큰이다
 * (`max-h-40`·`min-h-8` 은 높이 토큰 h-40·h-8 이 아니라 자기 이름의 일부라 잡지 않는다).
 */
const HEIGHT_TOKEN = /(?<![\w-])h-\d+\b/;

/**
 * 태그 이름이 순수 `SelectTrigger` 인지 — `UI.SelectTrigger` 처럼 경로가 붙은 이름은
 * 원래 정규식(`<SelectTrigger`)과 같이 잡지 않는다(정의부와의 구분은 파서가 해 준다).
 */
function isSelectTrigger(tagName: ts.JsxTagNameExpression, source: ts.SourceFile): boolean {
  return tagName.getText(source) === 'SelectTrigger';
}

/**
 * 호출 요소의 className 값만 모은다 — 문자열 리터럴과 `{...}` 식을 통째로 텍스트로 얹는다.
 * 따옴표·주석·정규식을 손으로 자르지 않아도 되는 것이 파서를 쓰는 이점이다
 * (식 안의 `=>`·비교 `<`·중첩 태그·주석도 여기에 그대로 들어온다).
 */
function classNamePayload(
  node: ts.JsxOpeningElement | ts.JsxSelfClosingElement,
  source: ts.SourceFile
): string {
  const parts: string[] = [];
  for (const attr of node.attributes.properties) {
    if (!ts.isJsxAttribute(attr) || attr.name.getText(source) !== 'className') continue;
    if (!attr.initializer) continue; // 값 없는 `className` — 높이 토큰을 쓸 수 없다
    parts.push(attr.initializer.getText(source));
  }
  return parts.join(' ');
}

/** 파일 본문 하나를 파싱해 높이 토큰 위반 `파일:줄` 목록을 돌려준다. */
function scanText(text: string, rel: string): string[] {
  const source = ts.createSourceFile(rel, text, ts.ScriptTarget.Latest, false, ts.ScriptKind.TSX);
  const violations: string[] = [];

  const visit = (node: ts.Node): void => {
    if (
      (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) &&
      isSelectTrigger(node.tagName, source)
    ) {
      const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
      if (HEIGHT_TOKEN.test(classNamePayload(node, source))) violations.push(`${rel}:${line}`);
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

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

describe('태그 파싱 회귀 — 화살표·비교·주석·정규식 (WD-913)', () => {
  it('속성 안 `=>` 에서 끊기지 않아 높이 위반을 잡는다', () => {
    expect(scanText('<SelectTrigger onClick={() => x} className="h-8" />', 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
  });

  it('중괄호 안 비교 `<` 로 닫힘이 사라져도 위반을 잡는다', () => {
    expect(scanText("<SelectTrigger className={a < b ? 'h-8' : 'x'} />", 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
  });

  it('닫는 `>` 없이 끝나는 태그도 통과시키지 않고 위반으로 기록한다', () => {
    expect(scanText('<SelectTrigger className="h-8"', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('속성 식 안 주석의 따옴표를 문자열 시작으로 오독하지 않는다', () => {
    // 주석 안 apostrophe 를 문자열 시작으로 읽으면 닫는 > 를 못 찾는 오판이 났다(리뷰 후속).
    expect(
      scanText(`<SelectTrigger className={cn('a', /* don't touch */ cond && 'b')} />`, 'x.tsx')
    ).toEqual([]);
  });

  it('속성 식 안 정규식의 따옴표를 문자열 시작으로 오독하지 않는다', () => {
    expect(scanText(`<SelectTrigger className={/['"]/.test(v) ? 'a' : 'b'} />`, 'x.tsx')).toEqual(
      []
    );
  });

  it('주석을 지나가도 그 뒤의 높이 위반은 잡는다', () => {
    expect(scanText(`<SelectTrigger className={cn('a', /* don't */ 'h-8')} />`, 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
  });

  it('주석에 적힌 예시 태그는 위반으로 세지 않는다', () => {
    expect(scanText(`// 예: <SelectTrigger className="h-8">`, 'x.tsx')).toEqual([]);
  });
});