import { useState } from 'react';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import type { TemplateSection } from '@/api/proactive';
import { SECTION_TYPES } from '@/lib/template-section-types';

import { SectionPreview } from './SectionPreview';

interface TemplateSidePanelProps {
  jsonValue: string;
}

function GuideTab() {
  const [expandedType, setExpandedType] = useState<string | null>(null);

  return (
    <div className="space-y-2">
      {SECTION_TYPES.map((st) => (
        <div
          key={st.type}
          className="p-3 bg-muted/30 rounded-md border border-border cursor-pointer hover:bg-muted/50 transition-colors"
          onClick={() => setExpandedType(expandedType === st.type ? null : st.type)}
        >
          <div className="flex items-center justify-between">
            <div className="font-medium text-sm">
              {st.icon} {st.label}
            </div>
          </div>
          <p className="text-xs text-muted-foreground mt-1">{st.description}</p>
          {expandedType === st.type && (
            <pre className="mt-2 p-2 bg-muted rounded text-xs font-mono overflow-x-auto">
              {JSON.stringify(st.snippet, null, 2)}
            </pre>
          )}
        </div>
      ))}
    </div>
  );
}

function parseSections(jsonValue: string): TemplateSection[] | null {
  try {
    const parsed = JSON.parse(jsonValue);
    if (Array.isArray(parsed?.sections)) {
      return parsed.sections as TemplateSection[];
    }
    return [];
  } catch {
    return null;
  }
}

export function TemplateSidePanel({ jsonValue }: TemplateSidePanelProps) {
  const sections = parseSections(jsonValue);

  return (
    <Tabs defaultValue="guide" className="h-full flex flex-col">
      <TabsList className="w-full grid grid-cols-2 shrink-0">
        <TabsTrigger value="guide">가이드</TabsTrigger>
        <TabsTrigger value="preview">미리보기</TabsTrigger>
      </TabsList>
      <TabsContent value="guide" className="flex-1 overflow-auto mt-4">
        <GuideTab />
      </TabsContent>
      <TabsContent value="preview" className="flex-1 overflow-auto mt-4">
        {sections === null ? (
          <div className="flex flex-col items-center justify-center py-12 text-muted-foreground text-sm">
            <p>JSON 형식이 올바르지 않습니다.</p>
            <p className="text-xs mt-1">에디터에서 수정해주세요.</p>
          </div>
        ) : (
          <SectionPreview sections={sections} />
        )}
      </TabsContent>
    </Tabs>
  );
}
