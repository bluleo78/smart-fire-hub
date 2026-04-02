import { Badge } from '@/components/ui/badge';
import type { TemplateSection } from '@/api/proactive';
import { getSectionTypeDef } from '@/lib/template-section-types';

interface SectionPreviewProps {
  sections: TemplateSection[];
}

export function SectionPreview({ sections }: SectionPreviewProps) {
  if (sections.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
        <p>섹션이 없습니다.</p>
        <p className="text-xs mt-1">왼쪽 에디터에서 섹션을 추가해보세요.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {sections.map((section, index) => {
        const def = getSectionTypeDef(section.type);
        return (
          <div
            key={section.key || index}
            className={`p-3 bg-muted/30 rounded-md border-l-3 ${def?.color ?? 'border-l-muted-foreground'}`}
          >
            <div className="flex items-center justify-between">
              <div>
                <div className="text-sm font-medium">{section.label}</div>
                <div className="text-xs text-muted-foreground mt-0.5">
                  {def ? `${def.icon} ${def.type}` : section.type ?? 'unknown'}
                </div>
              </div>
              {section.required && (
                <Badge variant="secondary" className="text-[10px] h-5">필수</Badge>
              )}
            </div>
            {section.description && (
              <p className="text-xs text-muted-foreground mt-1">{section.description}</p>
            )}
          </div>
        );
      })}
      <div className="text-center text-xs text-muted-foreground pt-2">
        {sections.length}개 섹션
      </div>
    </div>
  );
}
