import { useState, useCallback, useMemo } from 'react';
import { toast } from 'sonner';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { validateSectionDepth, flattenSections } from '@/lib/template-section-types';

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
  const moveSection = useCallback((activeId: string, overId: string) => {
    if (activeId === overId) return;
    setSections((prev) => {
      const moved = moveSectionInTree(prev, activeId, overId);
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

function moveSectionInTree(
  sections: TemplateSection[],
  activeId: string,
  overId: string,
): TemplateSection[] {
  // 1. Find the active section from the tree
  const found = flattenSections(sections).find((s) => s.key === activeId);
  if (!found) return sections;
  const active: TemplateSection = found;

  // 2. Remove it from current position
  const withoutActive = removeFromTree(sections, activeId);

  // 3. Insert before the overId at the same level
  function insertBefore(items: TemplateSection[]): TemplateSection[] {
    const result: TemplateSection[] = [];
    for (const item of items) {
      if (item.key === overId) {
        result.push(active);
      }
      result.push(
        item.children ? { ...item, children: insertBefore(item.children) } : item,
      );
    }
    return result;
  }

  const moved = insertBefore(withoutActive);

  // If overId was not found in the tree (e.g. it was a child of active that got removed),
  // fall back to appending at root level
  if (flattenSections(moved).every((s) => s.key !== activeId)) {
    return [...withoutActive, active];
  }

  return moved;
}
