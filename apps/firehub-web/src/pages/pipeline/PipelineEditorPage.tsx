import { Clock, Code, Database, GitBranch, Globe, Link, User } from 'lucide-react';
import { useEffect, useMemo,useRef, useState } from 'react';
import { useNavigate,useParams } from 'react-router-dom';
import { toast } from 'sonner';

import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Tabs, TabsContent,TabsList, TabsTrigger } from '@/components/ui/tabs';
import { useDatasets } from '@/hooks/queries/useDatasets';
import { useExecutePipeline, useExecution, useExecutions,usePipeline } from '@/hooks/queries/usePipelines';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import type { PipelineExecutionResponse } from '@/types/pipeline';

import { EditorHeader } from './components/EditorHeader';
import { ExecutionStepPanel } from './components/ExecutionStepPanel';
import { PipelineCanvas } from './components/PipelineCanvas';
import StepConfigPanel from './components/StepConfigPanel';
import TriggerTab from './components/TriggerTab';
import { usePipelineEditor } from './hooks/usePipelineEditor';

function formatDuration(startedAt: string | null, completedAt: string | null): string {
  if (!startedAt) return '-';
  const start = new Date(startedAt).getTime();
  const end = completedAt ? new Date(completedAt).getTime() : Date.now();
  const totalSeconds = Math.floor((end - start) / 1000);
  if (totalSeconds < 0) return '-';
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  if (minutes > 0) return `${minutes}m ${seconds}s`;
  return `${seconds}s`;
}

export default function PipelineEditorPage() {
  const { id, execId } = useParams<{ id: string; execId: string }>();
  const pipelineId = id ? Number(id) : undefined;
  const executionId = execId ? Number(execId) : undefined;

  const navigate = useNavigate();

  // 조회/편집 모드: 신규 생성은 항상 편집, 기존 파이프라인은 조회 모드에서 시작
  const [isEditing, setIsEditing] = useState(!pipelineId);
  const readOnly = !!executionId || !isEditing;

  // 실행 상세 진입 시 실행 이력 탭, 그 외 개요 탭
  const [activeTab, setActiveTab] = useState(executionId ? 'executions' : 'overview');

  // 실행 상세 진입 시 수정 모드 해제 (render-time state adjustment)
  const [prevExecutionId, setPrevExecutionId] = useState(executionId);
  if (prevExecutionId !== executionId) {
    setPrevExecutionId(executionId);
    if (executionId) {
      setIsEditing(false);
      setActiveTab('executions');
    }
  }

  const { state, dispatch, save, loadFromApi, cancelEdit, isSaving } = usePipelineEditor(pipelineId);

  // isError: 존재하지 않는 파이프라인 ID(404 등) 접근 시 에러 상태를 감지한다.
  const { data: pipelineData, isLoading: pipelineLoading, isError: pipelineError } = usePipeline(pipelineId!);
  // isError: 존재하지 않는 실행 ID(400 등) 접근 시 에러 상태를 감지한다.
  const { data: executionData, isError: executionError } = useExecution(pipelineId!, executionId!);
  const executeMutation = useExecutePipeline(pipelineId!);
  const { data: datasetsData } = useDatasets({ size: 1000 });
  const { data: executions } = useExecutions(pipelineId ?? 0);

  /** 존재하지 않는 실행 ID 접근 시 toast 알림 후 파이프라인 페이지로 리다이렉트 — ApiConnectionDetailPage와 동일 패턴 */
  useEffect(() => {
    if (!executionError) return;
    toast.error('실행 정보를 불러오는데 실패했습니다.');
    navigate(`/pipelines/${pipelineId}`);
  }, [executionError, navigate, pipelineId]);

  // Load pipeline data from API only once
  const loadedRef = useRef(false);
  useEffect(() => {
    if (pipelineData && !loadedRef.current) {
      loadFromApi(pipelineData);
      loadedRef.current = true;
    }
  }, [pipelineData, loadFromApi]);

  // Warn on browser/tab close or refresh
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (state.isDirty) e.preventDefault();
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [state.isDirty]);

  const handleSave = async () => {
    const ok = await save();
    if (ok) setIsEditing(false);
  };

  const handleCancelEdit = () => {
    cancelEdit();
    setIsEditing(false);
  };

  const handleExecute = async () => {
    try {
      await executeMutation.mutateAsync();
      toast.success('파이프라인 실행이 시작되었습니다');
    } catch {
      toast.error('파이프라인 실행에 실패했습니다');
    }
  };

  const datasetOptions = useMemo(
    () =>
      datasetsData?.content?.map((d) => ({
        id: d.id,
        name: d.name,
        tableName: d.tableName,
      })) ?? [],
    [datasetsData],
  );

  const selectedStepName = state.selectedStepId
    ? (state.steps.find((s) => s.tempId === state.selectedStepId)?.name ?? null)
    : null;

  const pipelineInfo = pipelineData
    ? {
        createdBy: pipelineData.createdBy,
        createdAt: pipelineData.createdAt,
        updatedBy: pipelineData.updatedBy,
        updatedAt: pipelineData.updatedAt,
      }
    : undefined;

  // 기존 파이프라인 ID가 있으나 로딩 중인 경우: 스켈레톤 표시
  if (pipelineId && pipelineLoading) {
    return (
      <div className="space-y-4 p-4">
        <div className="h-8 w-64 animate-pulse rounded bg-muted" />
        <div className="h-96 w-full animate-pulse rounded bg-muted" />
      </div>
    );
  }

  // 기존 파이프라인 ID가 있으나 에러(404 등)인 경우: 존재하지 않는 페이지 안내
  if (pipelineId && pipelineError) {
    return (
      <div className="flex flex-col items-center justify-center gap-4 py-20">
        <GitBranch className="h-12 w-12 text-muted-foreground" />
        <p className="text-muted-foreground">파이프라인을 찾을 수 없습니다.</p>
        <Button variant="outline" onClick={() => navigate('/pipelines')}>
          목록으로
        </Button>
      </div>
    );
  }

  return (
    <div className="h-[calc(100vh-64px)] w-full overflow-hidden flex flex-col">
      {/* Header */}
      <EditorHeader
        state={state}
        dispatch={dispatch}
        readOnly={readOnly}
        isEditing={isEditing}
        isExecutionMode={!!executionId}
        onSave={handleSave}
        onCancelEdit={handleCancelEdit}
        onEdit={() => setIsEditing(true)}
        onExecute={handleExecute}
        isSaving={isSaving}
        isExecuting={executeMutation.isPending}
        pipelineId={state.pipelineId}
      />

      {/* Main content with tabs */}
      <Tabs value={activeTab} onValueChange={setActiveTab} className="flex-1 flex flex-col min-h-0">
        {pipelineId && (
          <TabsList className="border-b justify-start h-10 px-4 shrink-0">
            <TabsTrigger
              value="overview"
              onPointerDown={() => {
                if (executionId) {
                  navigate(`/pipelines/${pipelineId}`);
                }
              }}
            >
              개요
            </TabsTrigger>
            <TabsTrigger
              value="triggers"
              onPointerDown={() => {
                if (executionId) {
                  navigate(`/pipelines/${pipelineId}`);
                }
              }}
            >
              트리거
            </TabsTrigger>
            <TabsTrigger
              value="executions"
              onPointerDown={() => {
                if (executionId) {
                  navigate(`/pipelines/${pipelineId}`);
                }
              }}
            >
              실행 이력
            </TabsTrigger>
          </TabsList>
        )}

        {/* 모바일(375px)에서 캔버스 width=0px 붕괴 방지: 모바일은 세로 스택(flex-col), lg+ 에서 가로 배치(flex-row) */}
        <TabsContent value="overview" className="flex-1 flex flex-col lg:flex-row min-h-0 mt-0 border rounded-b-lg">
          {/* Canvas: 가로 배치 시 남은 너비, 세로 배치(모바일) 시 최소 300px 높이 확보 */}
          <div className="flex-1 min-w-0 min-h-[300px]">
            <PipelineCanvas
              state={state}
              dispatch={dispatch}
              readOnly={readOnly}
            />
          </div>

          {/* Right panel */}
          <StepConfigPanel
            state={state}
            dispatch={dispatch}
            readOnly={readOnly}
            datasets={datasetOptions}
            pipelineInfo={pipelineInfo}
          />
        </TabsContent>

        <TabsContent value="triggers" className="flex-1 overflow-hidden mt-0 border rounded-b-lg">
          <TriggerTab pipelineId={pipelineId!} />
        </TabsContent>

        <TabsContent value="executions" className="flex-1 overflow-hidden mt-0 border rounded-b-lg">
          {executionId && executionData ? (
            /* 실행 상세 뷰: 캔버스 + 사이드패널 */
            <div className="h-full flex min-h-0">
              <div className="flex-1 min-w-0">
                <PipelineCanvas
                  state={state}
                  dispatch={dispatch}
                  readOnly={true}
                  executionData={executionData}
                />
              </div>
              <ExecutionStepPanel
                execution={executionData}
                selectedStepName={selectedStepName}
                onClose={() => dispatch({ type: 'SELECT_STEP', payload: { tempId: null } })}
              />
            </div>
          ) : !executions || executions.length === 0 ? (
            <div className="flex items-center justify-center h-full text-muted-foreground text-sm">
              실행 기록이 없습니다.
            </div>
          ) : (
            <ScrollArea className="h-full">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead className="w-16">#ID</TableHead>
                    <TableHead>상태</TableHead>
                    <TableHead>트리거</TableHead>
                    <TableHead>실행자</TableHead>
                    <TableHead>시작시간</TableHead>
                    <TableHead>소요시간</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {executions.map((exec: PipelineExecutionResponse) => (
                    <TableRow
                      key={exec.id}
                      className="cursor-pointer hover:bg-muted/50"
                      onClick={() => navigate(`/pipelines/${state.pipelineId}/executions/${exec.id}`)}
                    >
                      <TableCell className="font-mono text-xs">{exec.id}</TableCell>
                      <TableCell>
                        <Badge variant={getStatusBadgeVariant(exec.status)}>
                          {getStatusLabel(exec.status)}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <span className="inline-flex items-center gap-1 text-xs text-muted-foreground">
                          {exec.triggeredBy === 'SCHEDULE' && <><Clock className="h-3 w-3" />스케줄</>}
                          {exec.triggeredBy === 'API' && <><Code className="h-3 w-3" />API</>}
                          {exec.triggeredBy === 'PIPELINE_CHAIN' && <><Link className="h-3 w-3" />연쇄</>}
                          {exec.triggeredBy === 'WEBHOOK' && <><Globe className="h-3 w-3" />웹훅</>}
                          {exec.triggeredBy === 'DATASET_CHANGE' && <><Database className="h-3 w-3" />데이터 변경</>}
                          {(!exec.triggeredBy || exec.triggeredBy === 'MANUAL') && <><User className="h-3 w-3" />수동</>}
                        </span>
                      </TableCell>
                      <TableCell className="text-sm">{exec.executedBy}</TableCell>
                      <TableCell className="text-sm">{formatDate(exec.startedAt)}</TableCell>
                      <TableCell className="text-sm">
                        {formatDuration(exec.startedAt, exec.completedAt)}
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollArea>
          )}
        </TabsContent>
      </Tabs>
    </div>
  );
}
