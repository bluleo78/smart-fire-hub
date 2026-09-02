/**
 * 무엇: 디자인 가이드라인 중 "문서가 정답을 딱 하나로 명시한" 규칙들을 정적으로 고정하는 게이트.
 * 왜:  이런 이탈은 한 번 고쳐도 다음 기능 개발에서 조용히 다시 유입된다(#435 에서 실제로
 *      코드베이스 전체에 각 1건씩만 남아 있던 상태였다). 값이 하나로 정해진 규칙이라
 *      리뷰어 눈이 아니라 테스트가 지키는 편이 싸다.
 *
 * 범위: src/ 전체(.ts/.tsx). `components/ui/` 는 shadcn 생성물이라 손대지 않으므로 제외한다.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

const SRC_DIR = resolve(dirname(fileURLToPath(import.meta.url)), '..');

/** src/ 하위의 소스 파일을 모은다(테스트 파일과 shadcn 생성물은 제외). */
function collectSources(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === 'ui' && dir.endsWith('components')) continue; // shadcn 생성물
      out.push(...collectSources(full));
      continue;
    }
    if (!/\.tsx?$/.test(entry)) continue;
    if (/\.test\.tsx?$/.test(entry)) continue;
    out.push(full);
  }
  return out;
}

const FILES = collectSources(SRC_DIR);
const rel = (f: string) => f.slice(SRC_DIR.length + 1);

describe('디자인 가이드라인 게이트', () => {
  /**
   * 03-spacing-layout.md §승인된 Spacing Scale:
   * "space-5 (20px)는 프로젝트에서 사용하지 않는다. 새로 도입하지 말 것."
   *
   * 간격 유틸(p/m/gap/space)만 본다. `h-5`·`w-5` 는 간격이 아니라 아이콘 크기(20px)이고
   * 07-iconography.md 의 크기 스케일에 정식으로 들어 있으므로 걸러지면 안 된다.
   */
  it('20px 간격(-5) 을 쓰지 않는다', () => {
    const spacing5 = /\b(?:p|m|gap|space)[xytrbl]?-5\b/;
    const offenders = FILES.filter((f) => spacing5.test(readFileSync(f, 'utf8')));
    expect(
      offenders.map(rel),
      '03-spacing-layout.md 의 승인 스케일에 20px 은 없다 — 16px(-4) 또는 24px(-6) 을 쓰세요',
    ).toEqual([]);
  });

  /**
   * 07-iconography.md §7.1 의미 일관성 / §7.2 금지 패턴:
   * 같은 개념에는 항상 같은 아이콘을 쓴다. Edit→Pencil, Trash→Trash2, Reload→RefreshCw.
   *
   * `Trash2`·`Edit2` 같은 정식 이름이 걸리지 않도록 import 지정자 하나를 통째로 대조한다
   * (부분 문자열 매칭이면 Trash2 가 Trash 로 잡혀 게이트가 거짓 양성이 된다).
   */
  it('같은 개념에 폐기된 아이콘 별칭(Edit/Trash/Reload)을 쓰지 않는다', () => {
    const FORBIDDEN = new Map([
      ['Edit', 'Pencil'],
      ['Trash', 'Trash2'],
      ['Reload', 'RefreshCw'],
    ]);
    const offenders: string[] = [];
    for (const file of FILES) {
      const src = readFileSync(file, 'utf8');
      for (const m of src.matchAll(/import\s*\{([^}]*)\}\s*from\s*'lucide-react'/g)) {
        for (const raw of m[1].split(',')) {
          // `Edit as PencilIcon` 형태도 원본 이름 기준으로 판정한다.
          const name = raw.trim().split(/\s+as\s+/)[0].trim();
          const replacement = FORBIDDEN.get(name);
          if (replacement) offenders.push(`${rel(file)}: ${name} → ${replacement}`);
        }
      }
    }
    expect(offenders, '07-iconography.md §7.2 가 금지한 아이콘 별칭입니다').toEqual([]);
  });
});
