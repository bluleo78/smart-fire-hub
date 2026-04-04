import { useState } from 'react';
import {
  DndContext,
  closestCenter,
  KeyboardSensor,
  PointerSensor,
  useSensor,
  useSensors,
  DragOverlay,
  type DragStartEvent,
  type DragEndEvent,
} from '@dnd-kit/core';
import {
  SortableContext,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { Plus } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { SECTION_TYPES } from '@/lib/template-section-types';
import { SectionTreeItem } from './SectionTreeItem';
import type { FlatItem } from '../hooks/useSectionTree';

interface SectionTreeBuilderProps {
  sections: TemplateSection[];
  selectedKey: string | null;
  collapsedKeys: Set<string>;
  flatItems: FlatItem[];
  onSelect: (key: string) => void;
  onMove: (activeId: string, overId: string) => void;
  onAdd: (type: SectionType, parentKey?: string) => void;
  onRemove: (key: string) => void;
  onToggleCollapse: (key: string) => void;
}

export function SectionTreeBuilder({
  sections: _sections,
  selectedKey,
  collapsedKeys,
  flatItems,
  onSelect,
  onMove,
  onAdd,
  onRemove,
  onToggleCollapse,
}: SectionTreeBuilderProps) {
  const [activeId, setActiveId] = useState<string | null>(null);

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  );

  function handleDragStart(event: DragStartEvent) {
    setActiveId(String(event.active.id));
  }

  function handleDragEnd(event: DragEndEvent) {
    setActiveId(null);
    const { active, over } = event;
    if (over && active.id !== over.id) {
      onMove(String(active.id), String(over.id));
    }
  }

  const activeItem = activeId
    ? flatItems.find(f => f.section.key === activeId)
    : null;

  const sortableIds = flatItems.map(f => f.section.key);

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b">
        <h3 className="text-sm font-semibold">섹션 구조</h3>
        <Badge variant="secondary" className="text-xs">
          {flatItems.length}개
        </Badge>
      </div>

      {/* Tree */}
      <ScrollArea className="flex-1">
        <div className="p-2">
          <DndContext
            sensors={sensors}
            collisionDetection={closestCenter}
            onDragStart={handleDragStart}
            onDragEnd={handleDragEnd}
          >
            <SortableContext items={sortableIds} strategy={verticalListSortingStrategy}>
              {flatItems.map(({ section, depth }) => (
                <SectionTreeItem
                  key={section.key}
                  section={section}
                  depth={depth}
                  isSelected={section.key === selectedKey}
                  isCollapsed={collapsedKeys.has(section.key)}
                  onSelect={() => onSelect(section.key)}
                  onRemove={() => onRemove(section.key)}
                  onToggleCollapse={() => onToggleCollapse(section.key)}
                  onAddChild={section.type === 'group' ? (type) => onAdd(type, section.key) : undefined}
                />
              ))}
            </SortableContext>

            <DragOverlay>
              {activeItem ? (
                <SectionTreeItem
                  section={activeItem.section}
                  depth={0}
                  isSelected={false}
                  isCollapsed={false}
                  isDragOverlay
                  onSelect={() => {}}
                  onRemove={() => {}}
                  onToggleCollapse={() => {}}
                />
              ) : null}
            </DragOverlay>
          </DndContext>
        </div>
      </ScrollArea>

      {/* Add buttons */}
      <div className="flex gap-2 p-3 border-t">
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm" className="flex-1 border-dashed">
              <Plus className="h-3.5 w-3.5 mr-1" /> 섹션 추가
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start">
            {SECTION_TYPES
              .filter(t => t.type !== 'group')
              .map(t => (
                <DropdownMenuItem key={t.type} onClick={() => onAdd(t.type as SectionType)}>
                  <span className="mr-2">{t.icon}</span> {t.label}
                </DropdownMenuItem>
              ))}
          </DropdownMenuContent>
        </DropdownMenu>
        <Button
          variant="outline"
          size="sm"
          className="flex-1 border-dashed"
          onClick={() => onAdd('group' as SectionType)}
        >
          <Plus className="h-3.5 w-3.5 mr-1" /> 그룹 추가
        </Button>
      </div>
    </div>
  );
}
