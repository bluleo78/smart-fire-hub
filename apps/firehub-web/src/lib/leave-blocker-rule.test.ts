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
    const withListener = files.filter((f) => listener.test(readFileSync(f, 'utf8')));
    // 규칙이 아무것도 검사하지 않는 빈 시험이 되지 않게 — 현재 알려진 리스너(useUnsavedChangesGuard·useAIChat)가 잡혀야 한다
    expect(withListener.length).toBeGreaterThan(0);
    const offenders = withListener
      .map((f) => f.slice(SRC.length + 1))
      .filter((rel) => !ALLOWLIST[rel])
      .filter((rel) => !registers.test(readFileSync(join(SRC, rel), 'utf8')));
    expect(offenders).toEqual([]);
  });
});
