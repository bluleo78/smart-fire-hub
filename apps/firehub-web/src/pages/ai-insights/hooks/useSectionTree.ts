import { useCallback, useMemo,useState } from 'react';
import { toast } from 'sonner';

import type { SectionType,TemplateSection } from '@/api/proactive';
import { flattenSections,validateSectionDepth } from '@/lib/template-section-types';

export interface FlatItem {
  section: TemplateSection;
  depth: number;
  parentKey: string | null;
  /**
   * 루트부터의 인덱스 경로 (예: [1, 0] = 두 번째 최상위 섹션의 첫 자식) — #361
   * key는 중복될 수 있어 노드를 유일하게 가리키지 못하므로, 삭제 같은 파괴적 동작은
   * 이 경로를 기준으로 수행한다.
   */
  path: number[];
}

export function useSectionTree(initialSections: TemplateSection[]) {
  const [sections, setSections] = useState<TemplateSection[]>(initialSections);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [collapsedKeys, setCollapsedKeys] = useState<Set<string>>(new Set());

  // DFS flatten for dnd-kit — produces visible items respecting collapsed state
  const flatItems = useMemo<FlatItem[]>(() => {
    const result: FlatItem[] = [];
    function walk(items: TemplateSection[], depth: number, parentKey: string | null, base: number[]) {
      items.forEach((item, index) => {
        const path = [...base, index];
        result.push({ section: item, depth, parentKey, path });
        if (item.type === 'group' && item.children && !collapsedKeys.has(item.key)) {
          walk(item.children, depth + 1, item.key, path);
        }
      });
    }
    walk(sections, 0, null, []);
    return result;
  }, [sections, collapsedKeys]);

  // Selected section object
  const selectedSection = useMemo<TemplateSection | null>(() => {
    if (!selectedKey) return null;
    return flattenSections(sections).find((s) => s.key === selectedKey) ?? null;
  }, [sections, selectedKey]);

  // Generate unique key
  const generateKey = useCallback(
    (type: string): string => {
      const allKeys = flattenSections(sections).map((s) => s.key);
      let counter = 1;
      let key = `${type}_${counter}`;
      while (allKeys.includes(key)) {
        counter++;
        key = `${type}_${counter}`;
      }
      return key;
    },
    [sections],
  );

  // Add section
  const addSection = useCallback(
    (type: SectionType, parentKey?: string) => {
      const key = generateKey(type);
      const newSection: TemplateSection = {
        key,
        type,
        label: type === 'group' ? '새 그룹' : type === 'divider' ? '구분선' : `새 ${type} 섹션`,
        ...(type === 'group' ? { children: [] } : {}),
        ...(type === 'divider' ? { static: true } : {}),
      };

      setSections((prev) => {
        if (!parentKey) return [...prev, newSection];
        return addToParent(prev, parentKey, newSection);
      });
      setSelectedKey(key);
    },
    [generateKey],
  );

  /**
   * 섹션 삭제 — 인덱스 경로로 **정확히 그 노드 하나만** 제거한다 (#361).
   * key 기준 필터는 중복 키가 존재할 때 무관한 섹션까지 함께 지워 무음 유실을 일으켰다.
   */
  const removeSection = useCallback(
    (path: number[]) => {
      // 삭제 대상이 선택 중이었다면 선택 해제
      const target = getAtPath(sections, path);
      if (target && selectedKey === target.key) setSelectedKey(null);
      setSections((prev) => removeAtPath(prev, path));
    },
    [sections, selectedKey],
  );

  // Update section properties
  const updateSection = useCallback((key: string, patch: Partial<TemplateSection>) => {
    setSections((prev) => updateInTree(prev, key, patch));
  }, []);

  // Move section (for dnd-kit onDragEnd)
  // flatItems를 현재 sections 기반으로 재계산하여 그룹 간 이동도 지원
  const moveSection = useCallback((activeId: string, overId: string) => {
    if (activeId === overId) return;
    setSections((prev) => {
      // collapsed 상태와 무관하게 전체 트리를 순회하여 flatItems 재계산
      const currentFlat: FlatItem[] = [];
      function walkForFlat(items: TemplateSection[], depth: number, parentKey: string | null, base: number[]) {
        items.forEach((item, index) => {
          const path = [...base, index];
          currentFlat.push({ section: item, depth, parentKey, path });
          if (item.type === 'group' && item.children) {
            walkForFlat(item.children, depth + 1, item.key, path);
          }
        });
      }
      walkForFlat(prev, 0, null, []);

      const moved = moveSectionInTree(prev, activeId, overId, currentFlat);
      if (!validateSectionDepth(moved)) {
        toast.error('최대 3단계까지 중첩 가능합니다.');
        return prev;
      }
      return moved;
    });
  }, []);

  // Toggle collapsed
  const toggleCollapsed = useCallback((key: string) => {
    setCollapsedKeys((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }, []);

  return {
    sections,
    setSections,
    selectedKey,
    setSelectedKey,
    selectedSection,
    addSection,
    removeSection,
    updateSection,
    moveSection,
    toggleCollapsed,
    collapsedKeys,
    flatItems,
  };
}

// --- Tree manipulation helpers ---

function addToParent(
  sections: TemplateSection[],
  parentKey: string,
  newSection: TemplateSection,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === parentKey && s.type === 'group') {
      return { ...s, children: [...(s.children || []), newSection] };
    }
    if (s.children) {
      return { ...s, children: addToParent(s.children, parentKey, newSection) };
    }
    return s;
  });
}

/**
 * 인덱스 경로가 가리키는 노드를 반환한다. 경로가 유효하지 않으면 null (#361).
 */
export function getAtPath(sections: TemplateSection[], path: number[]): TemplateSection | null {
  if (path.length === 0) return null;
  let current: TemplateSection | undefined = sections[path[0]];
  for (let i = 1; i < path.length; i++) {
    if (!current?.children) return null;
    current = current.children[path[i]];
  }
  return current ?? null;
}

/**
 * 인덱스 경로가 가리키는 노드 **하나만** 제거한다 (#361).
 * key 기반 필터와 달리 동일 key가 여러 곳에 있어도 대상 외 노드는 보존된다.
 */
export function removeAtPath(sections: TemplateSection[], path: number[]): TemplateSection[] {
  if (path.length === 0) return sections;
  const [index, ...rest] = path;
  if (index < 0 || index >= sections.length) return sections;

  if (rest.length === 0) {
    return sections.filter((_, i) => i !== index);
  }

  const target = sections[index];
  if (!target.children) return sections;
  return sections.map((s, i) =>
    i === index ? { ...s, children: removeAtPath(s.children ?? [], rest) } : s,
  );
}

function removeFromTree(sections: TemplateSection[], key: string): TemplateSection[] {
  return sections
    .filter((s) => s.key !== key)
    .map((s) => (s.children ? { ...s, children: removeFromTree(s.children, key) } : s));
}

function updateInTree(
  sections: TemplateSection[],
  key: string,
  patch: Partial<TemplateSection>,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === key) return { ...s, ...patch };
    if (s.children) return { ...s, children: updateInTree(s.children, key, patch) };
    return s;
  });
}

/**
 * 섹션을 트리 내에서 이동한다.
 * - 같은 부모 내: 순서 변경
 * - 다른 부모로: 원래 위치에서 제거 → 대상 위치에 삽입
 * - 그룹 위에 드롭: 해당 그룹의 children 마지막에 추가
 */
function moveSectionInTree(
  sections: TemplateSection[],
  activeId: string,
  overId: string,
  flatItems: FlatItem[],
): TemplateSection[] {
  const activeFlat = flatItems.find((f) => f.section.key === activeId);
  const overFlat = flatItems.find((f) => f.section.key === overId);
  if (!activeFlat || !overFlat) return sections;

  const activeSection = activeFlat.section;
  const activeParent = activeFlat.parentKey;
  const overParent = overFlat.parentKey;

  // over가 group이고 active가 group이 아니면 → 그룹 children 마지막에 추가
  if (overFlat.section.type === 'group' && activeSection.type !== 'group') {
    const withoutActive = removeFromTree(sections, activeId);
    return addToParent(withoutActive, overId, { ...activeSection });
  }

  // 같은 부모 내 이동
  if (activeParent === overParent) {
    if (activeParent === null) {
      return reorderInArray(sections, activeId, overId);
    } else {
      return reorderInParent(sections, activeParent, activeId, overId);
    }
  }

  // 다른 부모 간 이동
  const withoutActive = removeFromTree(sections, activeId);
  if (overParent === null) {
    return insertBeforeInArray(withoutActive, overId, activeSection);
  } else {
    return insertBeforeInParent(withoutActive, overParent, overId, activeSection);
  }
}

/** 배열 내에서 activeId를 overId 앞으로 이동 */
function reorderInArray(items: TemplateSection[], activeId: string, overId: string): TemplateSection[] {
  const activeIdx = items.findIndex((s) => s.key === activeId);
  const overIdx = items.findIndex((s) => s.key === overId);
  if (activeIdx === -1 || overIdx === -1) return items;

  const result = [...items];
  const [moved] = result.splice(activeIdx, 1);
  const newOverIdx = result.findIndex((s) => s.key === overId);
  result.splice(newOverIdx, 0, moved);
  return result;
}

/** 특정 부모 그룹의 children 내에서 순서 변경 */
function reorderInParent(
  sections: TemplateSection[],
  parentKey: string,
  activeId: string,
  overId: string,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === parentKey && s.children) {
      return { ...s, children: reorderInArray(s.children, activeId, overId) };
    }
    if (s.children) {
      return { ...s, children: reorderInParent(s.children, parentKey, activeId, overId) };
    }
    return s;
  });
}

/** 배열에서 overId 앞에 section 삽입 */
function insertBeforeInArray(
  items: TemplateSection[],
  overId: string,
  section: TemplateSection,
): TemplateSection[] {
  const result: TemplateSection[] = [];
  for (const item of items) {
    if (item.key === overId) result.push(section);
    result.push(item);
  }
  // overId를 찾지 못한 경우 마지막에 추가 (방어 코드)
  if (!result.some((s) => s.key === section.key)) result.push(section);
  return result;
}

/** 특정 부모 그룹의 children에서 overId 앞에 section 삽입 */
function insertBeforeInParent(
  sections: TemplateSection[],
  parentKey: string,
  overId: string,
  section: TemplateSection,
): TemplateSection[] {
  return sections.map((s) => {
    if (s.key === parentKey && s.children) {
      return { ...s, children: insertBeforeInArray(s.children, overId, section) };
    }
    if (s.children) {
      return { ...s, children: insertBeforeInParent(s.children, parentKey, overId, section) };
    }
    return s;
  });
}
