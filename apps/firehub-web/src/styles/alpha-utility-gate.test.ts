/**
 * 알파 수식자 금지 게이트 (#366 / #368)
 *
 * 무엇: `src/` 전역을 훑어 `text-*` / `ring-*` / `outline-*` 유틸에 붙은 알파 수식자(`/50` 등)를
 *       찾아내고, 명시적 allowlist에 없으면 실패시킨다.
 * 왜:   역할 계약(#370 스펙 1절) — 알파는 대비를 계산 불가능하게 만들므로 전경·포커스 표시에
 *       금지하고, 장식·틴트 용도인 `bg-*` / `border-*` 에만 허용한다.
 *       `text-muted-foreground/50`은 라이트에서 2.10:1까지 떨어졌고,
 *       `ring-ring/50`은 포커스 링을 2.0:1로 만들어 SC 1.4.11을 위반했다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/**
 * 예외 — 순수 장식(aria-hidden 아이콘)이거나 SC 1.4.3이 명시적으로 제외하는 비활성 요소.
 * 새 항목을 넣으려면 반드시 사유를 함께 적는다.
 */
const ALLOWLIST: { file: string; util: string; reason: string }[] = [
  {
    file: 'components/ui/table-empty.tsx',
    util: 'text-muted-foreground/60',
    reason: 'aria-hidden 빈 상태 일러스트 아이콘 — 옆 텍스트가 의미를 전달하는 순수 장식',
  },
  {
    file: 'components/ai/AIChatPanel.tsx',
    util: 'text-muted-foreground/50',
    reason: 'aria-hidden Sparkles 빈 상태 일러스트 — 순수 장식',
  },
  {
    file: 'components/ai/widgets/table/ExportDropdown.tsx',
    util: 'text-muted-foreground/50',
    reason: 'cursor-not-allowed 비활성 항목 — SC 1.4.3 명시적 예외',
  },
  {
    file: 'pages/HomePage.tsx',
    util: 'text-muted-foreground/50',
    reason: 'aria-hidden Activity 빈 상태 일러스트 — 순수 장식',
  },
  {
    file: 'pages/pipeline/components/PythonOutputColumns.tsx',
    util: 'text-muted-foreground/40',
    reason: 'aria-hidden Plus 아이콘 — 순수 장식',
  },
  {
    file: 'pages/pipeline/components/TriggerTab.tsx',
    util: 'text-muted-foreground/30',
    reason: 'aria-hidden Clock 빈 상태 일러스트 — 순수 장식',
  },
];

/**
 * `aria-invalid:ring-destructive/N`은 포커스 표시가 아니라 오류 상태 보조 표시이고,
 * 같은 요소의 `aria-invalid:border-destructive`가 3:1을 담당하므로 SC 1.4.11 위반이 아니다.
 * (#368 확정안의 명시적 예외)
 */
const ARIA_INVALID_RING = /aria-invalid:ring-[\w-]+\/\d+/g;

/**
 * 이번 라운드의 게이트 범위 — 포커스 표시(`ring-*`/`outline-*`) 전부 + `text-muted-foreground/*`.
 * `text-foreground/70`·`text-primary/70` 등 다른 토큰의 알파 텍스트 유틸 전수 감사는
 * 디자이너가 별도 라운드로 이연했다(→ docs/design-system/13-migration-backlog.md).
 * 그 감사를 할 때 아래 정규식을 `(?:text|ring|outline)-[a-z][\w-]*`로 넓히면 된다.
 */
const ALPHA_UTIL = /\b(?:(?:ring|outline)-[a-z][\w-]*|text-muted-foreground)\/\d{1,3}\b/g;

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (/\.tsx?$/.test(name) && !/\.test\.tsx?$/.test(name)) acc.push(full);
  }
  return acc;
}

describe('알파 수식자 금지 게이트', () => {
  it('text-* / ring-* / outline-* 에 알파 수식자가 없다 (allowlist 제외)', () => {
    const violations: string[] = [];

    for (const full of walk(SRC)) {
      const rel = relative(SRC, full).split('\\').join('/');
      const lines = readFileSync(full, 'utf8').split('\n');

      lines.forEach((line, i) => {
        for (const hit of line.replace(ARIA_INVALID_RING, '').match(ALPHA_UTIL) ?? []) {
          const allowed = ALLOWLIST.some((a) => a.file === rel && a.util === hit);
          if (!allowed) violations.push(`${rel}:${i + 1}  ${hit}`);
        }
      });
    }

    expect(violations, `알파 수식자 위반:\n${violations.join('\n')}`).toEqual([]);
  });

  it('allowlist 항목이 실제로 존재한다 (죽은 예외 방지)', () => {
    for (const { file, util } of ALLOWLIST) {
      const src = readFileSync(join(SRC, file), 'utf8');
      expect(src, `${file}에 ${util}가 더는 없다 — allowlist에서 지워라`).toContain(util);
    }
  });
});
