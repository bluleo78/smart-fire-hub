import { useState, useEffect, useRef, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { toast } from 'sonner';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { Badge } from '@/components/ui/badge';
import { ScrollArea } from '@/components/ui/scroll-area';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { usePipelineEditor } from './hooks/usePipelineEditor';
import { usePipeline, useExecution, useExecutePipeline, useExecutions } from '@/hooks/queries/usePipelines';
import { useDatasets } from '@/hooks/queries/useDatasets';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '@/lib/formatters';
import { EditorHeader } from './components/EditorHeader';
import { PipelineCanvas } from './components/PipelineCanvas';
import StepConfigPanel from './components/StepConfigPanel';
import { ExecutionStepPanel } from './components/ExecutionStepPanel';
import type { PipelineExecutionResponse } from '@/types/pipeline';

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

  // 실행 상세 진입 시 수정 모드 해제
  useEffect(() => {
    if (executionId) {
      setIsEditing(false);
      setActiveTab('executions');
    }
  }, [executionId]);

  const { state, dispatch, save, loadFromApi, cancelEdit, isSaving } = usePipelineEditor(pipelineId);

  const { data: pipelineData } = usePipeline(pipelineId!);
  const { data: executionData } = useExecution(pipelineId!, executionId!);
  const executeMutation = useExecutePipeline(pipelineId!);
  const { data: datasetsData } = useDatasets({ size: 1000 });
  const { data: executions } = useExecutions(pipelineId ?? 0);

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
    await save();
    setIsEditing(false);
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
            <TabsTrigger value="overview">개요</TabsTrigger>
            <TabsTrigger
              value="executions"
              onPointerDown={() => {
                if (executionId) {
                  // 실행 상세에서 실행 이력 탭 클릭 시 실행 목록으로 복귀
                  navigate(`/pipelines/${pipelineId}`);
                }
              }}
            >
              실행 이력
            </TabsTrigger>
          </TabsList>
        )}

        <TabsContent value="overview" className="flex-1 flex min-h-0 mt-0 border rounded-b-lg">
          {/* Canvas (takes remaining space) */}
          <div className="flex-1 min-w-0">
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
