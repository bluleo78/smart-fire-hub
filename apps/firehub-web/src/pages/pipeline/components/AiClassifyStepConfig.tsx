import {
  Brain,
  ChevronDown,
  ChevronRight,
  Plus,
  Settings2,
  Sparkles,
  Trash2,
} from 'lucide-react';
import { useState } from 'react';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
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

const DEFAULT_PROMPT_TEMPLATE =
  'Classify the following text into exactly one of the allowed labels: {labels}.\n\nText: {text}\n\nRespond with a JSON object containing:\n- "label": one of the allowed labels\n- "confidence": a number between 0.0 and 1.0\n- "reason": a brief explanation (optional)';

interface AiClassifyStepConfigProps {
  aiConfig: AiClassifyConfig;
  inputDatasetIds: number[];
  onChange: (config: AiClassifyConfig) => void;
  readOnly: boolean;
}

interface SectionProps {
  title: string;
  icon?: React.ReactNode;
  defaultOpen?: boolean;
  children: React.ReactNode;
  accentColor?: string;
}

function Section({ title, icon, defaultOpen = false, children, accentColor }: SectionProps) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <Collapsible open={open} onOpenChange={setOpen}>
      <CollapsibleTrigger asChild>
        <button
          className="flex w-full items-center justify-between py-1.5 text-sm font-medium hover:text-foreground/80 transition-colors"
          style={accentColor ? { color: accentColor } : undefined}
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

function ColumnCombobox({
  label,
  value,
  columns,
  placeholder,
  readOnly,
  onChange,
  required,
}: {
  label: string;
  value: string;
  columns: Array<{ columnName: string; dataType: string }>;
  placeholder: string;
  readOnly: boolean;
  onChange: (v: string) => void;
  required?: boolean;
}) {
  return (
    <div className="space-y-1.5">
      <Label className="text-xs">
        {label} {required && <span className="text-destructive">*</span>}
      </Label>
      <Select value={value || ''} disabled={readOnly} onValueChange={onChange}>
        <SelectTrigger className="h-8 text-xs w-full">
          <SelectValue placeholder={placeholder} />
        </SelectTrigger>
        <SelectContent>
          {columns.length === 0 ? (
            <div className="px-2 py-1.5 text-xs text-muted-foreground">
              입력 데이터셋을 먼저 선택하세요
            </div>
          ) : (
            columns.map((col) => (
              <SelectItem key={col.columnName} value={col.columnName}>
                <span>{col.columnName}</span>
                <span className="ml-1.5 text-muted-foreground text-xs">({col.dataType})</span>
              </SelectItem>
            ))
          )}
        </SelectContent>
      </Select>
    </div>
  );
}

function LabelTagInput({
  labels,
  readOnly,
  onChange,
}: {
  labels: string[];
  readOnly: boolean;
  onChange: (labels: string[]) => void;
}) {
  const [inputValue, setInputValue] = useState('');

  const addLabel = (value: string) => {
    const trimmed = value.trim();
    if (trimmed && !labels.includes(trimmed)) {
      onChange([...labels, trimmed]);
    }
    setInputValue('');
  };

  const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter' || e.key === ',') {
      e.preventDefault();
      addLabel(inputValue);
    } else if (e.key === 'Backspace' && !inputValue && labels.length > 0) {
      onChange(labels.slice(0, -1));
    }
  };

  const removeLabel = (label: string) => {
    onChange(labels.filter((l) => l !== label));
  };

  return (
    <div className="space-y-2">
      {/* Presets */}
      {!readOnly && (
        <div className="flex items-center gap-1.5 flex-wrap">
          <span className="text-xs text-muted-foreground">프리셋:</span>
          <Button
            variant="outline"
            size="sm"
            className="h-6 text-xs px-2 border-violet-300 text-violet-700 hover:bg-violet-50 dark:border-violet-700 dark:text-violet-400 dark:hover:bg-violet-950"
            onClick={() => onChange(['positive', 'neutral', 'negative'])}
          >
            <Sparkles className="h-3 w-3 mr-1" />
            감성 분석
          </Button>
        </div>
      )}

      {/* Tag display */}
      <div className="min-h-[36px] flex flex-wrap gap-1 p-1.5 border rounded-md bg-background">
        {labels.map((label) => (
          <Badge
            key={label}
            variant="secondary"
            className="h-5 text-xs gap-1 bg-violet-100 text-violet-800 dark:bg-violet-900 dark:text-violet-200"
          >
            {label}
            {!readOnly && (
              <button
                onClick={() => removeLabel(label)}
                className="hover:text-destructive ml-0.5"
              >
                <Trash2 className="h-2.5 w-2.5" />
              </button>
            )}
          </Badge>
        ))}
        {!readOnly && (
          <input
            className="flex-1 min-w-[80px] text-xs outline-none bg-transparent placeholder:text-muted-foreground"
            placeholder={labels.length === 0 ? '라벨 입력 후 Enter...' : '추가...'}
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            onKeyDown={handleKeyDown}
            onBlur={() => { if (inputValue) addLabel(inputValue); }}
          />
        )}
      </div>
      <p className="text-xs text-muted-foreground">
        Enter 또는 쉼표로 라벨을 추가하세요. 최소 2개 필요.
      </p>
    </div>
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

  const update = <K extends keyof AiClassifyConfig>(key: K, value: AiClassifyConfig[K]) => {
    onChange({ ...aiConfig, [key]: value });
  };

  const sourceColumn = aiConfig.sourceColumn ?? '';
  const keyColumn = aiConfig.keyColumn ?? '';
  const labels = aiConfig.labels ?? [];
  const promptTemplate = aiConfig.promptTemplate ?? DEFAULT_PROMPT_TEMPLATE;
  const targetPrefix = aiConfig.targetPrefix ?? 'ai_';
  const batchSize = aiConfig.batchSize ?? 20;
  const confidenceThreshold = aiConfig.confidenceThreshold ?? 0.7;
  const onLowConfidence = aiConfig.onLowConfidence ?? 'MARK_UNKNOWN';
  const onError = aiConfig.onError ?? 'CONTINUE';

  return (
    <div className="space-y-3">
      {/* Header banner */}
      <div
        className="flex items-center gap-2 px-3 py-2 rounded-md text-sm font-medium"
        style={{
          background: 'rgba(124,58,237,0.06)',
          color: '#7c3aed',
          border: '1px solid rgba(124,58,237,0.2)',
        }}
      >
        <Brain className="h-4 w-4 shrink-0" />
        <span>AI 분류 스텝</span>
      </div>

      {/* Column selection */}
      <div className="space-y-2">
        <div className="flex items-center gap-1.5 text-sm font-medium" style={{ color: '#7c3aed' }}>
          <Brain className="h-3.5 w-3.5" />
          입력 컬럼 설정
        </div>

        {columnsLoading ? (
          <div className="space-y-1.5">
            <Skeleton className="h-8 w-full" />
            <Skeleton className="h-8 w-full" />
          </div>
        ) : (
          <div className="grid grid-cols-2 gap-2">
            <ColumnCombobox
              label="소스 컬럼"
              value={sourceColumn}
              columns={columns}
              placeholder="텍스트 컬럼 선택"
              readOnly={readOnly}
              onChange={(v) => update('sourceColumn', v)}
              required
            />
            <ColumnCombobox
              label="키 컬럼"
              value={keyColumn}
              columns={columns}
              placeholder="키 컬럼 선택"
              readOnly={readOnly}
              onChange={(v) => update('keyColumn', v)}
              required
            />
          </div>
        )}
        {!columnsLoading && inputDatasetIds.length === 0 && (
          <p className="text-xs text-amber-600">
            입력 데이터셋을 먼저 선택하면 컬럼 목록이 표시됩니다
          </p>
        )}
      </div>

      <Separator />

      {/* Label set */}
      <div className="space-y-1.5">
        <Label className="text-xs font-medium">
          라벨 세트 <span className="text-destructive">*</span>
        </Label>
        <LabelTagInput labels={labels} readOnly={readOnly} onChange={(v) => update('labels', v)} />
      </div>

      <Separator />

      {/* Output settings */}
      <div className="space-y-2">
        <div className="text-sm font-medium" style={{ color: '#7c3aed' }}>
          출력 설정
        </div>
        <div className="space-y-1.5">
          <Label className="text-xs">결과 컬럼 접두사</Label>
          <Input
            className="h-8 text-xs"
            placeholder="ai_"
            value={targetPrefix}
            disabled={readOnly}
            onChange={(e) => update('targetPrefix', e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            출력 컬럼명: {targetPrefix}label, {targetPrefix}confidence, {targetPrefix}reason, {targetPrefix}classified_at
          </p>
        </div>
      </div>

      <Separator />

      {/* Confidence & error handling */}
      <div className="space-y-3">
        <div className="text-sm font-medium" style={{ color: '#7c3aed' }}>
          신뢰도 및 오류 처리
        </div>

        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <Label className="text-xs">신뢰도 임계값</Label>
            <span className="text-xs font-mono text-violet-700">
              {confidenceThreshold.toFixed(2)}
            </span>
          </div>
          <input
            type="range"
            min={0}
            max={1}
            step={0.05}
            value={confidenceThreshold}
            disabled={readOnly}
            onChange={(e) => update('confidenceThreshold', Number(e.target.value))}
            className="w-full accent-violet-600"
          />
          <div className="flex justify-between text-xs text-muted-foreground">
            <span>0.0</span>
            <span>1.0</span>
          </div>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <div className="space-y-1.5">
            <Label className="text-xs">임계값 미달 처리</Label>
            <Select
              value={onLowConfidence}
              disabled={readOnly}
              onValueChange={(v) =>
                update('onLowConfidence', v as AiClassifyConfig['onLowConfidence'])
              }
            >
              <SelectTrigger className="h-8 text-xs w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="MARK_UNKNOWN">UNKNOWN 표시</SelectItem>
                <SelectItem value="KEEP_BEST_LABEL">최고 점수 유지</SelectItem>
                <SelectItem value="FAIL_STEP">스텝 실패</SelectItem>
              </SelectContent>
            </Select>
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
                <SelectItem value="CONTINUE">건너뛰기</SelectItem>
                <SelectItem value="RETRY_BATCH">배치 재시도</SelectItem>
                <SelectItem value="FAIL_STEP">스텝 실패</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </div>

      <Separator />

      {/* Advanced settings */}
      <Section
        title="고급 설정"
        icon={<Settings2 className="h-3.5 w-3.5" />}
        defaultOpen={false}
        accentColor="#7c3aed"
      >
        <div className="space-y-1.5">
          <Label className="text-xs">배치 크기</Label>
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
          <p className="text-xs text-muted-foreground">한 번에 처리할 행 수 (1~100)</p>
        </div>

        <div className="space-y-1.5">
          <div className="flex items-center justify-between">
            <Label className="text-xs">프롬프트 템플릿</Label>
            <button
              className="text-xs text-violet-600 hover:text-violet-800 underline"
              onClick={() => update('promptTemplate', DEFAULT_PROMPT_TEMPLATE)}
              disabled={readOnly}
            >
              기본값 복원
            </button>
          </div>
          <Textarea
            className="text-xs font-mono min-h-[120px] resize-y"
            placeholder={DEFAULT_PROMPT_TEMPLATE}
            value={promptTemplate}
            disabled={readOnly}
            onChange={(e) => update('promptTemplate', e.target.value)}
          />
          <p className="text-xs text-muted-foreground">
            <code className="bg-muted px-1 rounded">{'{labels}'}</code>,{' '}
            <code className="bg-muted px-1 rounded">{'{text}'}</code> 플레이스홀더를 사용하세요
          </p>
        </div>
      </Section>

      {/* Output column preview */}
      {(keyColumn || targetPrefix) && (
        <>
          <Separator />
          <div className="space-y-1.5">
            <Label className="text-xs text-muted-foreground">출력 데이터셋 필요 컬럼</Label>
            <div className="grid grid-cols-2 gap-1">
              {[
                keyColumn || '(키 컬럼)',
                `${targetPrefix}label`,
                `${targetPrefix}confidence`,
                `${targetPrefix}reason`,
                `${targetPrefix}classified_at`,
              ].map((col) => (
                <div
                  key={col}
                  className="flex items-center gap-1 px-2 py-1 rounded text-xs bg-violet-50 text-violet-800 dark:bg-violet-950 dark:text-violet-200 font-mono"
                >
                  <Plus className="h-2.5 w-2.5 shrink-0" />
                  {col}
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}
