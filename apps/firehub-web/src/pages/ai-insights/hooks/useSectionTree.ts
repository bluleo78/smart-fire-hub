import { useCallback, useMemo,useState } from 'react';
import { toast } from 'sonner';

import type { SectionType,TemplateSection } from '@/api/proactive';
import { flattenSections,validateSectionDepth } from '@/lib/template-section-types';

export interface FlatItem {
  section: TemplateSection;
  depth: number;
  parentKey: string | null;
}

export function useSectionTree(initialSections: TemplateSection[]) {
  const [sections, setSections] = useState<TemplateSection[]>(initialSections);
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const [collapsedKeys, setCollapsedKeys] = useState<Set<string>>(new Set());

  // DFS flatten for dnd-kit — produces visible items respecting collapsed state
  const flatItems = useMemo<FlatItem[]>(() => {
    const result: FlatItem[] = [];
    function walk(items: TemplateSection[], depth: number, parentKey: string | null) {
      for (const item of items) {
        result.push({ section: item, depth, parentKey });
        if (item.type === 'group' && item.children && !collapsedKeys.has(item.key)) {
          walk(item.children, depth + 1, item.key);
        }
      }
    }
    walk(sections, 0, null);
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

  // Remove section (recursive search)
  const removeSection = useCallback(
    (key: string) => {
      setSections((prev) => removeFromTree(prev, key));
      if (selectedKey === key) setSelectedKey(null);
    },
    [selectedKey],
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
      function walkForFlat(items: TemplateSection[], depth: number, parentKey: string | null) {
        for (const item of items) {
          currentFlat.push({ section: item, depth, parentKey });
          if (item.type === 'group' && item.children) {
            walkForFlat(item.children, depth + 1, item.key);
          }
        }
      }
      walkForFlat(prev, 0, null);

      const moved = moveSectionInTree(prev, activeId, overId, currentFlat);
      if (!validateSectionDepth(moved)) {
        toast.error('최대 3단계까지 중첩 가능합니다');
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
