import '@xyflow/react/dist/style.css';

import {
  Background,
  Controls,
  type Edge,
  MarkerType,
  type NodeMouseHandler,
  type OnConnect,
  type OnEdgesDelete,
  type OnNodeDrag,
  ReactFlow,
} from '@xyflow/react';
import { LayoutGrid, Pencil,Plus } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useCallback,useMemo } from 'react';

import { Button } from '@/components/ui/button';
import type { ExecutionDetailResponse } from '@/types/pipeline';

import type { EditorAction,PipelineEditorState } from '../hooks/usePipelineEditor';
import { AddStepEdge } from './AddStepEdge';
import { StepNode, type StepNodeData, type StepNodeType } from './StepNode';

interface PipelineCanvasProps {
  state: PipelineEditorState;
  dispatch: React.Dispatch<EditorAction>;
  readOnly: boolean;
  executionData?: ExecutionDetailResponse;
}

export function PipelineCanvas({
  state,
  dispatch,
  readOnly,
  executionData,
}: PipelineCanvasProps) {
  const { resolvedTheme } = useTheme();
  const nodeTypes = useMemo(() => ({ step: StepNode }), []);
  const edgeTypes = useMemo(() => ({ addStep: AddStepEdge }), []);

  // 모든 edge에 화살표(markerEnd)를 일괄 적용해 데이터 흐름 방향을 시각화한다.
  // color는 edge stroke와 동일한 currentColor를 사용해 테마(다크/라이트)에 자연스럽게 따라간다.
  const defaultEdgeOptions = useMemo(
    () => ({
      markerEnd: {
        type: MarkerType.ArrowClosed,
        width: 18,
        height: 18,
        color: 'currentColor',
      },
    }),
    [],
  );

  // Set of step tempIds that have at least one outgoing edge (are depended upon)
  const outgoingSet = useMemo(() => {
    const set = new Set<string>();
    for (const step of state.steps) {
      for (const depId of step.dependsOnTempIds) {
        set.add(depId);
      }
    }
    return set;
  }, [state.steps]);

  const nodes = useMemo<StepNodeType[]>(() =>
    state.steps.map((step, index) => ({
      id: step.tempId,
      type: 'step',
      position: step.position,
      data: {
        label: step.name || '(이름 없음)',
        description: step.description || undefined,
        scriptType: step.scriptType,
        stepNumber: index + 1,
        isSelected: step.tempId === state.selectedStepId,
        hasError: state.validationErrors.some((e) => e.stepTempId === step.tempId),
        hasOutgoingEdge: outgoingSet.has(step.tempId),
        executionStatus: executionData?.stepExecutions?.find(
          (se) => se.stepName === step.name,
        )?.status,
        readOnly,
        onDelete: readOnly
          ? undefined
          : () => dispatch({ type: 'REMOVE_STEP', payload: { tempId: step.tempId } }),
        onAddAfter: readOnly
          ? undefined
          : () => dispatch({ type: 'ADD_STEP_AFTER', payload: { sourceTempId: step.tempId } }),
      } satisfies StepNodeData,
    })),
  [state.steps, state.selectedStepId, state.validationErrors, outgoingSet, executionData, readOnly, dispatch]);

  const onInsertBetween = useCallback(
    (sourceId: string, targetId: string) => {
      dispatch({ type: 'INSERT_STEP_BETWEEN', payload: { sourceTempId: sourceId, targetTempId: targetId } });
    },
    [dispatch],
  );

  const edges = useMemo<Edge[]>(() =>
    state.steps.flatMap((step) =>
      step.dependsOnTempIds.map((depTempId) => ({
        id: `${depTempId}-${step.tempId}`,
        source: depTempId,
        target: step.tempId,
        type: 'addStep',
        data: { readOnly, onInsertBetween },
        animated:
          executionData?.stepExecutions?.find(
            (se) =>
              se.stepName ===
              state.steps.find((s) => s.tempId === depTempId)?.name,
          )?.status === 'RUNNING',
      })),
    ),
  [state.steps, executionData, readOnly, onInsertBetween]);

  const onNodeClick: NodeMouseHandler = (_event, node) => {
    // SELECT_STEP is allowed in readOnly mode (non-destructive, enables ExecutionStepPanel)
    dispatch({ type: 'SELECT_STEP', payload: { tempId: node.id } });
  };

  const onNodeDragStop: OnNodeDrag = (_event, node) => {
    if (readOnly) return;
    dispatch({
      type: 'UPDATE_NODE_POSITION',
      payload: { tempId: node.id, position: node.position },
    });
  };

  const onConnect: OnConnect = (connection) => {
    if (readOnly) return;
    if (!connection.source || !connection.target) return;
    dispatch({
      type: 'ADD_EDGE',
      payload: { sourceTempId: connection.source, targetTempId: connection.target },
    });
  };

  const onEdgesDelete: OnEdgesDelete = (deletedEdges) => {
    if (readOnly) return;
    for (const edge of deletedEdges) {
      dispatch({
        type: 'REMOVE_EDGE',
        payload: { sourceTempId: edge.source, targetTempId: edge.target },
      });
    }
  };

  const handleAddStep = () => {
    // 노드 카드 크기(가로 220px)보다 완전 랜덤 좌표 범위가 좁으면 신규 스텝이
    // 기존 스텝과 겹쳐 배치될 확률이 높다(#668). 기존 마지막 스텝 위치에서
    // 오른쪽/아래로 일정 간격 오프셋을 주는 계단식 배치로 겹침을 방지한다.
    const lastStep = state.steps[state.steps.length - 1];
    const position = lastStep
      ? { x: lastStep.position.x + 280, y: lastStep.position.y + 60 }
      : { x: 100, y: 100 };
    dispatch({
      type: 'ADD_STEP',
      payload: { position },
    });
  };

  const handleAutoLayout = () => {
    dispatch({ type: 'AUTO_LAYOUT' });
  };

  if (state.steps.length === 0) {
    return (
      <div className="relative flex h-full w-full flex-col items-center justify-center gap-4 rounded-lg border-2 border-dashed border-border bg-muted/20">
        <p className="text-muted-foreground">첫 번째 스텝을 추가하세요</p>
        {!readOnly && (
          <button
            onClick={handleAddStep}
            className="inline-flex items-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90"
          >
            <Plus className="h-5 w-5" />
            스텝 추가
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="relative h-full w-full [&_.react-flow]:text-sm">
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes as never}
        edgeTypes={edgeTypes}
        defaultEdgeOptions={defaultEdgeOptions}
        onNodeClick={onNodeClick}
        onNodeDragStop={onNodeDragStop}
        onConnect={onConnect}
        onEdgesDelete={onEdgesDelete}
        onPaneClick={() => dispatch({ type: 'SELECT_STEP', payload: { tempId: null } })}
        nodesDraggable={!readOnly}
        nodesConnectable={!readOnly}
        elementsSelectable={!readOnly}
        maxZoom={1}
        fitView
        fitViewOptions={{ maxZoom: 1, padding: 0.3 }}
        proOptions={{ hideAttribution: true }}
        ariaLabelConfig={KO_ARIA_LABEL_CONFIG}
      >
        <Background color={resolvedTheme === 'dark' ? 'oklch(1 0 0 / 8%)' : undefined} />
        <Controls showInteractive={!readOnly} />
      </ReactFlow>

      {/* 수정모드 표시 (좌상단) */}
      {!readOnly && (
        <div className="absolute left-3 top-3 z-10 flex items-center gap-1.5 rounded-md border bg-background/90 px-2.5 py-1 text-xs font-medium text-muted-foreground shadow-sm backdrop-blur-sm">
          <Pencil className="h-3 w-3" />
          수정모드
        </div>
      )}

      {/* 자동 정렬 + 스텝 추가 (우상단, 실행 상세에서는 숨김) */}
      {!executionData && (
        <div className="absolute right-3 top-3 z-10 flex items-center gap-1.5">
          <Button variant="outline" size="sm" className="h-8 gap-1.5 shadow-sm" onClick={handleAutoLayout}>
            <LayoutGrid className="h-3.5 w-3.5" />
            자동 정렬
          </Button>
          {!readOnly && (
            <Button variant="outline" size="sm" className="h-8 gap-1.5 shadow-sm" onClick={handleAddStep}>
              <Plus className="h-3.5 w-3.5" />
              스텝 추가
            </Button>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * @xyflow/react 컨트롤 패널/접근성 라벨의 한국어 i18n 설정.
 * ReactFlow의 ariaLabelConfig prop으로 전달하면 Controls 4개 버튼
 * (zoomIn/zoomOut/fitView/interactive) 및 컨트롤/노드/엣지 컨테이너의
 * aria-label/title이 한국어로 노출된다.
 */
const KO_ARIA_LABEL_CONFIG = {
  'controls.ariaLabel': '다이어그램 컨트롤',
  'controls.zoomIn.ariaLabel': '확대',
  'controls.zoomOut.ariaLabel': '축소',
  'controls.fitView.ariaLabel': '전체 보기',
  'controls.interactive.ariaLabel': '상호작용 잠금/해제',
} as const;
