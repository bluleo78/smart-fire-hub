/**
 * size="sm" 버튼 높이 덮어쓰기 회귀 가드 (WD-1018)
 *
 * 무엇: src/ 전역의 *.tsx 를 TypeScript 파서로 읽어 `size="sm"` 인 `<Button>`·`<PendingButton>` 을
 *       찾고, 그 className 에 `h-7` 토큰이 있으면 `파일:줄` 을 실패 메시지에 적어 멈춘다.
 * 왜:   Button 의 size="sm" 은 32px(h-8) 인데 호출부가 `h-7` 을 덧붙이면 28px 로 줄어
 *       같은 sm 버튼끼리 높이가 어긋난다. 이 시험은 그중 실제로 퍼져 있던 `h-7` 하나만 막는다
 *       (h-6·h-5·size="icon" 덮어쓰기는 WD-1022 범위).
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
 * 금지 토큰 `h-7` — 앞에 단어 문자·하이픈이 붙으면(min-h-7·max-h-7) 다른 토큰이라 잡지 않고, 뒤에 이어지는 `h-70`·`h-7.5` 도 제외한다.
 * `hover:h-7`·`data-[state=open]:h-7` 처럼 버튼 자신의 상태 변형은 잡는다(앞이 `:` 라도 경계로 본다).
 */
const H7_TOKEN = /(?<![\w-])h-7(?![\w.-])/;

/**
 * `[&` 로 시작하는 자식 선택자 클래스(`[&_svg]:h-7` 등) — 버튼이 아니라 자식 요소의 높이라 검사 전에 지운다.
 * Tailwind 임의 선택자는 공백 대신 `_` 를 쓰므로 공백·따옴표·백틱까지가 한 클래스다.
 */
const CHILD_SELECTOR_CLASS = /\[&[^\s'"`]*/g;

/**
 * 가드 제외 파일(src 기준 상대 경로) — WD-960 이 다른 곳에서 진행 중이라 파일 통째로 뺀다.
 * WD-960 머지 뒤 지운다.
 */
const EXEMPT_FILES = new Set([
  'components/ai/SessionSwitcher.tsx',
  'pages/data/DatasetDetailPage.tsx',
  'pages/pipeline/components/ApiCallStepConfig.tsx',
  'pages/pipeline/components/ApiCallPreview.tsx',
]);

/** 대상 태그 — `Button` 과, size·className 을 그대로 넘기는 `PendingButton`·`PendingSubmitButton`. 감싼 컴포넌트가 늘면 여기에 더한다. */
const BUTTON_TAGS = new Set(['Button', 'PendingButton', 'PendingSubmitButton']);

function isButtonTag(tagName: ts.JsxTagNameExpression, source: ts.SourceFile): boolean {
  return BUTTON_TAGS.has(tagName.getText(source));
}

/** size 속성이 "sm" 인지 — `size="sm"` 과 `size={'sm'}`·`size={"sm"}` 두 표기를 모두 본다. */
function isSizeSm(attr: ts.JsxAttribute): boolean {
  const init = attr.initializer;
  if (!init) return false;
  if (ts.isStringLiteral(init)) return init.text === 'sm';
  if (ts.isJsxExpression(init) && init.expression && ts.isStringLiteralLike(init.expression)) {
    return init.expression.text === 'sm';
  }
  return false;
}

/** 요소의 속성을 훑어 size="sm" 여부와 className 초기화식 텍스트를 모은다. */
function inspect(
  node: ts.JsxOpeningElement | ts.JsxSelfClosingElement,
  source: ts.SourceFile
): { sm: boolean; classNameText: string } {
  let sm = false;
  const parts: string[] = [];
  for (const attr of node.attributes.properties) {
    if (!ts.isJsxAttribute(attr)) continue;
    const name = attr.name.getText(source);
    if (name === 'size' && isSizeSm(attr)) sm = true;
    if (name === 'className' && attr.initializer) parts.push(attr.initializer.getText(source));
  }
  return { sm, classNameText: parts.join(' ') };
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
      const { sm, classNameText } = inspect(node, source);
      if (sm && H7_TOKEN.test(classNameText.replace(CHILD_SELECTOR_CLASS, ''))) {
        const line = source.getLineAndCharacterOfPosition(node.getStart(source)).line + 1;
        violations.push(`${rel}:${line}`);
      }
    }
    ts.forEachChild(node, visit);
  };
  visit(source);

  return violations;
}

/** src/ 아래 *.tsx 를 훑는다 — ui 폴더·*.test.tsx·예외 파일은 뺀다. */
function scan(): string[] {
  return readdirSync(SRC, { recursive: true, encoding: 'utf8' })
    .map((rel) => rel.split('\\').join('/'))
    .filter(
      (rel) =>
        rel.endsWith('.tsx') &&
        !rel.endsWith('.test.tsx') &&
        !rel.startsWith('components/ui/') &&
        !EXEMPT_FILES.has(rel)
    )
    .sort()
    .flatMap((rel) => {
      const text = readFileSync(join(SRC, rel), 'utf8');
      // 대부분의 파일엔 h-7 이 없다 — 글자로 먼저 걸러 TSX 파싱을 건너뛴다
      return text.includes('h-7') ? scanText(text, rel) : [];
    });
}

describe('scanText 판정 (WD-1018)', () => {
  it('size="sm" 에 h-7 이 있으면 1건', () => {
    expect(scanText('<Button size="sm" className="h-7 text-xs">x</Button>', 'x.tsx')).toEqual([
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

  it('size="icon" 은 h-7 이 있어도 0건', () => {
    expect(scanText('<Button size="icon" className="h-7 w-7" />', 'x.tsx')).toEqual([]);
  });

  it('min-h-7·max-h-7·h-70·h-7.5 는 다른 토큰이라 0건', () => {
    expect(scanText('<Button size="sm" className="min-h-7 max-h-7 h-70 h-7.5" />', 'x.tsx')).toEqual([]);
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

  it('자식 선택자([&…]) 뒤 h-7(자식 요소 높이)은 0건', () => {
    expect(scanText('<Button size="sm" className="[&_svg]:h-7 [&>span]:h-7" />', 'x.tsx')).toEqual([]);
  });

  it('버튼 자신의 상태 변형(data-[state=open]:h-7·hover:h-7)은 1건씩', () => {
    expect(scanText('<Button size="sm" className="data-[state=open]:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
    expect(scanText('<Button size="sm" className="hover:h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });

  it('자식 선택자와 버튼 자신의 h-7 이 함께 있으면 1건', () => {
    expect(scanText('<Button size="sm" className="[&_svg]:h-7 h-7" />', 'x.tsx')).toEqual(['x.tsx:1']);
  });
});

describe('src 전역 (WD-1018)', () => {
  it('size="sm" 버튼 className 에 h-7 이 없다 (예외 4파일 제외)', () => {
    const violations = scan();
    expect(
      violations,
      `size="sm" 버튼의 h-7 덮어쓰기 ${violations.length}곳:\n` +
        `${violations.join('\n')}\n` +
        'h-7 을 지워라 — size="sm" 이 이미 32px 다 (WD-1018).'
    ).toEqual([]);
  });
});
