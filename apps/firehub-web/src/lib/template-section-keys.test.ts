import { describe, expect, it } from 'vitest';

import type { TemplateSection } from '@/api/proactive';
import { validateSectionKeys } from '@/lib/template-section-types';

/**
 * 섹션 Key 트리 전역 검증 회귀 테스트 (#361)
 * 과거 구현은 최상위 배열만 순회해 그룹 하위 key가 검증을 통째로 우회했다.
 */
describe('validateSectionKeys', () => {
  it('정상 트리는 null을 반환한다', () => {
    const sections = [
      { key: 'summary', type: 'text', label: '요약' },
      { key: 'grp', type: 'group', label: '그룹', children: [{ key: 'detail', type: 'text', label: '상세' }] },
    ] as TemplateSection[];
    expect(validateSectionKeys(sections)).toBeNull();
  });

  it('그룹 하위의 형식 위반 key를 잡아낸다', () => {
    const sections = [
      { key: 'grp', type: 'group', label: '그룹', children: [{ key: 'BAD KEY!', type: 'text', label: '불량키' }] },
    ] as TemplateSection[];
    const error = validateSectionKeys(sections);
    expect(error).toContain('BAD KEY!');
    expect(error).toContain('그룹 하위');
  });

  it('최상위와 그룹 하위에 걸친 중복 key를 잡아낸다', () => {
    const sections = [
      { key: 'summary', type: 'text', label: '요약' },
      { key: 'grp', type: 'group', label: '그룹', children: [{ key: 'summary', type: 'text', label: '중복키' }] },
    ] as TemplateSection[];
    const error = validateSectionKeys(sections);
    expect(error).toContain('중복');
    expect(error).toContain('summary');
  });

  it('그룹 자신의 key도 유일성 검사 대상이다', () => {
    const sections = [
      { key: 'grp', type: 'text', label: '텍스트' },
      { key: 'grp', type: 'group', label: '그룹', children: [] },
    ] as TemplateSection[];
    expect(validateSectionKeys(sections)).toContain('중복');
  });

  it('3단계 중첩까지 순회한다', () => {
    const sections = [
      {
        key: 'a',
        type: 'group',
        label: 'A',
        children: [
          { key: 'b', type: 'group', label: 'B', children: [{ key: 'Bad', type: 'text', label: '깊은 불량키' }] },
        ],
      },
    ] as TemplateSection[];
    expect(validateSectionKeys(sections)).toContain('Bad');
  });
});
