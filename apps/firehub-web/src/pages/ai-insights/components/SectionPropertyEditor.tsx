import { useId } from 'react';

import type { SectionType,TemplateSection } from '@/api/proactive';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { getSectionTypeDef,SECTION_TYPES } from '@/lib/template-section-types';

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
  // 접근성: 라벨↔입력 연결용 id 접두사 (#432).
  // 훅 규칙상 아래 early return 보다 반드시 위에서 호출해야 한다.
  const baseId = useId();

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
            <Label htmlFor={`${baseId}-label`} className="text-sm font-medium">Label</Label>
            <Input
              id={`${baseId}-label`}
              value={section.label}
              onChange={(e) => onUpdate({ label: e.target.value })}
            />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-key`} className="text-sm font-medium">Key</Label>
            {/* key 형식 오류 문구를 aria-describedby/aria-invalid 로 입력에 연결한다 (§J) */}
            <Input
              id={`${baseId}-key`}
              value={section.key}
              onChange={(e) => onUpdate({ key: e.target.value })}
              className="font-mono"
              aria-invalid={!isValidKey}
              aria-describedby={!isValidKey ? `${baseId}-key-error` : undefined}
            />
            {!isValidKey && (
              <p id={`${baseId}-key-error`} className="text-sm text-destructive">영문 소문자, 숫자, 밑줄만 사용 가능</p>
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
                <Label htmlFor={`${baseId}-type`} className="text-sm font-medium">타입</Label>
                <Select
                  value={section.type}
                  onValueChange={(value) => onUpdate({ type: value as SectionType })}
                >
                  {/* shadcn Select 는 루트가 아니라 SelectTrigger 가 실제 포커스 대상이라 id 를 여기 건다 */}
                  <SelectTrigger id={`${baseId}-type`}><SelectValue /></SelectTrigger>
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
                {/* aria-label 은 유지한다 — 접근 가능한 이름을 '필수 항목'으로 고정해 기존 계약을 지키고,
                    htmlFor/id 는 라벨 클릭으로 토글되도록 하는 용도다 (#432) */}
                <Switch
                  id={`${baseId}-required`}
                  checked={section.required !== false}
                  onCheckedChange={(checked) => onUpdate({ required: checked })}
                  aria-label="필수 항목"
                />
                <Label htmlFor={`${baseId}-required`} className="text-sm">필수</Label>
              </div>
            </div>
          </>
        )}

        <Separator />

        {/* AI Instruction (non-divider, non-static) */}
        {!isDivider && !isStatic && (
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-instruction`} className="text-sm font-medium">AI 지시 (Instruction)</Label>
            <Textarea
              id={`${baseId}-instruction`}
              value={section.instruction ?? ''}
              onChange={(e) => onUpdate({ instruction: e.target.value || undefined })}
              placeholder="이 섹션에서 AI가 분석할 내용을 지시하세요..."
              rows={4}
              className="resize-none"
              aria-describedby={`${baseId}-instruction-help`}
            />
            {/* 도움말 문구 — aria-describedby 로 Textarea 에 연결 (§J) */}
            <p id={`${baseId}-instruction-help`} className="text-xs text-muted-foreground">
              이 지시는 AI 프롬프트에 포함되어 섹션 내용 생성을 안내합니다.
            </p>
          </div>
        )}

        {/* Static content editor */}
        {isStatic && !isDivider && (
          <div className="space-y-1.5">
            <Label htmlFor={`${baseId}-content`} className="text-sm font-medium">고정 텍스트</Label>
            <Textarea
              id={`${baseId}-content`}
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
            <Label htmlFor={`${baseId}-description`} className="text-sm font-medium">UI 설명 (Description)</Label>
            <Input
              id={`${baseId}-description`}
              value={section.description ?? ''}
              onChange={(e) => onUpdate({ description: e.target.value || undefined })}
              placeholder="편집 화면에서 보이는 도움말 (AI에게 전달되지 않음)"
            />
          </div>
        )}

        {/* Type guide (non-divider, non-static, non-group) */}
        {!isDivider && !isStatic && !isGroup && typeDef && (
          <div className="space-y-1.5">
            {/* 아래는 입력이 아니라 정적 안내 블록이므로 label 이 아니라 span 이다 (#432) */}
            <span className="block text-sm leading-none font-medium">타입 가이드</span>
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
