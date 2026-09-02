import { Trash2,X } from 'lucide-react';
import { lazy, Suspense, useId, useMemo, useRef, useState } from 'react';

const ApiCallStepConfig = lazy(() => import('./ApiCallStepConfig'));
const AiClassifyStepConfig = lazy(() => import('./AiClassifyStepConfig'));
const PythonOutputColumns = lazy(() => import('./PythonOutputColumns'));
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog';
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

  // 스텝 삭제 확인 다이얼로그 표시 여부 — 실수 삭제 방지 (rules-of-hooks: early return 이전 선언)
  const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);

  // 접근성: 라벨↔컨트롤 연결용 id 접두사 (#432). 기존 하드코딩 id(step-name 등)는 E2E 회귀를 피해 유지하고,
  // 새로 연결하는 것만 useId 로 만든다. (rules-of-hooks: early return 이전 선언)
  const baseId = useId();
  const scriptTypeId = `${baseId}-script-type`;
  const scriptLabelId = `${baseId}-script-label`;
  // DatasetCombobox 는 Popover 트리거 버튼(labelable)에 id 를 심어 라벨과 연결한다.
  const inputDatasetsId = `${baseId}-input-datasets`;
  const outputDatasetId = `${baseId}-output-dataset`;
  const outputDatasetHelpId = `${baseId}-output-dataset-help`;
  const outputDatasetErrorId = `${baseId}-output-dataset-error`;
  const loadStrategyId = `${baseId}-load-strategy`;
  const loadStrategyHelpId = `${baseId}-load-strategy-help`;
  const dependenciesLabelId = `${baseId}-dependencies-label`;

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
      /* 모바일: 전체 너비 + 상단 보더, lg+: 고정 400px 너비 + 좌측 보더 */
      <div className="w-full border-t lg:w-[400px] lg:border-l lg:border-t-0 h-full flex flex-col overflow-hidden">
        <div className="flex items-center px-4 py-3 border-b shrink-0">
          <span className="font-medium text-sm">파이프라인 정보</span>
        </div>
        <div className="flex-1 min-h-0">
          <ScrollArea className="h-full">
            <div className="p-4 space-y-4">
              {/* Name — 헤더의 Input이 단일 편집 진입점. 우측 패널은 읽기 전용으로만 표시하여
                  Ctrl+A 등으로 두 Input이 동시에 dispatch하는 중복 문자열 버그(#32) 방지. */}
              <div className="space-y-1.5">
                {/*
                  읽기 전용 표시라 대응하는 입력 요소가 없다 — Label 컴포넌트 대신 span 으로 둔다 (#432).
                  className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
                */}
                <span className="flex items-center gap-2 text-sm leading-none font-medium select-none">
                  이름
                </span>
                <p className="text-sm">{state.name || '-'}</p>
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

  // 삭제 버튼 클릭 시: 즉시 삭제하지 않고 확인 다이얼로그를 표시한다.
  const handleDeleteClick = () => {
    setDeleteDialogOpen(true);
  };

  // 삭제 확인 다이얼로그에서 "삭제" 클릭 시: 실제 스텝을 제거한다.
  const handleConfirmDelete = () => {
    dispatch({ type: 'REMOVE_STEP', payload: { tempId: step.tempId } });
    dispatch({ type: 'SELECT_STEP', payload: { tempId: null } });
  };

  const nameError = getFieldError('name');
  const scriptContentError = getFieldError('scriptContent');
  const outputDatasetIdError = getFieldError('outputDatasetId');

  return (
    /* 모바일: 전체 너비 + 상단 보더, lg+: 고정 400px 너비 + 좌측 보더 */
    <div className="w-full border-t lg:w-[400px] lg:border-l lg:border-t-0 h-full flex flex-col overflow-hidden">
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
            <Label htmlFor={scriptTypeId}>스크립트 타입</Label>
            <Select
              value={step.scriptType}
              disabled={readOnly}
              onValueChange={(value) =>
                handleUpdateStep({ scriptType: value as EditorStep['scriptType'] })
              }
            >
              <SelectTrigger id={scriptTypeId} className="w-full">
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
                {/*
                 * key={step.tempId}로 스텝이 변경될 때마다 ApiCallStepConfig를 재마운트한다.
                 * 재마운트 없이는 useState 지연 초기화(lazy initializer)가 마운트 시 1회만 실행되어
                 * headerPairs/queryParamPairs가 이전 스텝의 값으로 고정되는 stale 상태가 발생한다.
                 */}
                <ApiCallStepConfig
                  key={step.tempId}
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
                {/*
                  CodeMirror(ScriptEditor)는 id 를 받을 수 있는 폼 컨트롤이 아니라 htmlFor 대상이 못 된다.
                  라벨에 id 를 주고 에디터 래퍼에 role="group" + aria-labelledby 로 이름을 준다 (#432).
                  className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
                */}
                <span
                  id={scriptLabelId}
                  className="flex items-center gap-2 text-sm leading-none font-medium select-none"
                >
                  스크립트
                </span>
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
                <div role="group" aria-labelledby={scriptLabelId}>
                  <Suspense fallback={<Skeleton className="h-[200px]" />}>
                    <ScriptEditor
                      value={step.scriptContent}
                      onChange={(value) => handleUpdateStep({ scriptContent: value })}
                      language={step.scriptType}
                      readOnly={readOnly}
                      insertTextRef={insertTextRef}
                    />
                  </Suspense>
                </div>
                {scriptContentError && (
                  <p className="text-sm text-destructive">{scriptContentError}</p>
                )}
              </div>
            </>
          )}

          <Separator />

          {/* Output dataset */}
          <div className="space-y-1.5">
            <Label htmlFor={outputDatasetId}>출력 데이터셋</Label>
            <Select
              value={step.outputDatasetId?.toString() ?? '__auto__'}
              onValueChange={(value) =>
                handleUpdateStep({
                  outputDatasetId: value === '__auto__' ? null : Number(value),
                })
              }
              disabled={readOnly}
            >
              <SelectTrigger
                id={outputDatasetId}
                className="w-full"
                /* 자동 생성 안내문·검증 오류를 조건부로 연결한다 */
                aria-describedby={
                  [
                    step.outputDatasetId === null ? outputDatasetHelpId : null,
                    outputDatasetIdError ? outputDatasetErrorId : null,
                  ]
                    .filter(Boolean)
                    .join(' ') || undefined
                }
                aria-invalid={!!outputDatasetIdError}
              >
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
              <p id={outputDatasetHelpId} className="text-xs text-muted-foreground">
                실행 시 스텝 결과에 맞는 임시 데이터셋이 자동 생성됩니다
              </p>
            )}
            {outputDatasetIdError && (
              <p id={outputDatasetErrorId} className="text-sm text-destructive">{outputDatasetIdError}</p>
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
            <Label htmlFor={loadStrategyId}>로드 전략</Label>
            <Select
              value={step.loadStrategy ?? 'REPLACE'}
              disabled={readOnly}
              onValueChange={(value) => handleUpdateStep({ loadStrategy: value })}
            >
              <SelectTrigger
                id={loadStrategyId}
                className="w-full"
                aria-describedby={loadStrategyHelpId}
              >
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="REPLACE">교체 (Replace)</SelectItem>
                <SelectItem value="APPEND">추가 (Append)</SelectItem>
              </SelectContent>
            </Select>
            <p id={loadStrategyHelpId} className="text-xs text-muted-foreground">
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
                <Label htmlFor={inputDatasetsId}>입력 데이터셋</Label>
                <DatasetCombobox
                  id={inputDatasetsId}
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
            {/*
              읽기 전용 목록 제목이라 대응하는 입력 요소가 없다 — Label 컴포넌트 대신 span 으로 두고
              목록에 role="group" + aria-labelledby 로 이름을 준다 (#432).
              className 은 shadcn Label 기본 스타일을 그대로 옮겨 시각 결과를 유지한다.
            */}
            <span
              id={dependenciesLabelId}
              className="flex items-center gap-2 text-sm leading-none font-medium select-none"
            >
              의존성 (읽기 전용)
            </span>
            <div className="space-y-1" role="group" aria-labelledby={dependenciesLabelId}>
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

          {/* Delete button — 클릭 시 확인 다이얼로그 표시 후 삭제 (실수 삭제 방지) */}
          {!readOnly && (
            <>
              <Separator />
              <div className="pb-2">
                <Button
                  variant="destructive"
                  className="w-full"
                  onClick={handleDeleteClick}
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

      {/* 스텝 삭제 확인 다이얼로그 — 실수 삭제 방지 */}
      <AlertDialog
        open={deleteDialogOpen}
        onOpenChange={(open) => { if (!open) setDeleteDialogOpen(false); }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>스텝 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              정말 이 스텝을 삭제하시겠습니까? 저장하기 전까지는 취소가 가능합니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={handleConfirmDelete}>
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
