import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { ChevronDown, ChevronRight, GripVertical, Plus,Trash2 } from 'lucide-react';

import type { SectionType,TemplateSection } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { getSectionTypeDef, SECTION_TYPES } from '@/lib/template-section-types';
import { cn } from '@/lib/utils';

interface SectionTreeItemProps {
  section: TemplateSection;
  depth: number;
  isSelected: boolean;
  isCollapsed: boolean;
  isDragOverlay?: boolean;
  onSelect: () => void;
  onRemove: () => void;
  onToggleCollapse: () => void;
  onAddChild?: (type: SectionType) => void;
}

export function SectionTreeItem({
  section,
  depth,
  isSelected,
  isCollapsed,
  isDragOverlay,
  onSelect,
  onRemove,
  onToggleCollapse,
  onAddChild,
}: SectionTreeItemProps) {
  const {
    attributes,
    listeners,
    setNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({ id: section.key });

  const def = getSectionTypeDef(section.type);
  const isStatic = section.static || section.type === 'divider';
  const isGroup = section.type === 'group';

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
    paddingLeft: depth * 16,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={cn(
        'flex items-center gap-2 rounded-md px-2 py-1.5 text-sm transition-colors group relative cursor-pointer',
        isDragging && 'opacity-40',
        isDragOverlay &&
          'bg-muted border border-border shadow-lg rounded-md opacity-90',
        isSelected
          ? 'bg-accent border-l-2 border-l-primary'
          : `hover:bg-muted/50 border-l-3 ${isStatic ? 'border-l-muted-foreground' : (def?.color ?? 'border-l-gray-500')}`,
        isStatic && 'text-muted-foreground',
      )}
      onClick={onSelect}
      {...attributes}
    >
      {/* Drag handle - visible on hover */}
      <span
        className="opacity-0 group-hover:opacity-100 transition-opacity cursor-grab active:cursor-grabbing"
        {...listeners}
      >
        <GripVertical className="h-4 w-4 text-muted-foreground" />
      </span>

      {/* Group collapse toggle */}
      {isGroup && (
        <button
          onClick={(e) => {
            e.stopPropagation();
            onToggleCollapse();
          }}
          className="p-0.5"
        >
          {isCollapsed ? (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground transition-transform" />
          ) : (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground transition-transform" />
          )}
        </button>
      )}

      {/* Icon + Label */}
      <span className="text-base">{def?.icon}</span>
      <span className={cn('flex-1 truncate', isGroup && 'font-medium')}>
        {section.label}
      </span>

      {/* Badges */}
      {section.required && (
        <span
          className="w-1.5 h-1.5 rounded-full bg-warning flex-shrink-0"
          title="필수"
        />
      )}
      {isStatic && (
        <Badge variant="secondary" className="text-[10px] h-5">
          정적
        </Badge>
      )}
      <Badge variant="outline" className="text-[10px]">
        {section.type}
      </Badge>

      {/* Add child button for groups */}
      {isGroup && onAddChild && (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button
              variant="ghost"
              size="icon"
              className="h-6 w-6 opacity-0 group-hover:opacity-100"
              onClick={(e) => e.stopPropagation()}
            >
              <Plus className="h-3 w-3" />
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            {SECTION_TYPES
              .filter(t => t.type !== 'group' && t.type !== 'divider')
              .map(t => (
                <DropdownMenuItem
                  key={t.type}
                  onClick={(e) => {
                    e.stopPropagation();
                    onAddChild(t.type as SectionType);
                  }}
                >
                  <span className="mr-2">{t.icon}</span> {t.label}
                </DropdownMenuItem>
              ))}
          </DropdownMenuContent>
        </DropdownMenu>
      )}

      {/* Delete button - visible on hover */}
      <Button
        variant="ghost"
        size="icon"
        className="h-6 w-6 opacity-0 group-hover:opacity-100"
        onClick={(e) => {
          e.stopPropagation();
          onRemove();
        }}
      >
        <Trash2 className="h-3 w-3" />
      </Button>
    </div>
  );
}
