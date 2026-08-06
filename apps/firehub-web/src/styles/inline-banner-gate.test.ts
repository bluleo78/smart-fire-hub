/**
 * 무엇: pages/ 하위에서 인라인 배너를 손으로 다시 조립하는 것을 막는 게이트.
 * 왜: 이 관용구는 원래 6곳에 복제돼 있었고, 그 사본들이 서로 다른 보더 알파(/20, /30)를 써서
 *     전부 SC 1.4.11(3:1)에 미달했다. InlineBanner로 추출한 뒤 다시 복제되면 같은 결함이 돌아온다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const PAGES_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '../pages');

// pages/ 하위 .tsx 를 모두 모은다.
function collectTsx(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...collectTsx(full));
    else if (entry.endsWith('.tsx')) out.push(full);
  }
  return out;
}

// SectionPreview는 배너가 아니라 AI 리포트 섹션 미리보기의 스켈레톤 플레이스홀더다
// (본문이 텍스트가 아니라 h-2 가짜 바). 의미가 달라 InlineBanner로 흡수하지 않는다.
const TINT_ALLOWED = ['ai-insights/components/SectionPreview.tsx'];

// ChannelStatusBadge는 Badge 컴포넌트에 알파 보더를 쓴 것이지 배너가 아니다 — 제외.
const BORDER_ALLOWED = [
  'ai-insights/components/SectionPreview.tsx',
  'settings/components/ChannelStatusBadge.tsx',
];

describe('InlineBanner 게이트', () => {
  const allFiles = collectTsx(PAGES_DIR);

  // 배너 관용구는 "같은 줄에 -subtle 배경과 알파 보더가 함께 있는 것"으로 정의한다.
  // 배경 틴트만 단독으로 쓰는 것(예: 단계 상태 칩)은 정당한 사용이라 여기서 걸러지지 않아야 한다.
  it('pages/ 하위에서 배너 관용구(틴트 배경 + 알파 보더)를 다시 조립하지 않는다', () => {
    const files = allFiles.filter(
      (f) => !TINT_ALLOWED.some((a) => f.replace(/\\/g, '/').endsWith(a)),
    );
    const offenders = files.filter((f) =>
      readFileSync(f, 'utf8')
        .split('\n')
        .some(
          (line) =>
            /bg-(warning|info|success|caution)-subtle/.test(line) &&
            /border-(warning|info|success|caution)\/\d+/.test(line),
        ),
    );
    expect(
      offenders.map((f) => f.slice(PAGES_DIR.length + 1)),
      'InlineBanner 컴포넌트를 사용하세요',
    ).toEqual([]);
  });

  it('pages/ 하위에서 알파 보더로 경계를 만들지 않는다', () => {
    const files = allFiles.filter(
      (f) => !BORDER_ALLOWED.some((a) => f.replace(/\\/g, '/').endsWith(a)),
    );
    const offenders = files.filter((f) =>
      /border-(warning|info|success|caution)\/\d+/.test(readFileSync(f, 'utf8')),
    );
    expect(
      offenders.map((f) => f.slice(PAGES_DIR.length + 1)),
      '알파 보더는 SC 1.4.11(3:1)에 미달한다 — 실색을 쓰세요',
    ).toEqual([]);
  });
});
