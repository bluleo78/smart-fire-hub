import '@xyflow/react/dist/style.css';

import {
  Background,
  Controls,
  type Edge,
  MarkerType,
  type NodeMouseHandler,
  type OnConnect,
  type OnEdgesChange,
  type OnNodeDrag,
  type OnNodesChange,
  ReactFlow,
  type XYPosition,
} from '@xyflow/react';
import { LayoutGrid, Pencil,Plus } from 'lucide-react';
import { useTheme } from 'next-themes';
import { useCallback, useMemo, useRef, useState } from 'react';

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
import type { ExecutionDetailResponse } from '@/types/pipeline';

import type { EditorAction,PipelineEditorState } from '../hooks/usePipelineEditor';
import { isUnnamedStep, stepDisplayName } from '../utils/step-display-name';
import { findStepExecution } from '../utils/step-execution';
import { AddStepEdge } from './AddStepEdge';
import { StepNode, type StepNodeData, type StepNodeType } from './StepNode';

/** 끄는 중 노드 위치 덮어쓰기 — 만든 때의 steps 를 함께 들고 있어 그것이 바뀌면 무효가 된다 */
interface DragOverride {
  id: string;
  position: XYPosition;
  steps: PipelineEditorState['steps'];
}

/** 선택된 연결선 id 와, 그 선택을 만든 때의 steps·모드(이것이 바뀌면 선택은 무효) */
interface EdgeSelection {
  steps: PipelineEditorState['steps'];
  readOnly: boolean;
  ids: ReadonlySet<string>;
}
const NO_EDGES: ReadonlySet<string> = new Set();

/**
 * 캔버스 위에 떠 있는 outline 버튼의 칠(WD-778) — 공용 outline 은 쉬는 면·hover 면이 투명/반투명이라 팬할 때 노드·격자가 비친다.
 * 불투명 바탕(bg-background)·hover 면(라이트 accent, 다크 popover)으로 칠하되, tailwind-merge 가 variant 의
 * dark:bg-input/30·dark:hover:bg-input/50 을 걷어내도록 dark:·dark:hover: 클래스를 직접 준다.
 */
const FLOATING_BUTTON_CLASS =
  'h-8 gap-1.5 bg-background shadow-sm hover:bg-accent dark:bg-background dark:hover:bg-popover';

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
  // 삭제 확인을 기다리는 스텝 — X 버튼은 즉시 지우지 않고 확인을 거쳐 실수 삭제를 막는다(#536).
  // 창은 노드가 아니라 캔버스가 한 개만 들고, onKeyDown div 의 형제로 그려 창의 이벤트가 캔버스·노드로 올라가지 않게 한다(WD-952).
  const [pendingDeleteId, setPendingDeleteId] = useState<string | null>(null);
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

  /**
   * 마우스로 끄는 동안만 쓰는 로컬 위치(WD-1057) — 노드가 state.steps 로 제어돼 위치는 끌기 끝에서만 반영되므로
   * 끄는 중에는 노드가 제자리였다. 매 움직임을 reducer 에 저장하면 변경 표시·되돌리기 이력이 오염되므로
   * 끈 노드 하나(onNodeDrag 의 node)의 위치만 여기 두고 nodes 에서 덮어쓴다. 끌기 끝에서 지운다.
   */
  const [dragOverride, setDragOverride] = useState<DragOverride | null>(null);
  /**
   * 덮어쓰기는 만든 때의 steps 에 묶고 편집 모드에서만 쓴다(edgeSelection 과 같은 패턴) — onNodeDragStop 없이 끌기가 끊겨도
   * (xyflow abortDrag: 터치 두 손가락·노드 사라짐) steps 가 바뀌면(저장·자동 정렬·화살표) 옛 덮어쓰기는 저절로 무시돼
   * step.position 을 계속 가리지 않는다. 끄는 동안 steps 는 그대로라 유효하다. 덮어쓴 id 가 steps 에 없으면 아무 노드에도 쓰이지 않는다.
   */
  const liveDragOverride =
    dragOverride && !readOnly && dragOverride.steps === state.steps
      ? dragOverride
      : null;

  /** 스텝 상태로 만든 노드 — 끄는 동안 바뀌지 않아 마우스가 움직일 때마다 전체를 다시 만들지 않는다 */
  const baseNodes = useMemo<StepNodeType[]>(() =>
    state.steps.map((step, index) => ({
      id: step.tempId,
      type: 'step',
      position: step.position,
      domAttributes: STEP_DOM_ATTRS,
      // 선택된(설정이 열린) 스텝만 xyflow 가 화살표 키로 옮긴다 — 선택을 우리 상태에서 내려 줘야 한다(WD-1046)
      selected: step.tempId === state.selectedStepId,
      data: {
        // 이름이 없으면 연결선 라벨·이동 알림과 같이 「스텝 #순번」으로 부른다 — 스크린리더가 어디서나 같은 이름을 듣게 (WD-1049)
        label: stepDisplayName(step.name, index),
        isUnnamed: isUnnamedStep(step.name),
        description: step.description || undefined,
        scriptType: step.scriptType,
        stepNumber: index + 1,
        isSelected: step.tempId === state.selectedStepId,
        hasError: state.validationErrors.some((e) => e.stepTempId === step.tempId),
        hasOutgoingEdge: outgoingSet.has(step.tempId),
        // 실행 기록은 스텝 id 로 찾는다(WD-108) — 이름으로 찾으면 삭제된 동명 스텝의 기록이 새 스텝에 붙는다.
        executionStatus: findStepExecution(executionData?.stepExecutions, step.id)?.status,
        readOnly,
        onRequestDelete: readOnly ? undefined : () => setPendingDeleteId(step.tempId),
        onAddAfter: readOnly
          ? undefined
          : () => dispatch({ type: 'ADD_STEP_AFTER', payload: { sourceTempId: step.tempId } }),
      } satisfies StepNodeData,
    })),
  [state.steps, state.selectedStepId, state.validationErrors, outgoingSet, executionData, readOnly, dispatch]);

  /** 끄는 중이면 끈 노드 하나만 위치를 바꾼 새 객체로 갈아 끼운다 — 다른 노드는 같은 객체라 다시 그리지 않는다 */
  const nodes = useMemo<StepNodeType[]>(
    () =>
      liveDragOverride
        ? baseNodes.map((n) => (n.id === liveDragOverride.id ? { ...n, position: liveDragOverride.position } : n))
        : baseNodes,
    [baseNodes, liveDragOverride],
  );

  /**
   * 선택된 연결선 — 엣지를 state.steps 에서 매 렌더 새로 만들기 때문에 React Flow 의 선택 변경을
   * 여기서 들고 있어야 선택이 유지된다. 선택이 없으면 Backspace·Delete 로 지울 대상도 없다(WD-346).
   * 선택은 그때의 steps 에 묶어 둔다 — 연결을 끊거나 스텝을 바꾸면 steps 가 새로 만들어져 선택이 저절로 풀리므로,
   * 같은 id 의 연결을 다시 이었을 때 옛 선택이 살아나 다음 Delete 에 지워지는 일이 없다.
   */
  const [edgeSelection, setEdgeSelection] = useState<EdgeSelection>({ steps: state.steps, readOnly, ids: NO_EDGES });
  /** 지금 steps·모드에서 만든 선택만 살아 있다 — 끊기·스텝 변경·모드 전환 뒤의 옛 선택은 버린다 */
  const liveIds = (sel: EdgeSelection) =>
    !readOnly && sel.steps === state.steps && sel.readOnly === readOnly ? sel.ids : NO_EDGES;
  const selectedEdgeIds = liveIds(edgeSelection);

  // 이미 비어 있으면 그대로 둬 엣지를 다시 만들지 않는다(노드·빈 곳 클릭마다 불린다)
  const clearEdgeSelection = () => {
    if (selectedEdgeIds.size > 0) setEdgeSelection({ steps: state.steps, readOnly, ids: NO_EDGES });
  };

  const onEdgesChange: OnEdgesChange = (changes) => {
    const selectChanges = changes.filter((c) => c.type === 'select');
    if (selectChanges.length === 0) return;
    setEdgeSelection((prev) => {
      const ids = new Set(liveIds(prev));
      for (const c of selectChanges) {
        if (c.selected) ids.add(c.id);
        else ids.delete(c.id);
      }
      return { steps: state.steps, readOnly, ids };
    });
  };

  /** 연결 끊기 — 연결선의 「연결 끊기」 버튼과 키보드 삭제가 같은 동작을 쓴다 */
  const onRemoveEdge = useCallback(
    (sourceId: string, targetId: string) => {
      dispatch({ type: 'REMOVE_EDGE', payload: { sourceTempId: sourceId, targetTempId: targetId } });
    },
    [dispatch],
  );

  const onInsertBetween = useCallback(
    (sourceId: string, targetId: string) => {
      dispatch({ type: 'INSERT_STEP_BETWEEN', payload: { sourceTempId: sourceId, targetTempId: targetId } });
    },
    [dispatch],
  );

  const edges = useMemo<Edge[]>(() => {
    // 연결선 이름에 쓸 스텝 표시 이름을 스텝마다 한 번 만든다 — 이름이 없으면 「스텝 #순번」(WD-918)
    const labels = new Map(state.steps.map((s, i) => [s.tempId, stepDisplayName(s.name, i)]));
    const stepLabel = (tempId: string) => labels.get(tempId) ?? '';
    return state.steps.flatMap((step) =>
      step.dependsOnTempIds.map((depTempId) => ({
        id: `${depTempId}-${step.tempId}`,
        // xyflow 기본 영문 「Edge from … to …」 대신 스텝 이름으로 읽히게 한다(WD-918)
        ariaLabel: `「${stepLabel(depTempId)}」에서 「${stepLabel(step.tempId)}」(으)로 연결`,
        domAttributes: EDGE_DOM_ATTRS,
        source: depTempId,
        target: step.tempId,
        type: 'addStep',
        data: { readOnly, onInsertBetween, onRemoveEdge },
        selected: selectedEdgeIds.has(`${depTempId}-${step.tempId}`),
        animated:
          findStepExecution(
            executionData?.stepExecutions,
            state.steps.find((s) => s.tempId === depTempId)?.id,
          )?.status === 'RUNNING',
      })),
    );
  }, [state.steps, executionData, readOnly, onInsertBetween, onRemoveEdge, selectedEdgeIds]);

  const onNodeClick: NodeMouseHandler = (_event, node) => {
    // SELECT_STEP is allowed in readOnly mode (non-destructive, enables ExecutionStepPanel)
    clearEdgeSelection();
    dispatch({ type: 'SELECT_STEP', payload: { tempId: node.id } });
  };

  /** 마우스로 끌린 노드 id(끌기 시작~끝) — 끌기 끝의 위치 변경과 화살표 키 이동의 위치 변경을 구분한다 */
  const draggingIdsRef = useRef<Set<string>>(new Set());
  /** 키보드(화살표)로 옮긴 결과를 알리는 한국어 문구 — 캔버스 자체 aria-live 영역에 그린다 */
  const [moveAnnouncement, setMoveAnnouncement] = useState('');

  /**
   * 마우스 끌기 끝 — 사용자가 끈 노드 하나만 저장한다.
   * xyflow 는 이미 selected 인(설정을 연) 다른 스텝도 함께 끌 대상으로 모아 그쪽 위치 변경도 보내므로,
   * onNodesChange 로 저장하면 엉뚱한 스텝이 같이 옮겨진다(WD-1046).
   */
  const onNodeDragStop: OnNodeDrag<StepNodeType> = (_event, node) => {
    // 읽기 전용이어도 덮어쓰기는 먼저 비운다(잔재 방지)
    setDragOverride(null);
    if (readOnly) return;
    dispatch({
      type: 'UPDATE_NODE_POSITION',
      payload: { tempId: node.id, position: node.position },
    });
  };

  /**
   * 끄는 동안 — 사용자가 잡은 노드(node) 하나의 위치만 덮어쓰기로 둔다(WD-1057).
   * onNodesChange 의 dragging 변경은 함께 끌린 selected 노드도 보내므로 쓰지 않는다.
   */
  const onNodeDrag: OnNodeDrag<StepNodeType> = (_event, node) => {
    if (readOnly) return;
    setDragOverride({ id: node.id, position: node.position, steps: state.steps });
  };

  /**
   * 화살표 키 이동만 steps 에 반영하고 한국어로 알린다(xyflow 영문 알림은 비워 둠, WD-1046).
   * dragging:true 를 받은 id 는 마우스 끌기이므로 기록해 두었다가, 끝(dragging:false)에서 지우기만 하고
   * 저장·알림은 하지 않는다 — 저장은 onNodeDragStop 이 끈 노드 하나만 한다.
   */
  const onNodesChange: OnNodesChange<StepNodeType> = (changes) => {
    // 읽기 전용으로 바뀌면 끝나지 않은 끌기 id 가 남지 않게 비운다
    if (readOnly) {
      draggingIdsRef.current.clear();
      return;
    }
    for (const change of changes) {
      if (change.type !== 'position' || !change.position) continue;
      if (change.dragging) {
        draggingIdsRef.current.add(change.id);
        continue;
      }
      // 끌기 끝(함께 끌린 노드 포함) — 기록만 지운다
      if (draggingIdsRef.current.delete(change.id)) continue;
      const index = state.steps.findIndex((s) => s.tempId === change.id);
      const step = state.steps[index];
      if (!step) continue;
      dispatch({
        type: 'UPDATE_NODE_POSITION',
        payload: { tempId: change.id, position: change.position },
      });
      const dx = change.position.x - step.position.x;
      const dy = change.position.y - step.position.y;
      // 위치가 그대로면(경계 등) 알릴 이동이 없다 — 저장만 하고 알림은 내지 않는다
      if (dx === 0 && dy === 0) continue;
      const direction = dx !== 0 ? (dx > 0 ? '오른쪽' : '왼쪽') : dy > 0 ? '아래' : '위';
      setMoveAnnouncement(
        // 이름이 없으면 연결선 라벨과 같이 「스텝 #순번」으로 부른다
        `「${stepDisplayName(step.name, index)}」 스텝을 ${direction}(으)로 옮겼습니다 (가로 ${Math.round(change.position.x)}, 세로 ${Math.round(change.position.y)})`,
      );
    }
  };

  const onConnect: OnConnect = (connection) => {
    if (readOnly) return;
    if (!connection.source || !connection.target) return;
    dispatch({
      type: 'ADD_EDGE',
      payload: { sourceTempId: connection.source, targetTempId: connection.target },
    });
  };

  /**
   * 선택된 연결선을 Backspace·Delete 로 끊는다. React Flow 의 deleteKeyCode 는 문서 전체 키를 들어
   * 설정 패널의 버튼에 포커스가 있을 때도 연결이 지워지므로, 캔버스 안에서 누른 키만 처리한다.
   */
  const handleCanvasKeyDown = (e: React.KeyboardEvent<HTMLDivElement>) => {
    // 노드 요소 자체에서 누른 Enter/Space 는 스텝 설정(수정)·스텝 정보(보기 — 읽기 전용 설정 또는 실행 결과) 패널을 연다 (WD-1046).
    // xyflow 는 이 키에서 내부 선택만 하고 onNodeClick 을 부르지 않아 키보드로는 패널에 닿지 못했다.
    // 노드 안 버튼·입력칸에서 온 키는 target 이 노드 요소가 아니므로 제외해 버튼 동작을 가로채지 않는다.
    // 보기 모드에서도 열려야 하므로 readOnly 검사보다 먼저 처리한다.
    if (e.key === 'Enter' || e.key === ' ') {
      const target = e.target as HTMLElement;
      const tempId = target.classList.contains('react-flow__node') ? target.dataset.id : undefined;
      if (tempId) {
        e.preventDefault(); // Space 의 페이지 스크롤 방지
        clearEdgeSelection();
        dispatch({ type: 'SELECT_STEP', payload: { tempId } });
        return;
      }
    }
    if (readOnly || (e.key !== 'Backspace' && e.key !== 'Delete')) return;
    if ((e.target as HTMLElement).closest('input, textarea, [contenteditable="true"]')) return;
    const targets = edges.filter((edge) => edge.selected);
    if (targets.length === 0) return;
    e.preventDefault();
    for (const edge of targets) onRemoveEdge(edge.source, edge.target);
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

  // 읽기 전용이 되거나 대상 스텝이 사라지면(저장 완료·재조회) 창을 닫고 요청도 버린다 —
  // 남겨 두면 다시 열리거나, 닫히는 창의 「삭제」가 먹는다(WD-952)
  // 저장 중 잠금(WD-953) 뒤로는 저장 경로로 이 상황에 닿지 않아 방어용으로 남긴다
  const canConfirmDelete = !readOnly && state.steps.some((s) => s.tempId === pendingDeleteId);
  if (pendingDeleteId !== null && !canConfirmDelete) setPendingDeleteId(null);

  if (state.steps.length === 0) {
    return (
      <div className="relative flex h-full w-full flex-col items-center justify-center gap-4 rounded-lg border-2 border-dashed border-border bg-muted">
        <p className="text-muted-foreground">첫 번째 스텝을 추가하세요</p>
        {!readOnly && (
          // 패널 「스텝 삭제」로 스텝이 하나도 남지 않으면 초점을 이 버튼으로 보낸다(WD-1051).
          // 속성 이름을 바꾸면 StepConfigPanel 의 선택자도 맞춘다.
          <button
            onClick={handleAddStep}
            data-canvas-add-step=""
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
    <>
      <div className="relative h-full w-full [&_.react-flow]:text-sm" onKeyDown={handleCanvasKeyDown}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes as never}
          edgeTypes={edgeTypes}
          defaultEdgeOptions={defaultEdgeOptions}
          onNodeClick={onNodeClick}
          onNodeDrag={onNodeDrag}
          onNodeDragStop={onNodeDragStop}
          onNodesChange={onNodesChange}
          onConnect={onConnect}
          onEdgesChange={onEdgesChange}
          deleteKeyCode={null}
          onPaneClick={() => {
            clearEdgeSelection();
            dispatch({ type: 'SELECT_STEP', payload: { tempId: null } });
          }}
          nodesDraggable={!readOnly}
          nodesConnectable={!readOnly}
          elementsSelectable={!readOnly}
          maxZoom={1}
          // 좁은 화면(375px)에서 기본 최소 배율 0.5 에 걸려 「전체 보기」로도 스텝 세 개가 한 화면에 들어오지 않았다(WD-456)
          minZoom={0.2}
          fitView
          fitViewOptions={{ maxZoom: 1, minZoom: 0.2, padding: 0.3 }}
          proOptions={{ hideAttribution: true }}
          ariaLabelConfig={readOnly ? KO_ARIA_LABEL_CONFIG_VIEW : KO_ARIA_LABEL_CONFIG_EDIT}
          // WD-918: 스크린리더가 캔버스 영역을 영문 기본값 대신 한국어 이름으로 읽게 한다
          aria-label="파이프라인 다이어그램"
        >
          <Background color={resolvedTheme === 'dark' ? 'oklch(1 0 0 / 8%)' : undefined} />
          {/* WD-883: 확대·축소·전체 보기 버튼 크기는 index.css 의 .react-flow__controls-button 규칙(32×32)으로 키운다 */}
          <Controls showInteractive={!readOnly} />
        </ReactFlow>

        {/* 화살표 이동 알림 — xyflow 영문 알림 대신 한국어로 읽히게 하는 자체 알림 영역(WD-1046) */}
        <div role="status" aria-live="polite" className="sr-only" data-testid="canvas-move-announcement">
          {moveAnnouncement}
        </div>

        {/* 수정모드 표시 (좌상단) */}
        {!readOnly && (
          <div className="absolute left-3 top-3 z-10 flex items-center gap-1.5 rounded-md border bg-background/90 px-2.5 py-1 text-xs font-medium text-muted-foreground shadow-sm backdrop-blur-sm">
            <Pencil className="h-3 w-3" />
            수정모드
          </div>
        )}

        {/* 자동 정렬 + 스텝 추가 (우상단, 실행 상세에서는 숨김) — 칠은 FLOATING_BUTTON_CLASS 참고(WD-778) */}
        {!executionData && (
          <div className="absolute right-3 top-3 z-10 flex items-center gap-1.5">
            <Button variant="outline" size="sm" className={FLOATING_BUTTON_CLASS} onClick={handleAutoLayout}>
              <LayoutGrid className="h-3.5 w-3.5" />
              자동 정렬
            </Button>
            {!readOnly && (
              <Button variant="outline" size="sm" className={FLOATING_BUTTON_CLASS} onClick={handleAddStep}>
                <Plus className="h-3.5 w-3.5" />
                스텝 추가
              </Button>
            )}
          </div>
        )}
      </div>

      {/* 스텝 삭제 확인 — 캔버스 div 의 형제여야 한다(이유는 pendingDeleteId 선언부, WD-952·#536) */}
      <AlertDialog
        open={canConfirmDelete}
        onOpenChange={(open) => { if (!open) setPendingDeleteId(null); }}
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
            <AlertDialogAction
              variant="destructive"
              onClick={() => {
                const tempId = pendingDeleteId;
                setPendingDeleteId(null);
                if (tempId) dispatch({ type: 'REMOVE_STEP', payload: { tempId } });
              }}
            >
              삭제
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}

/**
 * @xyflow/react 컨트롤 패널/접근성 라벨의 한국어 i18n 설정 공통 부분.
 * Controls 4개 버튼(zoomIn/zoomOut/fitView/interactive) 및 컨트롤 컨테이너의
 * aria-label/title 이 한국어로 노출된다.
 */
const KO_CONTROLS_LABELS = {
  'controls.ariaLabel': '다이어그램 컨트롤',
  'controls.zoomIn.ariaLabel': '확대',
  'controls.zoomOut.ariaLabel': '축소',
  'controls.fitView.ariaLabel': '전체 보기',
  'controls.interactive.ariaLabel': '상호작용 잠금/해제',
} as const;

/** 스크린리더가 영문 node/edge 대신 「스텝」·「연결」로 읽게 하는 역할 설명 (WD-918) */
const STEP_DOM_ATTRS = { 'aria-roledescription': '스텝' } as const;
const EDGE_DOM_ATTRS = { 'aria-roledescription': '연결' } as const;

/** xyflow 는 이동 전 좌표로 알리고 같은 글이면 다시 읽히지 않아 비우고, 캔버스 자체 영역이 알린다(WD-1046) */
const NO_MOVE_ANNOUNCEMENT = () => '';

/** 노드 안내문 — xyflow 가 키보드 켬/끔에 따라 default·keyboardDisabled 중 하나를 쓰므로 두 키에 같이 넣는다 (WD-918) */
const EDIT_NODE_DESC =
  '스텝 노드입니다. Enter 나 Space 로 스텝 설정을 열고, 설정을 연 스텝은 화살표 키로 옮깁니다(Shift 와 함께 누르면 크게). 노드 안 「다음 스텝 추가」·「스텝 삭제」 버튼은 Tab 키로 이동할 수 있습니다.';
const VIEW_NODE_DESC = '스텝 노드입니다. Enter 나 Space 로 스텝 정보를 엽니다.';

/** 수정 모드 안내문 (WD-918). 12.10 에는 handle.ariaLabel 키가 없어 연결점 이름은 바꾸지 않는다. */
const KO_ARIA_LABEL_CONFIG_EDIT = {
  ...KO_CONTROLS_LABELS,
  'node.a11yDescription.default': EDIT_NODE_DESC,
  'node.a11yDescription.keyboardDisabled': EDIT_NODE_DESC,
  'node.a11yDescription.ariaLiveMessage': NO_MOVE_ANNOUNCEMENT,
  'edge.a11yDescription.default':
    '스텝 사이 연결입니다. Enter 나 Space 로 연결을 고른 뒤 Delete 나 Backspace 로 끊고, Escape 로 선택을 풉니다.',
};

/** 보기 모드 안내문 — 조작할 수 없으므로 설명만 읽힌다 (WD-918) */
const KO_ARIA_LABEL_CONFIG_VIEW = {
  ...KO_CONTROLS_LABELS,
  'node.a11yDescription.default': VIEW_NODE_DESC,
  'node.a11yDescription.keyboardDisabled': VIEW_NODE_DESC,
  'node.a11yDescription.ariaLiveMessage': NO_MOVE_ANNOUNCEMENT,
  'edge.a11yDescription.default': '스텝 사이 연결입니다.',
};
