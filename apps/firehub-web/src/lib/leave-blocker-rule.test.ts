/**
 * 「beforeunload 리스너는 떠나기 차단 등록도 해야 한다」 규칙 고정 (WD-1122)
 *
 * client.ts 의 만료·사용자 바뀜 이동은 hasActiveLeaveGuard() 로 떠나기 확인이 걸릴 수 있는지 판단한다. beforeunload 리스너를
 * 직접 거는 파일이 registerLeaveBlocker / registerActiveGuard 에 같은 조건을 등록하지 않으면 그 확인 창을 모른 채
 * 같은 화면의 배경 요청마다 이동이 되풀이된다. 소스를 훑어 새 beforeunload 리스너가 등록 없이 생기는 것을 막는다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..');
/** 규칙을 면제할 파일(경로는 src 기준) — 이유를 함께 적는다. 현재 없음. */
const ALLOWLIST: Record<string, string> = {};

/**
 * 소스에서 `//`·`/* *\/` 주석을 걷는다 (WD-1142, WD-1160) — 주석에 적힌 `registerLeaveBlocker(` 같은 글자가 실제 등록으로 세어져
 * 등록 없이 beforeunload 를 거는 파일이 규칙을 빠져나가지 않게 한다.
 * 문자 단위 스캐너도, 문맥 없는 토큰 스캐너도 정규식 리터럴·JSX 글(`<p>image/*</p>`, `don't`)·템플릿 `${}` 에서 경계를 잘못 잡는다.
 * 그래서 파서(TSX)가 만든 트리의 모든 토큰 앞 여백에서 주석 범위(앞·뒤)를 모아 그 범위만 지운다 —
 * JSX 글은 주석 후보에서 빼므로 그 안의 `/*` 는 주석이 아니다.
 */
function stripComments(src: string): string {
  const sf = ts.createSourceFile('x.tsx', src, ts.ScriptTarget.Latest, true, ts.ScriptKind.TSX);
  const ranges = new Map<number, number>();
  const collect = (node: ts.Node) => {
    // JSDoc 노드는 주석 자체라 그 안은 훑지 않는다 — 주석 범위는 이를 가진 토큰의 앞 여백에서 이미 잡힌다
    if (node.kind >= ts.SyntaxKind.FirstJSDocNode && node.kind <= ts.SyntaxKind.LastJSDocNode) return;
    const children = node.getChildren(sf);
    if (children.length === 0) {
      // JSX 글에는 여백이 없다 — 글 속 `/*`·`//` 를 주석으로 읽지 않게 건너뛴다
      if (node.kind === ts.SyntaxKind.JsxText) return;
      const pos = node.getFullStart();
      for (const r of ts.getLeadingCommentRanges(src, pos) ?? []) ranges.set(r.pos, r.end);
      for (const r of ts.getTrailingCommentRanges(src, pos) ?? []) ranges.set(r.pos, r.end);
      return;
    }
    children.forEach(collect);
  };
  collect(sf);
  let out = '';
  let last = 0;
  for (const start of [...ranges.keys()].sort((x, y) => x - y)) {
    if (start < last) continue;
    out += src.slice(last, start);
    last = ranges.get(start)!;
  }
  return out + src.slice(last);
}

function collect(dir: string, out: string[]) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) collect(full, out);
    else if (/\.(ts|tsx)$/.test(name) && !/\.test\.(ts|tsx)$/.test(name)) out.push(full);
  }
}

describe('beforeunload 리스너는 떠나기 차단 등록을 함께 한다 (WD-1122)', () => {
  it('addEventListener(beforeunload)/onbeforeunload/useBeforeUnload 를 쓰는 파일은 registerLeaveBlocker 또는 registerActiveGuard 를 부른다', () => {
    const files: string[] = [];
    collect(SRC, files);
    const listener = /addEventListener\(\s*['"`]beforeunload['"`]|onbeforeunload\s*=|useBeforeUnload\(/;
    const registers = /registerLeaveBlocker\(|registerActiveGuard\(/;
    // 파서(stripComments)는 비싸니, 규칙이 찾는 글자(beforeunload·useBeforeUnload)가 아예 없는 파일은 건너뛰고 일치한 파일에만 돌린다.
    const mightListen = (text: string) => /beforeunload|BeforeUnload/.test(text);
    const withListener = files.filter((f) => {
      const text = readFileSync(f, 'utf8');
      return mightListen(text) && listener.test(stripComments(text));
    });
    // 규칙이 아무것도 검사하지 않는 빈 시험이 되지 않게 — 현재 알려진 리스너(useUnsavedChangesGuard·useAIChat)가 잡혀야 한다
    expect(withListener.length).toBeGreaterThan(0);
    const offenders = withListener
      .map((f) => f.slice(SRC.length + 1))
      .filter((rel) => !ALLOWLIST[rel])
      .filter((rel) => !registers.test(stripComments(readFileSync(join(SRC, rel), 'utf8'))));
    expect(offenders).toEqual([]);
  });
});

describe('stripComments (WD-1142)', () => {
  const registers = /registerLeaveBlocker\(|registerActiveGuard\(/;

  it('주석 속 registerLeaveBlocker( 만 있는 소스는 등록 없음으로 본다', () => {
    expect(registers.test(stripComments('// registerLeaveBlocker(fn)\nconst a = 1;'))).toBe(false);
    expect(registers.test(stripComments('/* registerActiveGuard(x) */\nconst a = 1;'))).toBe(false);
    expect(registers.test(stripComments('/**\n * registerLeaveBlocker(\n */'))).toBe(false);
  });

  it('실제 호출은 그대로 남기고 URL 의 // 는 건드리지 않는다', () => {
    expect(registers.test(stripComments('registerLeaveBlocker(() => true); // 설명'))).toBe(true);
    expect(stripComments("const u = 'http://x'; // c")).toContain("'http://x';");
  });

  it('정규식 리터럴·JSX 글·템플릿 ${} 안의 따옴표/슬래시에 속지 않고 뒤의 코드를 남긴다', () => {
    const tail = "window.addEventListener('beforeunload', f);";
    expect(stripComments("const r = /\\/\\/.*'/;\n" + tail)).toContain(tail);
    expect(stripComments('const e = <p>don\'t</p>;\n' + tail)).toContain(tail);
    expect(stripComments('const t = `${\'//\'}`;\n' + tail)).toContain(tail);
    expect(stripComments('const t = `${\'//\'}`;\n' + tail)).toContain("'//'");
  });

  it('진짜 // · /* */ 주석은 정규식 리터럴 뒤에서도 걷는다', () => {
    const out = stripComments("const r = /a'/; // registerLeaveBlocker(\n/* registerActiveGuard( */ const b = 1;");
    expect(registers.test(out)).toBe(false);
    expect(out).toContain('const b = 1;');
    const jsx = stripComments("const e = <p>don't</p>;\n// registerLeaveBlocker(\nconst c = 2;");
    expect(registers.test(jsx)).toBe(false);
    expect(jsx).toContain('const c = 2;');
  });

  it('JSX 글 속 /* 는 주석이 아니어서 뒤의 실제 beforeunload 가 남고, 뒤의 진짜 주석은 걷힌다', () => {
    const out = stripComments("const e = <p>image/*</p>;\nwindow.addEventListener('beforeunload', f);\n/* end */");
    expect(out).toContain("addEventListener('beforeunload'");
    expect(out).not.toContain('end');
  });

  it("JSX 글의 아포스트로피 뒤 같은 줄 // 주석도 걷는다", () => {
    expect(registers.test(stripComments("const e = <p>don't</p>; // registerLeaveBlocker("))).toBe(false);
  });

  it('닫는 태그 뒤 같은 줄 주석을 걷는다', () => {
    const out = stripComments('const e = <div>x</div> // c\nconst d = 1;');
    expect(out).not.toContain('// c');
    expect(out).toContain('const d = 1;');
  });

  it("문자열 속 '/*' 는 주석 시작이 아니다 — 뒤의 실제 beforeunload 줄이 남는다", () => {
    const src = "<input accept='image/*' />\nwindow.addEventListener('beforeunload', f);";
    expect(stripComments(src)).toContain("addEventListener('beforeunload'");
  });
});
