import {
  Brain,
  ChevronDown,
  ChevronRight,
  MessageSquare,
  Plus,
  Settings2,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';

import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
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
import { Skeleton } from '@/components/ui/skeleton';
import { Textarea } from '@/components/ui/textarea';
import { useDataset } from '@/hooks/queries/useDatasets';
import type { AiClassifyConfig } from '@/types/pipeline';

const COLUMN_TYPES = ['TEXT', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'DATE', 'TIMESTAMP'];

const PRESETS = [
  {
    label: '감성 분석',
    icon: '😊',
    prompt: '각 행의 텍스트를 감성 분류하세요. 긍정, 중립, 부정 중 하나로 분류하고 신뢰도와 이유를 함께 제공하세요.',
    outputColumns: [
      { name: 'label', type: 'TEXT' },
      { name: 'confidence', type: 'DECIMAL' },
      { name: 'reason', type: 'TEXT' },
    ],
  },
  {
    label: '키워드 추출',
    icon: '🔑',
    prompt: '각 행에서 핵심 키워드 3개를 추출하세요. 쉼표로 구분된 문자열로 반환하세요.',
    outputColumns: [{ name: 'keywords', type: 'TEXT' }],
  },
  {
    label: '요약',
    icon: '📝',
    prompt: '각 행의 내용을 2문장으로 요약하세요. 핵심 내용을 간결하게 담아주세요.',
    outputColumns: [{ name: 'summary', type: 'TEXT' }],
  },
];

interface AiClassifyStepConfigProps {
  aiConfig: AiClassifyConfig;
  inputDatasetIds: number[];
  onChange: (config: AiClassifyConfig) => void;
  readOnly: boolean;
}

interface CollapsibleSectionProps {
  title: string;
  icon?: React.ReactNode;
  defaultOpen?: boolean;
  children: React.ReactNode;
  accentClassName?: string;
}

function CollapsibleSection({
  title,
  icon,
  defaultOpen = false,
  children,
  accentClassName,
}: CollapsibleSectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <button
          className={`flex w-full items-center justify-between py-1.5 text-sm font-medium hover:text-foreground/80 transition-colors${accentClassName ? ` ${accentClassName}` : ''}`}
        >
          <span className="flex items-center gap-1.5">
            {icon}
            {title}
          </span>
          {open ? (
            <ChevronDown className="h-3.5 w-3.5 text-muted-foreground" />
          ) : (
            <ChevronRight className="h-3.5 w-3.5 text-muted-foreground" />
          )}
        </button>
      </CollapsibleTrigger>
      <CollapsibleContent>
        <div className="pb-2 space-y-2">{children}</div>
      </CollapsibleContent>
    </Collapsible>
  );
}

export default function AiClassifyStepConfig({
  aiConfig,
  inputDatasetIds,
  onChange,
  readOnly,
}: AiClassifyStepConfigProps) {
  const primaryDatasetId = inputDatasetIds[0] ?? 0;
  const { data: datasetDetail, isLoading: columnsLoading } = useDataset(primaryDatasetId);
  const columns = datasetDetail?.columns ?? [];

  const prompt = aiConfig.prompt ?? '';
  const outputColumns = aiConfig.outputColumns ?? [];
  const inputColumns = aiConfig.inputColumns ?? [];
  const batchSize = aiConfig.batchSize ?? 20;
  const onError = aiConfig.onError ?? 'CONTINUE';

  const update = <K extends keyof AiClassifyConfig>(key: K, value: AiClassifyConfig[K]) => {
    onChange({ ...aiConfig, [key]: value });
  };

  const applyPreset = (preset: (typeof PRESETS)[number]) => {
    onChange({
      ...aiConfig,
      prompt: preset.prompt,
      outputColumns: preset.outputColumns,
    });
  };

  // Output column helpers
  const addOutputColumn = () => {
    update('outputColumns', [...outputColumns, { name: '', type: 'TEXT' }]);
  };

  const removeOutputColumn = (i: number) => {
    update('outputColumns', outputColumns.filter((_, idx) => idx !== i));
  };

  const updateOutputColumn = (i: number, field: 'name' | 'type', value: string) => {
    const next = outputColumns.map((col, idx) =>
      idx === i ? { ...col, [field]: value } : col,
    );
    update('outputColumns', next);
  };

  // Input column filter helpers
  const toggleInputColumn = (colName: string) => {
    const current = inputColumns;
    if (current.includes(colName)) {
      update('inputColumns', current.filter((c) => c !== colName));
    } else {
      update('inputColumns', [...current, colName]);
    }
  };

  return (
    <div className="space-y-3">
      {/* Header banner */}
      <div
        className="flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium bg-ai-accent-subtle text-ai-accent border border-ai-accent/20"
      >
        <Brain className="h-4 w-4 shrink-0" />
        <span>AI 분류 스텝</span>
      </div>

      {/* === 1. 프롬프트 (메인 영역) === */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5 text-sm font-medium text-ai-accent">
          <MessageSquare className="h-3.5 w-3.5" />
          프롬프트 <span className="text-destructive ml-0.5">*</span>
        </div>

        {/* 프리셋 버튼 */}
        {!readOnly && (
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className="text-xs text-muted-foreground">프리셋:</span>
            {PRESETS.map((preset) => (
              <Button
                key={preset.label}
                variant="outline"
                size="sm"
                className="h-7 text-xs px-2.5 border-ai-accent/30 text-ai-accent hover:bg-ai-accent-subtle hover:border-ai-accent/40 gap-1"
                onClick={() => applyPreset(preset)}
              >
                <Sparkles className="h-3 w-3" />
                {preset.label}
              </Button>
            ))}
          </div>
        )}

        <Textarea
          className="text-xs min-h-[100px] resize-y leading-relaxed"
          placeholder="입력 데이터의 어떤 컬럼을 어떻게 처리할지 설명하세요"
          value={prompt}
          disabled={readOnly}
          onChange={(e) => update('prompt', e.target.value)}
          rows={4}
        />
      </div>

      <Separator />

      {/* === 2. 출력 컬럼 정의 === */}
      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <div className="text-sm font-medium text-ai-accent">
            출력 컬럼 정의 <span className="text-destructive">*</span>
          </div>
          {!readOnly && (
            <Button
              variant="outline"
              size="sm"
              className="h-7 text-xs px-2 border-ai-accent/30 text-ai-accent hover:bg-ai-accent-subtle"
              onClick={addOutputColumn}
            >
              <Plus className="h-3 w-3" />
              컬럼 추가
            </Button>
          )}
        </div>

        <p className="text-xs text-muted-foreground">
          <code className="bg-muted px-1 rounded text-xs">source_id (INTEGER)</code>는 자동으로
          추가됩니다
        </p>

        {outputColumns.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-4 border border-dashed border-ai-accent/20 rounded-md text-xs text-muted-foreground gap-1">
            <Plus className="h-4 w-4 text-ai-accent" />
            <span>출력 컬럼을 추가하거나 프리셋을 선택하세요</span>
          </div>
        ) : (
          <div className="space-y-1.5">
            <div className="grid grid-cols-[1fr_140px_auto] gap-1.5 text-xs text-muted-foreground px-0.5">
              <span>컬럼명</span>
              <span>타입</span>
              <span></span>
            </div>
            {outputColumns.map((col, i) => (
              <div key={i} className="grid grid-cols-[1fr_140px_auto] gap-1.5 items-center">
                <Input
                  className="h-7 text-xs font-mono"
                  placeholder="column_name"
                  value={col.name}
                  disabled={readOnly}
                  pattern="[a-z][a-z0-9_]*"
                  onChange={(e) => updateOutputColumn(i, 'name', e.target.value)}
                />
                <Select
                  value={col.type}
                  disabled={readOnly}
                  onValueChange={(v) => updateOutputColumn(i, 'type', v)}
                >
                  <SelectTrigger className="h-7 text-xs w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {COLUMN_TYPES.map((t) => (
                      <SelectItem key={t} value={t}>
                        {t}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {!readOnly && (
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-7 w-7 shrink-0 text-muted-foreground hover:text-destructive"
                    onClick={() => removeOutputColumn(i)}
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </div>

      <Separator />

      {/* === 3. 입력 컬럼 필터 (Collapsible, 기본 접힘) === */}
      <CollapsibleSection
        title="입력 컬럼 필터 (선택)"
        icon={<Brain className="h-3.5 w-3.5" />}
        defaultOpen={false}
        accentClassName="text-ai-accent"
      >
        {inputDatasetIds.length === 0 ? (
          <p className="text-xs text-warning">입력 데이터셋을 먼저 선택하세요</p>
        ) : columnsLoading ? (
          <div className="space-y-1.5">
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-5 w-3/4" />
          </div>
        ) : columns.length === 0 ? (
          <p className="text-xs text-muted-foreground">데이터셋에 컬럼이 없습니다</p>
        ) : (
          <>
            <div className="space-y-1.5 max-h-[180px] overflow-y-auto pr-1">
              {columns.map((col) => (
                <div key={col.columnName} className="flex items-center gap-2">
                  <Checkbox
                    id={`col-${col.columnName}`}
                    checked={inputColumns.includes(col.columnName)}
                    disabled={readOnly}
                    onCheckedChange={() => toggleInputColumn(col.columnName)}
                    className="h-3.5 w-3.5 border-ai-accent/30 data-[state=checked]:bg-ai-accent data-[state=checked]:border-ai-accent"
                  />
                  <label
                    htmlFor={`col-${col.columnName}`}
                    className="text-xs font-mono cursor-pointer select-none flex-1"
                  >
                    {col.columnName}
                    <span className="ml-1.5 text-muted-foreground">({col.dataType})</span>
                  </label>
                </div>
              ))}
            </div>
            <p className="text-xs text-muted-foreground">
              {inputColumns.length === 0
                ? '전체 컬럼이 LLM에 전달됩니다'
                : `선택된 ${inputColumns.length}개 컬럼만 LLM에 전달됩니다`}
            </p>
          </>
        )}
      </CollapsibleSection>

      <Separator />

      {/* === 4. 고급 설정 (Collapsible, 기본 접힘) === */}
      <CollapsibleSection
        title="고급 설정"
        icon={<Settings2 className="h-3.5 w-3.5" />}
        defaultOpen={false}
        accentClassName="text-ai-accent"
      >
        <div className="space-y-2">
          <div className="space-y-1.5">
            <Label className="text-xs">배치 크기 (1~100)</Label>
            <Input
              className="h-8 text-xs"
              type="number"
              min={1}
              max={100}
              value={batchSize}
              disabled={readOnly}
              onChange={(e) => {
                const v = Math.max(1, Math.min(100, Number(e.target.value)));
                update('batchSize', v);
              }}
            />
            <p className="text-xs text-muted-foreground">한 번에 처리할 행 수 (기본값: 20)</p>
          </div>

          <div className="space-y-1.5">
            <Label className="text-xs">오류 처리</Label>
            <Select
              value={onError}
              disabled={readOnly}
              onValueChange={(v) => update('onError', v as AiClassifyConfig['onError'])}
            >
              <SelectTrigger className="h-8 text-xs w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="CONTINUE">건너뛰기 (CONTINUE)</SelectItem>
                <SelectItem value="RETRY_BATCH">배치 재시도 (RETRY_BATCH)</SelectItem>
                <SelectItem value="FAIL_STEP">스텝 실패 (FAIL_STEP)</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </CollapsibleSection>
    </div>
  );
}
