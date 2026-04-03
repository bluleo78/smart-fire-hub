import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import type { TemplateSection } from '@/api/proactive';
import { getSectionTypeDef } from '@/lib/template-section-types';

const SAMPLE_VARIABLES: Record<string, string> = {
  date: new Date().toISOString().slice(0, 16).replace('T', ' '),
  jobName: '(작업 이름)',
  author: '(작성자)',
  templateName: '(템플릿 이름)',
  period: '(분석 기간)',
};

function substituteVariables(content: string): string {
  return content.replace(/\{\{(\w+)\}\}/g, (_, key) => SAMPLE_VARIABLES[key] ?? `{{${key}}}`);
}

function renderSection(section: TemplateSection, index: number, depth = 0) {
  const def = getSectionTypeDef(section.type);
  const indent = depth * 16;

  // Divider
  if (section.type === 'divider') {
    return <Separator key={section.key || index} className="my-2" style={{ marginLeft: indent }} />;
  }

  // Group — render header + recurse children
  if (section.type === 'group') {
    return (
      <div key={section.key || index} style={{ marginLeft: indent }}>
        <div className="flex items-center gap-2 py-1 font-semibold text-sm">
          <span>{def?.icon}</span>
          <span>{section.label}</span>
          <Badge variant="outline" className="text-[10px]">group</Badge>
        </div>
        {section.children?.map((child, i) => renderSection(child, i, depth + 1))}
      </div>
    );
  }

  // Regular section
  return (
    <div
      key={section.key || index}
      className={`flex flex-col gap-1 py-1.5 px-2 rounded border-l-3 ${def?.color ?? 'border-l-gray-500'} ${section.static ? 'text-muted-foreground' : ''}`}
      style={{ marginLeft: indent }}
    >
      <div className="flex items-center gap-2">
        <span>{def?.icon}</span>
        <span className="text-sm flex-1">{section.label}</span>
        {section.required && <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />}
        {section.static && <Badge variant="secondary" className="text-[10px]">정적</Badge>}
        <Badge variant="outline" className="text-[10px] ml-auto">{section.type}</Badge>
      </div>
      {section.static && section.content && (
        <p className="text-xs text-muted-foreground pl-6 whitespace-pre-line">
          {substituteVariables(section.content)}
        </p>
      )}
    </div>
  );
}

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
      {sections.map((section, index) => renderSection(section, index))}
      <div className="text-center text-xs text-muted-foreground pt-2">
        {sections.length}개 섹션
      </div>
    </div>
  );
}
