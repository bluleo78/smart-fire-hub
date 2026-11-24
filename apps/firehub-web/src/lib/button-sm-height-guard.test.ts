/**
 * size="sm"·size="icon" 버튼 높이 덮어쓰기 회귀 가드 (WD-1018 → WD-1022)
 *
 * 무엇: src/ 전역의 *.tsx 를 TypeScript 파서로 읽어 `size="sm"`·`size="icon"` 인 `<Button>`·`<PendingButton>`·
 *       `<PendingSubmitButton>` 을 찾고, 그 className 에 `h-5`·`h-6`·`h-7` 토큰이 있으면 `파일:줄` 을
 *       실패 메시지에 적어 멈춘다.
 * 왜:   Button 의 `size="sm"` 은 32px(h-8), `size="icon"` 은 36px(size-9) 인데 호출부가 높이 토큰을
 *       덧붙이면 28px·24px·20px 로 줄어 같은 버튼끼리 높이가 어긋나고 32px 최소 클릭 영역도 깨진다.
 *       WD-1018 은 실제로 퍼져 있던 `size="sm"`+`h-7` 하나만 막았고, WD-1022 에서 sm+h-5·h-6 과
 *       icon+h-5·h-6·h-7 까지 넓혔다. `size="icon-sm"` 은 같은 32px 를 주는 별도 변형이라
 *       size 값 완전 일치로만 가려 잡지 않는다.
 *       (src/components/ui/ 와 *.test.tsx 는 훑지 않는다 — 정의부·시험 예시는 대상이 아니다.)
 *
 * 왜 파서로 읽는가: 주석·문자열·정규식 안의 예시 태그를 위반으로 오인하거나, 여러 줄 className 식을
 *       놓치지 않도록 `ts.createSourceFile` 로 읽는다 (select-trigger-height-guard.test.ts 와 같은 방식).
 *
 * 한계: 이름이 `Button`·`PendingButton`·`PendingSubmitButton` 인 태그의 리터럴 size·className 만 본다 — 별칭·감싼 컴포넌트,
 *       `{...props}` 로 넘긴 값, `size={cond ? 'sm' : 'default'}` 같은 조건식 size, `buttonVariants({ size: 'sm' })` 직접 호출,
 *       *.jsx 는 잡지 않는다(지금은 없다).
 */
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 금지 높이 토큰 `h-5`·`h-6`·`h-7` — 앞에 단어 문자·하이픈이 붙으면(min-h-6·max-h-7) 다른 토큰이라 잡지 않고,
 * 뒤에 이어지는 `h-50`·`h-65`·`h-7.5` 도 제외한다. (WD-1018 `H7_TOKEN` 을 WD-1022 에서 h-5·h-6 까지 넓혔다 — 경계 규칙은 그대로.)
 * `hover:h-6`·`data-[state=open]:h-7` 처럼 버튼 자신의 상태 변형은 잡는다(앞이 `:` 라라도 경계로 본다).
 */
const HEIGHT_TOKEN = /(?<![\w-])h-(?:5|6|7)(?![\w.-])/;

/**
 * 다른 요소를 가리키는 임의 선택자 클래스 — `[&_svg]:h-7`(자손)·`[&>span]:h-7`(자식)·`[&~*]`·`[&+*]`(형제).
 * 버튼이 아니라 그 요소의 높이라 검사 전에 지운다. `[&]:h-7`·`[&:hover]:h-7` 은 버튼 자신이라 남긴다(WD-1018 리뷰 후속).
 * Tailwind 임의 선택자는 공백 대신 `_` 를 쓰므로 공백·따옴표·백틱까지가 한 클래스다.
 */
const OTHER_ELEMENT_SELECTOR_CLASS = /\[&[_>~+][^\s'"`]*/g;

/** 대상 태그 — `Button` 과, size·className 을 그대로 넘기는 `PendingButton`·`PendingSubmitButton`. 감싼 컴포넌트가 늘면 여기에 더한다. */
const BUTTON_TAGS = new Set(['Button', 'PendingButton', 'PendingSubmitButton']);

function isButtonTag(tagName: ts.JsxTagNameExpression, source: ts.SourceFile): boolean {
  return BUTTON_TAGS.has(tagName.getText(source));
}

/**
 * size 속성이 기대 값과 완전 일치하는지 — `size="sm"` 과 `size={'sm'}`·`size={"sm"}` 두 표기를 모두 본다.
 * 완전 일치만 쓰는 이유: `size="icon-sm"` 은 32px 별도 변형이라 `icon` 으로 부분 일치해 잡히면 안 된다(WD-1022 리뷰 포커스).
 */
function isSize(attr: ts.JsxAttribute, expected: string): boolean {
  const init = attr.initializer;
  if (!init) return false;
  if (ts.isStringLiteral(init)) return init.text === expected;
  if (ts.isJsxExpression(init) && init.expression && ts.isStringLiteralLike(init.expression)) {
    return init.expression.text === expected;
  }
  return false;
}

/** 요소의 속성을 훑어 size="sm"·size="icon" 여부와 className 초기화식 텍스트를 모은다. */
function inspect(
  node: ts.JsxOpeningElement | ts.JsxSelfClosingElement,
  source: ts.SourceFile
): { sm: boolean; icon: boolean; classNameText: string } {
  let sm = false;
  let icon = false;
  const parts: string[] = [];
  for (const attr of node.attributes.properties) {
    if (!ts.isJsxAttribute(attr)) continue;
    const name = attr.name.getText(source);
    if (name === 'size') {
      if (isSize(attr, 'sm')) sm = true;
      if (isSize(attr, 'icon')) icon = true;
    }
    if (name === 'className' && attr.initializer) parts.push(attr.initializer.getText(source));
  }
  return { sm, icon, classNameText: parts.join(' ') };
}

/** 파일 본문 하나를 파싱해 위반 `파일:줄` 목록을 돌려준다. */
function scanText(text: string, rel: string): string[] {
  const source = ts.createSourceFile(rel, text, ts.ScriptTarget.Latest, false, ts.ScriptKind.TSX);
  const violations: string[] = [];

  const visit = (node: ts.Node): void => {
    if (
      (ts.isJsxOpeningElement(node) || ts.isJsxSelfClosingElement(node)) &&
      isButtonTag(node.tagName, source)
    ) {
      const { sm, icon, classNameText } = inspect(node, source);
      const cls = classNameText.replace(OTHER_ELEMENT_SELECTOR_CLASS, '');
      // size="sm": h-5·h-6·h-7 금지(WD-1018 h-7 → WD-1022 에 h-5·h-6 추가)
      // size="icon": h-5·h-6·h-7 금지(WD-1022 신규 — icon-sm 은 완전 일치 판별이라 여기 안 잡힘)
      if ((sm || icon) && HEIGHT_TOKEN.test(cls)) {
        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
        violations.push(`${rel}:${line}`);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

  return violations;
}

/** src/ 아래 *.tsx 를 훑는다 — ui 폴더·*.test.tsx 는 뺀다. */
function scan(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .map((rel) => rel.split('\\').join('/'))
    .filter(
      (rel) =>
        rel.endsWith('.tsx') &&
        !rel.endsWith('.test.tsx') &&
        !rel.startsWith('components/ui/')
    )
    .sort()
    .flatMap((rel) => {
      const text = readFileSync(join(SRC, rel), 'utf8');
      // 대부분의 파일엔 금지 높이 토큰이 없다 — 글자로 먼저 걸러 TSX 파싱을 건너뛴다
      return HEIGHT_TOKEN.test(text) ? scanText(text, rel) : [];
    });
}

describe('scanText 판정 (WD-1018·WD-1022)', () => {
  it('size="sm" 에 h-7 이 있으면 1건', () => {
    expect(scanText('<Button size="sm" className="h-7 text-xs">x</Button>', 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
  });

  it('size="sm" 에 h-6·h-5 도 1건씩 (WD-1022)', () => {
    expect(scanText('<Button size="sm" className="h-6 text-xs">x</Button>', 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
    expect(scanText('<Button size="sm" className="h-5 text-xs">x</Button>', 'x.tsx')).toEqual([
      'x.tsx:1',
    ]);
  });

  it("size={'sm'} 표기도 1건", () => {
    expect(scanText(`<Button size={'sm'} className="h-7" />`, 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('여러 줄 className 식 안의 h-7 도 1건(시작 줄 기준)', () => {
    const text = [
      '<Button',
      '  size="sm"',
      "  className={cn('px-2', open && 'h-7')}",
      '/>',
    ].join('\n');
    expect(scanText(text, 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('size="icon" 에 h-7 이 있으면 1건 — WD-1022 전까지 0건 이던 것을 위반으로 잡는다', () => {
    expect(scanText('<Button size="icon" className="h-7 w-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('size="icon" 에 h-6·h-5 도 1건씩 (WD-1022 신규)', () => {
    expect(scanText('<Button size="icon" className="h-6 w-6" />', 'x.tsx')).toEqual(['x.tsx:1']);
    expect(scanText('<Button size="icon" className="h-5 w-5" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('size="icon-sm" 은 h-5·h-6·h-7 이 있어도 0건 — size 값 완전 일치 (WD-1022)', () => {
    expect(scanText('<Button size="icon-sm" className="h-5 w-5" />', 'x.tsx')).toEqual([]);
    expect(scanText('<Button size="icon-sm" className="h-6 w-6" />', 'x.tsx')).toEqual([]);
    expect(scanText('<Button size="icon-sm" className="h-7 w-7" />', 'x.tsx')).toEqual([]);
  });

  it('min-h-·max-h-·h-50·h-65·h-70·h-6.5·h-7.5 는 다른 토큰이라 0건', () => {
    expect(
      scanText(
        '<Button size="sm" className="min-h-5 max-h-6 min-h-7 max-h-7 h-50 h-65 h-70 h-6.5 h-7.5" />',
        'x.tsx'
      )
    ).toEqual([]);
  });

  it('JSX 주석 안 예시는 0건', () => {
    expect(scanText('<div>{/* <Button size="sm" className="h-7" /> */}</div>', 'x.tsx')).toEqual(
      []
    );
  });

  it('PendingButton 도 1건', () => {
    expect(scanText('<PendingButton size="sm" className="h-7">x</PendingButton>', 'x.tsx')).toEqual(
      ['x.tsx:1']
    );
  });

  it('PendingSubmitButton 도 1건 — size·className 을 PendingButton 에 그대로 넘긴다', () => {
    expect(
      scanText('<PendingSubmitButton size="sm" className="h-7">x</PendingSubmitButton>', 'x.tsx')
    ).toEqual(['x.tsx:1']);
  });

  it('다른 요소를 가리키는 선택자([&_…]·[&>…]·[&~…]·[&+…]) 뒤 h-6·h-7 은 0건', () => {
    expect(
      scanText('<Button size="sm" className="[&_svg]:h-7 [&>span]:h-7 [&~*]:h-7 [&+*]:h-7" />', 'x.tsx')
    ).toEqual([]);
    expect(
      scanText('<Button size="icon" className="[&_svg]:h-6 [&>span]:h-5" />', 'x.tsx')
    ).toEqual([]);
  });

  it('버튼 자신을 가리키는 [&]:h-7·[&:hover]:h-7 은 1건씩 (리뷰 후속)', () => {
    expect(scanText('<Button size="sm" className="[&]:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
    expect(scanText('<Button size="sm" className="[&:hover]:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('버튼 자신의 상태 변형(data-[state=open]:h-7·hover:h-7)은 1건씩', () => {
    expect(scanText('<Button size="sm" className="data-[state=open]:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
    expect(scanText('<Button size="sm" className="hover:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('자식 선택자와 버튼 자신의 h-7 이 함께 있으면 1건', () => {
    expect(scanText('<Button size="sm" className="[&_svg]:h-7 h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });
});

describe('src 전역 (WD-1018·WD-1022)', () => {
  it('size="sm"·size="icon" 버튼 className 에 h-5·h-6·h-7 덮어쓰기가 없다', () => {
    const violations = scan();
    expect(
      violations,
      `size="sm"·size="icon" 버튼의 높이 덮어쓰기 ${violations.length}곳:\n` +
        `${violations.join('\n')}\n` +
        '높이 토큰을 지워라 — size="sm" 은 이미 32px 이고, size="icon" 자리는 size="icon-sm"(32px) 으로 갈아 끼운다 (WD-1018·WD-1022).'
    ).toEqual([]);
  });
});
