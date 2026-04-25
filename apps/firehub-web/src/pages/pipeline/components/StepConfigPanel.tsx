import { Trash2,X } from 'lucide-react';
import { lazy, Suspense, useMemo, useRef } from 'react';

const ApiCallStepConfig = lazy(() => import('./ApiCallStepConfig'));
const AiClassifyStepConfig = lazy(() => import('./AiClassifyStepConfig'));
const PythonOutputColumns = lazy(() => import('./PythonOutputColumns'));
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select';
import { Separator } from '@/components/ui/separator';
import { Skeleton } from '@/components/ui/skeleton';
import { Switch } from '@/components/ui/switch';
import { Textarea } from '@/components/ui/textarea';
import { formatDate } from '@/lib/formatters';

import type { EditorAction, EditorStep,PipelineEditorState } from '../hooks/usePipelineEditor';
import DatasetCombobox from './DatasetCombobox';

const ScriptEditor = lazy(() => import('./ScriptEditor'));

interface DatasetOption {
  id: number;
  name: string;
  tableName: string;
}

interface PipelineInfo {
  createdBy?: string;
  createdAt?: string;
  updatedBy?: string | null;
  updatedAt?: string | null;
}

interface StepConfigPanelProps {
  state: PipelineEditorState;
  dispatch: React.Dispatch<EditorAction>;
  readOnly: boolean;
  datasets: DatasetOption[];
  pipelineInfo?: PipelineInfo;
}

export default function StepConfigPanel({
  state,
  dispatch,
  readOnly,
  datasets,
  pipelineInfo,
}: StepConfigPanelProps) {
  const step = state.selectedStepId
    ? state.steps.find((s) => s.tempId === state.selectedStepId) ?? null
    : null;
  const stepIndex = step ? state.steps.findIndex((s) => s.tempId === step.tempId) : -1;
  const stepNumber = stepIndex + 1;

  // 훅은 early return 이전에 호출되어야 한다 (rules-of-hooks). step이 없을 때는 fallback 값 사용.
  const insertTextRef = useRef<((text: string) => void) | null>(null);

  const inputDatasetOptions = useMemo<DatasetOption[]>(() => {
    if (!step) return [];
    const prevStepDatasets: DatasetOption[] = [];
    const prevStepDatasetIds = new Set<number>();

    const otherSteps = state.steps
      .map((s, i) => ({ step: s, number: i + 1 }))
      .filter(({ step: s }) => s.tempId !== step.tempId);

    for (const { step: s, number } of otherSteps) {
      if (number < stepNumber && s.outputDatasetId != null) {
        const ds = datasets.find((d) => d.id === s.outputDatasetId);
        if (ds) {
          prevStepDatasets.push({ ...ds, name: `[스텝${number}] ${ds.name}` });
          prevStepDatasetIds.add(ds.id);
        }
      }
    }

    // 이미 이전 스텝 출력으로 등장한 데이터셋은 중복 제외하고 일반 데이터셋을 추가
    const regularDatasets = datasets.filter((d) => !prevStepDatasetIds.has(d.id));
    return [...prevStepDatasets, ...regularDatasets];
  }, [step, state.steps, stepNumber, datasets]);

  // 이 스텝이 의존하는 스텝 중 영구 출력 데이터셋이 없는 (임시 출력) 스텝들
  const tempDependencySteps = useMemo(() => {
    if (!step) return [];
    return state.steps.filter(
      (s) => step.dependsOnTempIds.includes(s.tempId) && s.outputDatasetId === null,
    );
  }, [state.steps, step]);

  // 현재 선택된 스텝을 제외한 나머지 스텝들 — 스크립트 내 스텝 참조 UI에서 사용
  const otherSteps = useMemo(() => {
    if (!step) return [];
    return state.steps
      .map((s, i) => ({ step: s, number: i + 1 }))
      .filter(({ step: s }) => s.tempId !== step.tempId);
  }, [state.steps, step]);

  if (!step) {
    return (
      <div className="w-[400px] border-l h-full flex flex-col overflow-hidden">
        <div className="flex items-center px-4 py-3 border-b shrink-0">
          <span className="font-medium text-sm">파이프라인 정보</span>
        </div>
        <div className="flex-1 min-h-0">
          <ScrollArea className="h-full">
            <div className="p-4 space-y-4">
              {/* Name */}
              <div className="space-y-1.5">
                <Label htmlFor="pipeline-name">이름</Label>
                {readOnly ? (
                  <p className="text-sm">{state.name || '-'}</p>
                ) : (
                  <Input
                    id="pipeline-name"
                    value={state.name}
                    onChange={(e) =>
                      dispatch({ type: 'SET_META', payload: { name: e.target.value } })
                    }
                    placeholder="파이프라인 이름"
                  />
                )}
              </div>

              <Separator />

              {/* Description */}
              <div className="space-y-1.5">
                <Label htmlFor="pipeline-description">설명</Label>
                {readOnly ? (
                  <p className="text-sm whitespace-pre-wrap">{state.description || '-'}</p>
                ) : (
                  <Textarea
                    id="pipeline-description"
                    value={state.description}
                    onChange={(e) =>
                      dispatch({ type: 'SET_META', payload: { description: e.target.value } })
                    }
                    placeholder="파이프라인 설명을 입력하세요"
                    rows={4}
                  />
                )}
              </div>

              <Separator />

              {/* Active toggle */}
              <div className="flex items-center gap-3">
                <Switch
                  id="pipeline-active-side"
                  checked={state.isActive}
                  onCheckedChange={(active) =>
                    dispatch({ type: 'SET_META', payload: { isActive: active } })
                  }
                  disabled={readOnly}
                />
                <Label htmlFor="pipeline-active-side">파이프라인 활성화</Label>
              </div>

              {/* Metadata */}
              {pipelineInfo && (
                <>
                  <Separator />
                  <div className="grid grid-cols-2 gap-x-6 gap-y-2 text-sm">
                    <div className="text-muted-foreground">생성자</div>
                    <div>{pipelineInfo.createdBy ?? '-'}</div>
                    <div className="text-muted-foreground">생성일</div>
                    <div>{formatDate(pipelineInfo.createdAt ?? null)}</div>
                    <div className="text-muted-foreground">수정자</div>
                    <div>{pipelineInfo.updatedBy ?? '-'}</div>
                    <div className="text-muted-foreground">수정일</div>
                    <div>{formatDate(pipelineInfo.updatedAt ?? null)}</div>
                  </div>
                </>
              )}
            </div>
          </ScrollArea>
        </div>
      </div>
    );
  }

  const stepErrors = state.validationErrors.filter((e) => e.stepTempId === step.tempId);
  const getFieldError = (field: string) => stepErrors.find((e) => e.field === field)?.message;

  const handleClose = () => {
    dispatch({ type: 'SELECT_STEP', payload: { tempId: null } });
  };

  const handleUpdateStep = (changes: Partial<EditorStep>) => {
    dispatch({ type: 'UPDATE_STEP', payload: { tempId: step.tempId, changes } });
  };

  const handleDelete = () => {
    dispatch({ type: 'REMOVE_STEP', payload: { tempId: step.tempId } });
    dispatch({ type: 'SELECT_STEP', payload: { tempId: null } });
  };

  const nameError = getFieldError('name');
  const scriptContentError = getFieldError('scriptContent');
  const outputDatasetIdError = getFieldError('outputDatasetId');

  return (
    <div className="w-[400px] border-l h-full flex flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 border-b shrink-0">
        <span className="font-medium text-sm truncate">스텝 #{stepNumber}: {step.name || '(이름 없음)'}</span>
        <Button variant="ghost" size="icon" className="h-6 w-6 shrink-0" onClick={handleClose}>
          <X className="h-4 w-4" />
        </Button>
      </div>

      {/* Scrollable content - constrained to remaining space */}
      <div className="flex-1 min-h-0">
        <ScrollArea className="h-full">
          <div className="p-4 space-y-4 overflow-hidden">

          {/* Name */}
          <div className="space-y-1.5">
            <Label htmlFor="step-name">이름</Label>
            <Input
              id="step-name"
              placeholder="스텝 이름"
              value={step.name}
              disabled={readOnly}
              className={nameError ? 'border-destructive' : undefined}
              onChange={(e) => handleUpdateStep({ name: e.target.value })}
            />
            {nameError && (
              <p className="text-sm text-destructive">{nameError}</p>
            )}
          </div>

          <Separator />

          {/* Description */}
          <div className="space-y-1.5">
            <Label htmlFor="step-description">설명</Label>
            <Textarea
              id="step-description"
              rows={2}
              value={step.description}
              disabled={readOnly}
              onChange={(e) => handleUpdateStep({ description: e.target.value })}
            />
          </div>

          <Separator />

          {/* Script type */}
          <div className="space-y-1.5">
            <Label>스크립트 타입</Label>
            <Select
              value={step.scriptType}
              disabled={readOnly}
              onValueChange={(value) =>
                handleUpdateStep({ scriptType: value as EditorStep['scriptType'] })
              }
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {/* 스크립트 타입 레이블을 한국어 친화적으로 일관 적용 (#9) */}
                <SelectItem value="SQL">SQL</SelectItem>
                <SelectItem value="PYTHON">Python</SelectItem>
                <SelectItem value="API_CALL">API 호출</SelectItem>
                <SelectItem value="AI_CLASSIFY">AI 분류</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {/* Script content or API Config or AI Classify Config */}
          {step.scriptType === 'API_CALL' ? (
            <>
              <Separator />
              <Suspense fallback={<Skeleton className="h-[200px]" />}>
                <ApiCallStepConfig
                  apiConfig={(step.apiConfig ?? {}) as Record<string, unknown>}
                  apiConnectionId={step.apiConnectionId ?? null}
                  onChange={(config) => handleUpdateStep({ apiConfig: config })}
                  onConnectionChange={(id) => handleUpdateStep({ apiConnectionId: id })}
                  readOnly={readOnly}
                />
              </Suspense>
            </>
          ) : step.scriptType === 'AI_CLASSIFY' ? (
            <>
              <Separator />
              <Suspense fallback={<Skeleton className="h-[200px]" />}>
                <AiClassifyStepConfig
                  aiConfig={step.aiConfig ?? { prompt: '', outputColumns: [] }}
                  inputDatasetIds={step.inputDatasetIds}
                  onChange={(config) => handleUpdateStep({ aiConfig: config })}
                  readOnly={readOnly}
                />
              </Suspense>
            </>
          ) : (
            <>
              <Separator />
              <div className="space-y-1.5">
                <Label>스크립트</Label>
                {otherSteps.length > 0 && (
                  <div className="space-y-1.5">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-xs text-muted-foreground shrink-0">스텝 참조:</span>
                      {otherSteps.map(({ step: s, number }) => (
                        <button
                          key={s.tempId}
                          type="button"
                          onClick={() => insertTextRef.current?.(`{{#${number}}}`)}
                          className="inline-flex items-center rounded px-1.5 py-0.5 text-xs font-mono font-semibold border transition-colors hover:bg-accent bg-ai-accent-subtle border-ai-accent/30 text-ai-accent"
                          title={`클릭하여 {{#${number}}} 삽입`}
                        >
                          {`{{#${number}}}`} {s.name || '(이름 없음)'}
                        </button>
                      ))}
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {'{{#N}}은 해당 스텝의 출력 데이터셋으로 치환됩니다. 명시적 데이터셋은 data."tableName" 형식을 사용하세요.'}
                    </p>
                  </div>
                )}
                <Suspense fallback={<Skeleton className="h-[200px]" />}>
                  <ScriptEditor
                    value={step.scriptContent}
                    onChange={(value) => handleUpdateStep({ scriptContent: value })}
                    language={step.scriptType}
                    readOnly={readOnly}
                    insertTextRef={insertTextRef}
                  />
                </Suspense>
                {scriptContentError && (
                  <p className="text-sm text-destructive">{scriptContentError}</p>
                )}
              </div>
            </>
          )}

          <Separator />

          {/* Output dataset */}
          <div className="space-y-1.5">
            <Label>출력 데이터셋</Label>
            <Select
              value={step.outputDatasetId?.toString() ?? '__auto__'}
              onValueChange={(value) =>
                handleUpdateStep({
                  outputDatasetId: value === '__auto__' ? null : Number(value),
                })
              }
              disabled={readOnly}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="__auto__">자동 생성 (임시)</SelectItem>
                {datasets.map((ds) => (
                  <SelectItem key={ds.id} value={ds.id.toString()}>
                    {ds.name}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {step.outputDatasetId === null && (
              <p className="text-xs text-muted-foreground">
                실행 시 스텝 결과에 맞는 임시 데이터셋이 자동 생성됩니다
              </p>
            )}
            {outputDatasetIdError && (
              <p className="text-sm text-destructive">{outputDatasetIdError}</p>
            )}
          </div>

          {/* Python output columns (only for PYTHON steps without an output dataset) */}
          {step.scriptType === 'PYTHON' && step.outputDatasetId === null && (
            <>
              <Separator />
              <Suspense fallback={<Skeleton className="h-[120px]" />}>
                <PythonOutputColumns
                  columns={step.pythonConfig?.outputColumns ?? []}
                  onChange={(cols) =>
                    handleUpdateStep({
                      pythonConfig: { ...step.pythonConfig, outputColumns: cols },
                    })
                  }
                  readOnly={readOnly}
                />
              </Suspense>
            </>
          )}

          <Separator />

          {/* Load strategy */}
          <div className="space-y-1.5">
            <Label>로드 전략</Label>
            <Select
              value={step.loadStrategy ?? 'REPLACE'}
              disabled={readOnly}
              onValueChange={(value) => handleUpdateStep({ loadStrategy: value })}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="REPLACE">교체 (Replace)</SelectItem>
                <SelectItem value="APPEND">추가 (Append)</SelectItem>
              </SelectContent>
            </Select>
            <p className="text-xs text-muted-foreground">
              {(step.loadStrategy ?? 'REPLACE') === 'REPLACE'
                ? '출력 테이블을 비운 후 새로 생성합니다'
                : '기존 데이터에 새 데이터를 추가합니다'}
            </p>
          </div>

          {step.scriptType !== 'API_CALL' && (
            <>
              <Separator />

              {/* Input datasets */}
              <div className="space-y-1.5">
                <Label>입력 데이터셋</Label>
                <DatasetCombobox
                  mode="multi"
                  datasets={inputDatasetOptions}
                  value={step.inputDatasetIds}
                  disabled={readOnly}
                  onChange={(value) => handleUpdateStep({ inputDatasetIds: value })}
                />
                {tempDependencySteps.length > 0 && (
                  <p className="text-xs text-muted-foreground">
                    이전 스텝의 출력 데이터셋이 실행 시 자동으로 입력됩니다:
                    {' '}{tempDependencySteps.map((s) => s.name).join(', ')}
                  </p>
                )}
              </div>
            </>
          )}

          <Separator />

          {/* Dependencies (read-only) */}
          <div className="space-y-1.5">
            <Label>의존성 (읽기 전용)</Label>
            <div className="space-y-1">
              {step.dependsOnTempIds.length === 0 ? (
                <p className="text-sm text-muted-foreground">(없음)</p>
              ) : (
                step.dependsOnTempIds.map((depId) => {
                  const depStep = state.steps.find((s) => s.tempId === depId);
                  return (
                    <p key={depId} className="text-sm">
                      → {depStep?.name ?? '(알 수 없음)'}
                    </p>
                  );
                })
              )}
            </div>
            <p className="text-xs text-muted-foreground">
              캔버스에서 엣지 연결로 관리됩니다
            </p>
          </div>

          {/* Delete button */}
          {!readOnly && (
            <>
              <Separator />
              <div className="pb-2">
                <Button
                  variant="destructive"
                  className="w-full"
                  onClick={handleDelete}
                >
                  <Trash2 className="h-4 w-4" />
                  스텝 삭제
                </Button>
              </div>
            </>
          )}
          </div>
        </ScrollArea>
      </div>
    </div>
  );
}
