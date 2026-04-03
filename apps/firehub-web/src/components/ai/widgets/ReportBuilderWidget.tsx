import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { Pencil, Save } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import { Badge } from '@/components/ui/badge';
import { Separator } from '@/components/ui/separator';
import { WidgetShell } from './WidgetShell';
import type { WidgetProps } from './types';

interface ReportSection {
  key: string;
  label: string;
  type: string;
  instruction?: string;
  required?: boolean;
}

interface ReportBuilderInput {
  title: string;
  question: string;
  templateStructure: {
    sections: ReportSection[];
    output_format?: string;
  };
  sectionContents: Record<string, string>;
  style?: string;
}

export default function ReportBuilderWidget({
  input,
  onNavigate,
  displayMode,
}: WidgetProps<ReportBuilderInput>) {
  const { title, question, templateStructure, sectionContents } = input;
  const sections = templateStructure?.sections ?? [];

  const maxH =
    displayMode === 'fullscreen' || displayMode === 'native'
      ? 'max-h-[450px]'
      : 'max-h-[250px]';

  return (
    <WidgetShell
      title={title}
      icon="📝"
      subtitle="AI 리포트"
      displayMode={displayMode}
      onNavigate={onNavigate}
    >
      <div className="flex flex-col gap-2 p-3">
        {/* Original question */}
        <p className="text-xs text-muted-foreground italic">"{question}"</p>

        <Separator />

        {/* Section contents */}
        <ScrollArea className={maxH}>
          <div className="space-y-3 pr-2">
            {sections.map((section) => {
              const content = sectionContents?.[section.key];
              return (
                <div key={section.key} className="space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium">{section.label}</span>
                    <Badge variant="outline" className="text-[10px]">
                      {section.type}
                    </Badge>
                    {section.required && (
                      <span className="h-1.5 w-1.5 rounded-full bg-amber-500" />
                    )}
                  </div>
                  {content ? (
                    <div className="prose prose-sm dark:prose-invert max-w-none text-sm">
                      <ReactMarkdown remarkPlugins={[remarkGfm]}>
                        {content}
                      </ReactMarkdown>
                    </div>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      (내용 없음)
                    </p>
                  )}
                </div>
              );
            })}
          </div>
        </ScrollArea>

        <Separator />

        {/* Action buttons */}
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            className="flex-1"
            onClick={() => onNavigate?.('/ai-insights/templates/new')}
          >
            <Pencil className="mr-1 h-3.5 w-3.5" />
            편집하기
          </Button>
          <Button
            variant="default"
            size="sm"
            className="flex-1"
            onClick={() => onNavigate?.('/ai-insights/jobs/new')}
          >
            <Save className="mr-1 h-3.5 w-3.5" />
            스마트 작업으로 저장
          </Button>
        </div>
      </div>
    </WidgetShell>
  );
}
