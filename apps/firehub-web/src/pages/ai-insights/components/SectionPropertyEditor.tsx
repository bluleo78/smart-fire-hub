import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Switch } from '@/components/ui/switch';
import { Separator } from '@/components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Button } from '@/components/ui/button';
import type { TemplateSection, SectionType } from '@/api/proactive';
import { SECTION_TYPES, getSectionTypeDef } from '@/lib/template-section-types';

const TEMPLATE_VARIABLES = [
  { key: 'date', label: '실행 일시' },
  { key: 'jobName', label: '작업 이름' },
  { key: 'author', label: '작성자' },
  { key: 'templateName', label: '템플릿 이름' },
  { key: 'period', label: '분석 기간' },
];

interface SectionPropertyEditorProps {
  section: TemplateSection | null;
  onUpdate: (patch: Partial<TemplateSection>) => void;
}

export function SectionPropertyEditor({ section, onUpdate }: SectionPropertyEditorProps) {
  if (!section) {
    return (
      <Card>
        <CardContent className="flex flex-col items-center justify-center py-16 text-muted-foreground text-sm">
          <p>섹션을 선택하세요</p>
        </CardContent>
      </Card>
    );
  }

  const typeDef = getSectionTypeDef(section.type);
  const isStatic = section.static || section.type === 'divider';
  const isGroup = section.type === 'group';
  const isDivider = section.type === 'divider';

  // Key validation: snake_case
  const isValidKey = /^[a-z][a-z0-9_]*$/.test(section.key);

  return (
    <Card>
      <CardContent className="pt-6 space-y-6">
        {/* Section key display */}
        <div className="font-mono text-xs text-muted-foreground">key: {section.key}</div>

        {/* Label + Key fields */}
        <div className="grid grid-cols-2 gap-3">
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">Label</Label>
            <Input
              value={section.label}
              onChange={(e) => onUpdate({ label: e.target.value })}
            />
          </div>
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">Key</Label>
            <Input
              value={section.key}
              onChange={(e) => onUpdate({ key: e.target.value })}
              className="font-mono"
            />
            {!isValidKey && (
              <p className="text-sm text-destructive">영문 소문자, 숫자, 밑줄만 사용 가능</p>
            )}
          </div>
        </div>

        {/* Divider: minimal */}
        {isDivider && (
          <p className="text-xs text-muted-foreground">구분선은 섹션 간 시각적 구분을 위해 사용됩니다.</p>
        )}

        {/* Type + Required (non-divider, non-static) */}
        {!isDivider && !isStatic && !isGroup && (
          <>
            <div className="grid grid-cols-2 gap-3 items-end">
              <div className="space-y-1.5">
                <Label className="text-sm font-medium">타입</Label>
                <Select
                  value={section.type}
                  onValueChange={(value) => onUpdate({ type: value as SectionType })}
                >
                  <SelectTrigger><SelectValue /></SelectTrigger>
                  <SelectContent>
                    {SECTION_TYPES
                      .filter(t => t.type !== 'group' && t.type !== 'divider')
                      .map(t => (
                        <SelectItem key={t.type} value={t.type}>
                          {t.icon} {t.label}
                        </SelectItem>
                      ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="flex items-center gap-2 pb-1">
                <Switch
                  checked={section.required !== false}
                  onCheckedChange={(checked) => onUpdate({ required: checked })}
                  aria-label="필수 항목"
                />
                <Label className="text-sm">필수</Label>
              </div>
            </div>
          </>
        )}

        <Separator />

        {/* AI Instruction (non-divider, non-static) */}
        {!isDivider && !isStatic && (
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">AI 지시 (Instruction)</Label>
            <Textarea
              value={section.instruction ?? ''}
              onChange={(e) => onUpdate({ instruction: e.target.value || undefined })}
              placeholder="이 섹션에서 AI가 분석할 내용을 지시하세요..."
              rows={4}
              className="resize-none"
            />
            <p className="text-xs text-muted-foreground">
              이 지시는 AI 프롬프트에 포함되어 섹션 내용 생성을 안내합니다.
            </p>
          </div>
        )}

        {/* Static content editor */}
        {isStatic && !isDivider && (
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">고정 텍스트</Label>
            <Textarea
              value={section.content ?? ''}
              onChange={(e) => onUpdate({ content: e.target.value || undefined })}
              placeholder="고정 텍스트를 입력하세요. 변수를 사용할 수 있습니다."
              rows={4}
              className="resize-none"
            />
            <div className="space-y-1">
              <p className="text-xs text-muted-foreground">사용 가능한 변수</p>
              <div className="flex flex-wrap gap-1">
                {TEMPLATE_VARIABLES.map(v => (
                  <Button
                    key={v.key}
                    variant="ghost"
                    size="sm"
                    className="h-6 px-2 text-xs font-mono"
                    onClick={() => {
                      const current = section.content ?? '';
                      onUpdate({ content: current + `{{${v.key}}}` });
                    }}
                    title={v.label}
                  >
                    {`{{${v.key}}}`}
                  </Button>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Description (non-divider) */}
        {!isDivider && !isGroup && !isStatic && (
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">UI 설명 (Description)</Label>
            <Input
              value={section.description ?? ''}
              onChange={(e) => onUpdate({ description: e.target.value || undefined })}
              placeholder="편집 화면에서 보이는 도움말 (AI에게 전달되지 않음)"
            />
          </div>
        )}

        {/* Type guide (non-divider, non-static, non-group) */}
        {!isDivider && !isStatic && !isGroup && typeDef && (
          <div className="space-y-1.5">
            <Label className="text-sm font-medium">타입 가이드</Label>
            <div className="bg-muted/40 rounded-md p-3 text-xs text-muted-foreground">
              <span className="font-medium" style={{ color: 'var(--primary)' }}>
                {typeDef.icon} {typeDef.label}
              </span>
              {' — '}
              {typeDef.description}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
