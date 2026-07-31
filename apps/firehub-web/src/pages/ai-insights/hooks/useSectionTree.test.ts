import { describe, expect, it } from 'vitest';

import type { TemplateSection } from '@/api/proactive';

import { getAtPath, removeAtPath } from './useSectionTree';

/**
 * 인덱스 경로 기반 삭제 회귀 테스트 (#361)
 *
 * 과거 구현은 `s.key !== key` 로 모든 depth를 필터해서, 중복 key가 있으면
 * 사용자가 지목하지 않은 섹션까지 함께 삭제되는 무음 데이터 유실이 발생했다.
 */
function buildDuplicateKeyTree(): TemplateSection[] {
  return [
    { key: 'summary', type: 'text', label: '요약' },
    {
      key: 'grp',
      type: 'group',
      label: '그룹',
      children: [
        { key: 'detail', type: 'text', label: '상세' },
        { key: 'summary', type: 'text', label: '중복키' },
      ],
    },
  ] as TemplateSection[];
}

describe('removeAtPath', () => {
  it('중복 key가 있어도 경로가 가리키는 노드 하나만 삭제한다', () => {
    const tree = buildDuplicateKeyTree();

    // 그룹(index 1)의 두 번째 자식(index 1) = 중복키 섹션
    const result = removeAtPath(tree, [1, 1]);

    // 최상위 'summary'(요약)는 보존되어야 한다 — 여기가 유실 지점이었다
    expect(result.map((s) => s.label)).toEqual(['요약', '그룹']);
    expect(result[1].children?.map((s) => s.label)).toEqual(['상세']);
  });

  it('최상위 노드를 경로로 삭제하면 그룹 하위 동일 key는 보존된다', () => {
    const tree = buildDuplicateKeyTree();

    const result = removeAtPath(tree, [0]);

    expect(result.map((s) => s.label)).toEqual(['그룹']);
    expect(result[0].children?.map((s) => s.label)).toEqual(['상세', '중복키']);
  });

  it('원본 배열을 변경하지 않는다', () => {
    const tree = buildDuplicateKeyTree();
    removeAtPath(tree, [1, 1]);
    expect(tree[1].children).toHaveLength(2);
  });

  it('범위를 벗어난 경로는 트리를 그대로 반환한다', () => {
    const tree = buildDuplicateKeyTree();
    expect(removeAtPath(tree, [5])).toBe(tree);
    expect(removeAtPath(tree, [])).toBe(tree);
    expect(removeAtPath(tree, [0, 0])).toBe(tree); // children 없는 노드
  });
});

describe('getAtPath', () => {
  it('경로가 가리키는 노드를 반환한다', () => {
    const tree = buildDuplicateKeyTree();
    expect(getAtPath(tree, [1, 1])?.label).toBe('중복키');
    expect(getAtPath(tree, [0])?.label).toBe('요약');
  });

  it('유효하지 않은 경로는 null을 반환한다', () => {
    const tree = buildDuplicateKeyTree();
    expect(getAtPath(tree, [])).toBeNull();
    expect(getAtPath(tree, [9])).toBeNull();
    expect(getAtPath(tree, [0, 0])).toBeNull();
  });
});
