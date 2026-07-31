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

  /**
   * #372: 전경에 알파가 금지되는 것과 짝을 이루는 규칙 —
   * `text-X` 전경이 얹히는 **배경 틴트**는 역할 계약이 보장하는 `bg-X/10`을 넘지 않는다.
   * 틴트가 진해질수록 전경과 배경이 같은 색으로 수렴해 대비가 무너진다
   * (light/ocean `bg-primary/30 text-primary` = 3.68:1).
   *
   * 범위는 이번 라운드 대상인 `primary`로 한정한다. `bg-info/15 text-info`,
   * `bg-destructive/20 text-destructive` 등 다른 토큰의 전수 감사는 별도 라운드다
   * (→ docs/design-system/13-migration-backlog.md). 넓히려면 TOKEN을 정규식으로 바꾸면 된다.
   */
  it('text-primary 위에 깔리는 bg-primary 틴트가 10%를 넘지 않는다', () => {
    /**
     * 예외 — 전경이 텍스트가 아니라 아이콘(SVG `currentColor`)인 컨트롤.
     * SC 1.4.3(4.5:1)이 아니라 SC 1.4.11(3:1)이 적용되고, 가장 진한 hover 30% 틴트에서도
     * 6개 테마 조합 전부 3:1을 넘는다. 새 항목은 반드시 사유를 함께 적는다.
     */
    const ICON_ONLY: { file: string; reason: string }[] = [
      {
        file: 'components/ai/AIStatusChipDropdown.tsx',
        reason: '전송 버튼 — 라벨 없는 아이콘 전용 컨트롤(SC 1.4.11 3:1 적용)',
      },
    ];
    const violations: string[] = [];
    for (const full of walk(SRC)) {
      const rel = relative(SRC, full).split('\\').join('/');
      if (ICON_ONLY.some((a) => a.file === rel)) continue;
      readFileSync(full, 'utf8')
        .split('\n')
        .forEach((line, i) => {
          for (const [util, pct] of line.matchAll(/\bbg-primary\/(\d{1,3})\b/g)) {
            if (Number(pct) <= 10) continue;
            // `text-primary-foreground`는 짝 토큰이라 제외 — 하이픈 뒤가 이어지면 다른 토큰이다.
            if (!/\btext-primary(?![\w-])/.test(line)) continue;
            violations.push(`${rel}:${i + 1}  ${util}`);
          }
        });
    }
    expect(violations, `과한 틴트 위 전경:\n${violations.join('\n')}`).toEqual([]);
  });

  /**
   * AIStatusChip은 Tailwind 유틸이 아니라 인라인 `color-mix(..., transparent)`로 틴트를 만든다.
   * (Tailwind v4는 알파 수식자를 `color-mix(... , transparent)` 원문으로 남기므로 위 정규식으로는
   * 잡히지 않는다.) 칩 라벨은 같은 토큰의 원색을 전경으로 쓰므로 동일한 10% 한도를 적용한다.
   */
  it('AIStatusChip 상태별 배경 틴트가 10%를 넘지 않는다', () => {
    const src = readFileSync(join(SRC, 'components/ai/AIStatusChip.tsx'), 'utf8');
    const pcts = [...src.matchAll(/background: 'color-mix\(in oklch, var\(--[\w-]+\) (\d+)%/g)].map(
      (m) => Number(m[1])
    );
    expect(pcts.length).toBeGreaterThanOrEqual(8); // 8개 상태 전부 잡혔는지(정규식 공허화 방지)
    expect(pcts.filter((p) => p > 10)).toEqual([]);
  });

  it('allowlist 항목이 실제로 존재한다 (죽은 예외 방지)', () => {
    for (const { file, util } of ALLOWLIST) {
      const src = readFileSync(join(SRC, file), 'utf8');
      expect(src, `${file}에 ${util}가 더는 없다 — allowlist에서 지워라`).toContain(util);
    }
  });
});
