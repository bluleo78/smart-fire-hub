/**
 * 「beforeunload 리스너는 떠나기 차단 등록도 해야 한다」 규칙 고정 (WD-1122)
 *
 * client.ts 의 만료·사용자 바뀜 이동은 hasActiveLeaveGuard() 로 떠나기 확인이 걸릴 수 있는지 판단한다. beforeunload 리스너를
 * 직접 거는 파일이 registerLeaveBlocker / registerActiveGuard 에 같은 조건을 등록하지 않으면 그 확인 창을 모른 채
 * 같은 화면의 배경 요청마다 이동이 되풀이된다. 소스를 훑어 새 beforeunload 리스너가 등록 없이 생기는 것을 막는다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

const SRC = join(__dirname, '..');
/** 규칙을 면제할 파일(경로는 src 기준) — 이유를 함께 적는다. 현재 없음. */
const ALLOWLIST: Record<string, string> = {};

/**
 * 소스에서 `//`·`/* *\/` 주석을 걷는다 (WD-1142) — 주석에 적힌 `registerLeaveBlocker(` 같은 글자가 실제 등록으로 세어져
 * 등록 없이 beforeunload 를 거는 파일이 규칙을 빠져나가지 않게 한다. 문자열 리터럴('…'·"…"·`…`, 역슬래시 이스케이프)은
 * 그대로 복사하므로 `accept='image/*'`·`'http://x'` 가 주석 시작으로 오인되지 않는다.
 * 가벼운 스캐너다 — 정규식 리터럴·템플릿 `${}` 안의 중첩은 다루지 않는다(이 규칙 시험 전용).
 */
function stripComments(src: string): string {
  let out = '';
  let i = 0;
  while (i < src.length) {
    const ch = src[i];
    const next = src[i + 1];
    if (ch === "'" || ch === '"' || ch === '`') {
      let j = i + 1;
      while (j < src.length && src[j] !== ch) j += src[j] === '\\' ? 2 : 1;
      out += src.slice(i, j + 1);
      i = j + 1;
    } else if (ch === '/' && next === '/') {
      while (i < src.length && src[i] !== '\n') i++;
    } else if (ch === '/' && next === '*') {
      const end = src.indexOf('*/', i + 2);
      i = end < 0 ? src.length : end + 2;
    } else {
      out += ch;
      i++;
    }
  }
  return out;
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
    const withListener = files.filter((f) => listener.test(stripComments(readFileSync(f, 'utf8'))));
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

  it("문자열 속 '/*' 는 주석 시작이 아니다 — 뒤의 실제 beforeunload 줄이 남는다", () => {
    const src = "<input accept='image/*' />\nwindow.addEventListener('beforeunload', f);";
    expect(stripComments(src)).toContain("addEventListener('beforeunload'");
  });
});
