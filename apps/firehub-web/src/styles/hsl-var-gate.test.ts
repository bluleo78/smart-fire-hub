/**
 * `hsl(var(--X))` 래핑 금지 게이트 (#374)
 *
 * 무엇: `src/` 전역에서 색 토큰을 `hsl(var(--X))`로 감싼 지점을 찾아 실패시킨다.
 * 왜:   이 앱의 디자인 토큰은 shadcn 초기 세대의 HSL **성분값**(`0 0% 45%`)이 아니라
 *       완결된 `oklch()` **색 함수**다(`--muted-foreground: oklch(0.5 0 0)`).
 *       따라서 `hsl(var(--muted-foreground))`는 `hsl(oklch(...))`라는 무효 CSS가 되고,
 *       브라우저가 선언을 통째로 버려 initial 값으로 되돌린다 —
 *       `fill`은 `rgb(0,0,0)`, `stroke`는 `none`.
 *
 *       실제 피해(#374): 다크 모드 차트 축 라벨 대비가 1.06~1.10:1까지 떨어졌고(SC 1.4.3 요구 4.5:1),
 *       `격자 표시` 토글이 라이트·다크 공통으로 무동작했다(격자 `stroke: none`).
 *       토큰이 이미 완결된 색 함수이므로 래핑 없이 `var(--X)`를 그대로 쓰면 된다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 예외 — 비어 있어야 정상이다.
 * 토큰이 전부 `oklch()` 완결형인 한 `hsl(var(--X))`가 정당한 경우는 존재하지 않는다.
 * 훗날 HSL **성분값** 규약 토큰을 도입한다면 그 토큰명과 사유를 여기 적는다.
 */
const ALLOWLIST: { file: string; token: string; reason: string }[] = [];

/** `hsl(var(--foo))` / `hsla(var(--foo))` — 공백 변형까지 포함 */
const HSL_VAR = /hsla?\(\s*var\(\s*(--[a-zA-Z0-9-]+)\s*\)/g;

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (/\.(tsx?|css)$/.test(name) && !/\.test\.tsx?$/.test(name)) acc.push(full);
  }
  return acc;
}

describe('hsl(var(--X)) 래핑 금지 게이트', () => {
  it('색 토큰을 hsl()로 감싼 지점이 없다 (allowlist 제외)', () => {
    const violations: string[] = [];

    for (const full of walk(SRC)) {
      const rel = relative(SRC, full).split('\\').join('/');
      readFileSync(full, 'utf8')
        .split('\n')
        .forEach((line, i) => {
          for (const m of line.matchAll(HSL_VAR)) {
            const token = m[1];
            if (ALLOWLIST.some((a) => a.file === rel && a.token === token)) continue;
            violations.push(`${rel}:${i + 1}  hsl(var(${token}))`);
          }
        });
    }

    expect(
      violations,
      `토큰이 oklch() 완결형이므로 hsl() 래핑은 무효 CSS다 — var(--X)로 바꿔라:\n${violations.join('\n')}`
    ).toEqual([]);
  });

  /**
   * 게이트가 공허해지지 않도록 전제 자체를 단언한다 —
   * 토큰이 여전히 `oklch()` 완결형이어야 위 규칙이 의미를 갖는다.
   * (성분값 규약으로 갈아탄다면 이 테스트가 먼저 빨개져서 게이트를 재검토하게 만든다.)
   */
  it('색 토큰이 oklch() 완결형이다 (게이트의 전제)', () => {
    const css = readFileSync(join(SRC, 'index.css'), 'utf8');
    const probes = ['--muted-foreground', '--border', '--popover', '--foreground'];
    for (const token of probes) {
      const m = css.match(new RegExp(`^\\s*${token}:\\s*([^;]+);`, 'm'));
      expect(m, `${token} 선언을 index.css에서 찾지 못했다`).not.toBeNull();
      expect(m![1].trim(), `${token}가 더는 oklch() 완결형이 아니다 — 게이트 전제 재검토`).toMatch(
        /^oklch\(/
      );
    }
    // index.css 어디에도 HSL 성분값 규약 토큰이 없어야 한다
    expect(css).not.toMatch(/hsl\(\s*var\(/);
  });

  it('allowlist 항목이 실제로 존재한다 (죽은 예외 방지)', () => {
    for (const { file, token } of ALLOWLIST) {
      const src = readFileSync(join(SRC, file), 'utf8');
      expect(src, `${file}에 hsl(var(${token}))가 더는 없다 — allowlist에서 지워라`).toMatch(
        new RegExp(`hsla?\\(\\s*var\\(\\s*${token}\\s*\\)`)
      );
    }
  });
});
