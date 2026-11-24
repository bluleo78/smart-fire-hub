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
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';

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
import { applyNodeOverrides } from '../utils/node-overrides';
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
 * 초점 이동 재시도 상한(WD-1034) — 새 노드는 xyflow 의 치수 측정 전까지 visibility: hidden 이라 focus() 가 무시되고,
 * 프레임마다 다시 시도해야 한다. 60Hz 기준 약 0.5초 — 측정·이름 입력 초점 효과보다 넉넉히 길게, 대신 무한 루프는 막는다.
 * 시도 횟수는 초점 예약(PendingNodeFocus.attempts)에 남겨 steps 가 바뀌어도 리셋되지 않으므로,
 * 체인이 새로 띄워져도 한 예약 전체가 이 한도로 막힌다.
 */
const MAX_FOCUS_ATTEMPTS = 30;

/** 초점 이동 예약 하나 — 대상 노드 id 와, 이 예약이 쓴 재시도 횟수를 함께 든다(WD-1034) */
interface PendingNodeFocus {
  id: string;
  /**
   * 이 예약이 쓴 재시도 횟수 — effect 본문이 아니라 예약에 남겨 두는 것이 중요하다.
   * steps 가 바뀌면 effect 가 다시 돌며 재시도 체인을 새로 띄우는데, 그때마다 0 으로 리셋되면
   * 상한이 무력화되어 예약이 오래 남아 뒤에 엉뚱한 초점 이동이 일어난다.
   */
  attempts: number;
}

/**
 * 초점 이동(WD-1034, WCAG 2.4.3) — 예약한 노드를 data-id 로 찾아 초점을 옮기고, 실제로 옮겨갔는지 돌려준다.
 * 요소가 아직 안 보이면(visibility: hidden) focus() 가 조용히 무시되므로 activeElement 로 확인한다.
 * id 는 UUID·난수에서 나온 값이라 셀렉터 주입은 어렵지만, 방어로 CSS.escape 를 지난다(StepConfigPanel 과 동일).
 * 초점 이동에 tabIndex 이 필요하면 잠시 넣었다가 초점 후 원래대로 되돌린다 —
 * 원래부터 tabIndex 있던 요소는 건드리지 않는다(이미 포커스 가능하면 속성을 바꾸지 않는다).
 */
function focusNodeById(id: string): boolean {
  const el = document.querySelector<HTMLElement>(`.react-flow__node[data-id="${CSS.escape(id)}"]`);
  if (!el) return false;
  const hadTabIndex = el.hasAttribute('tabindex');
  if (!hadTabIndex) el.setAttribute('tabindex', '-1');
  // preventScroll — 초점 대상은 사용자가 방금 조작한 캔버스 안이라 스크롤이 필요 없고,
  // 캔버스 스크롤이 React Flow 의 자체 이동과 겹치지 않게 한다(StepConfigPanel·WD-1044 와 동일).
  el.focus({ preventScroll: true });
  if (!hadTabIndex) el.removeAttribute('tabindex');
  return document.activeElement === el;
}

/**
 * 키보드에서 온 activation 인가 — 버튼의 합성 click 은 detail 0(키보드), 마우스는 1 이상이고,
 * 키보드 Delete 로 연결선을 끊는 경로는 detail 자체가 없다. 초점 이동 예약은 이 쪽에서만 한다
 * (설계 비목표: 마우스 클릭 경로의 초점 이동 변경 — WD-1034).
 */
function isKeyboardActivation(detail?: number): boolean {
  return detail === undefined || detail === 0;
}

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
      // 선택 여부는 여기서 고정 false — 선택 노드만 applyNodeOverrides 가 덧씌운다(WD-1074, 화살표 키 이동 WD-1046)
      selected: false,
      data: {
        // 이름이 없으면 연결선 라벨·이동 알림과 같이 「스텝 #순번」으로 부른다 — 스크린리더가 어디서나 같은 이름을 듣게 (WD-1049)
        label: stepDisplayName(step.name, index),
        isUnnamed: isUnnamedStep(step.name),
        description: step.description || undefined,
        scriptType: step.scriptType,
        stepNumber: index + 1,
        isSelected: false,
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
  [state.steps, state.validationErrors, outgoingSet, executionData, readOnly, dispatch]);

  /** 선택·끌기 덧씌우기 — 선택 노드와 끄는 노드만 새 객체라 선택해도 나머지 노드는 다시 그리지 않는다(WD-1074) */
  const nodes = useMemo<StepNodeType[]>(
    () => applyNodeOverrides(baseNodes, state.selectedStepId, liveDragOverride),
    [baseNodes, state.selectedStepId, liveDragOverride],
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

  /**
   * 초점 이동 예약(WD-1034, WCAG 2.4.3) — 「연결 끊기」 버튼·키보드 Delete 로 대상이 DOM 에서 빠지면
   * 브라우저가 초점을 body 로 되돌려 키보드 사용자가 캔버스에서 자리를 잃는다. 사라지기 전에 초점 대상을
   * 예약해 두고, steps 갱신 뒤 실행되는 effect 가 렌더 이후 실제 요소에 초점을 옮긴다.
   * 예약은 노드뿐이다 — 연결선 초점은 AddStepEdge.tsx 의 focusEdgeElement 가 직접 맡는다(WD-1034).
   * 파일 안에서만 쓴다(export 안 함 — Task 도 인터페이스로 이것만 본다).
   */
  const pendingFocusRef = useRef<PendingNodeFocus | null>(null);
  /**
   * 「스텝 삽입」 초점 예약(WD-1034) — 새 스텝의 tempId 는 dispatch 를 해야 알 수 있으므로,
   * 무엇이 새로 생겼는지 대조할 원본 steps 와 기준이 되는 targetId 를 함께 보관한다.
   * (설계 접근 2번: 새 노드는 커밋 이후에야 DOM 에 존재한다.)
   */
  const pendingInsertRef = useRef<{ beforeSteps: PipelineEditorState['steps']; targetId: string } | null>(null);
  /**
   * 초점 재시도 체인의 세대(WD-1034) — steps 가 바뀔 때마다 새 체인을 띄우는데, 이전 체인이 살아 있으면
   * 오래된 예약으로 초점이 다시 움직인다. 체인마다 번호를 하나씩 받아 두고, 매 프레임 그 번호가 최신인지
   * 확인해 오래된 체인은 스스로 멈춘다 — 사용자가 그 사이에 옮긴 초점을 되찾지 않게 하려는 것이다.
   */
  const focusChainSeqRef = useRef(0);

  /** 초점을 노드로 옮기게 예약한다 — 키보드 경로에서만 불린다(isKeyboardActivation 참고, WD-1034) */
  const queueNodeFocus = useCallback((tempId: string) => {
    pendingFocusRef.current = { id: tempId, attempts: 0 };
  }, []);

  /** 연결 끊기 — 연결선의 「연결 끊기」 버튼과 키보드 삭제가 같은 동작을 쓴다 */
  const onRemoveEdge = useCallback(
    (sourceId: string, targetId: string, detail?: number) => {
      // 끊긴 연결의 앞(source) 노드로 초점을 옮기게 — dispatch 전에 예약해야 갱신 effect 가 이를 본다.
      if (isKeyboardActivation(detail)) queueNodeFocus(sourceId);
      dispatch({ type: 'REMOVE_EDGE', payload: { sourceTempId: sourceId, targetTempId: targetId } });
    },
    [dispatch, queueNodeFocus],
  );

  const onInsertBetween = useCallback(
    (sourceId: string, targetId: string, detail?: number) => {
      // 새 스텝 노드는 이 dispatch 다음 렌더에야 존재하므로, 원본 steps 를 예약해 두고 effect 에서
      // 새로 생긴 tempId 를 찾아 초점을 옮긴다(설계 접근 2번 — pendingInsertRef 선언부).
      if (isKeyboardActivation(detail)) {
        pendingInsertRef.current = { beforeSteps: state.steps, targetId };
      }
      dispatch({ type: 'INSERT_STEP_BETWEEN', payload: { sourceTempId: sourceId, targetTempId: targetId } });
    },
    [dispatch, state.steps],
  );

  /**
   * 초점 이동 effect(WD-1034, WCAG 2.4.3) — steps 가 바뀔 때 예약된 초점 대상을 실제 DOM 요소로 옮긴다.
   * 「스텝 삽입」으로 생긴 새 노드는 이 렌더 이후에야 DOM 에 존재하므로 여기서 찾는다.
   * 요소가 아직 안 보이면(위 focusNodeById) requestAnimationFrame 으로 초점이 실제로 옮겨갈 때까지 다시 시도하고,
   * 한계에 닿으면 예약을 비운다. 이전 체인은 cleanup 취소와 세대 토큰 두 겹으로 끊는다(아래 체인 시작·return).
   */
  useEffect(() => {
    // 「스텝 삽입」: 원본 steps 와 대조해 새로 생긴 tempId 를 찾는다 — INSERT_STEP_BETWEEN 은 새 스텝을
    // 맨 뒤에 붙이므로 배열 위치가 아니라 추가된 것(diff)으로 찾고, target 이 그 스텝에 의존하는지로 확인한다.
    const insert = pendingInsertRef.current;
    if (insert) {
      pendingInsertRef.current = null;
      const added = state.steps.filter((s) => !insert.beforeSteps.some((b) => b.tempId === s.tempId));
      const target = state.steps.find((s) => s.tempId === insert.targetId);
      const newStep =
        added.length === 1 && target?.dependsOnTempIds.includes(added[0].tempId) ? added[0] : null;
      if (newStep) pendingFocusRef.current = { id: newStep.tempId, attempts: 0 };
    }

    const pending = pendingFocusRef.current;
    if (!pending) return;

    // 새 체인 — 세대 번호를 하나 올려 이전 체인(아직 프레임을 기다리고 있을 수 있는)이 스스로 멈추게 한다.
    // 이전 체인을 멈추지 않으면 오래된 예약이 최대 30프레임(≈0.5초, 백그라운드 탭에서는 프레임이
    // 멈춰 있다 한꺼번에 훅씬 뒤에 도착한다) 동안 초점 이동을 계속 시도해 사용자가 옮긴 초점을 되찾는다 —
    // 이 이슈가 고치려는 결함 자체가 이런 오래된 체인이다(WD-1034).
    const seq = ++focusChainSeqRef.current;
    let frame = 0;
    // 새 노드는 xyflow 가 치수를 측정하기 전 visibility: hidden 이라(위) 한 번에 안 되고,
    // 스텝 삽입 시 이름 입력에 초점을 주는 기존 효과(WD-545, 부모 effect)보다 뒤에 초점을 옮겨야 초점이 노드로 남는다
    // (effect 는 다음 훅에 이어 즉시, rAF 는 그보다 뒤에 — 그래서 프레임 단위 재시도가 그 뒤를 따른다).
    const attempt = () => {
      // 세대가 바뀌었으면(더 새 effect 가 체인을 띄웠거나 unmount 로 취소됐으면) 이 체인은 끝 —
      // 초점을 건드리지도 않고 물러난다. 오래된 체인의 초점 이동이 곧 이 결함이므로 맨 앞에서 끊는다.
      if (seq !== focusChainSeqRef.current) return;
      // 시도 횟수를 예약에 남긴다 — steps 가 바뀌어 이 effect 가 다시 돌아도 리셋되지 않아
      // 한 예약 전체가 MAX_FOCUS_ATTEMPTS 회로 막힌다.
      pending.attempts += 1;
      if (focusNodeById(pending.id)) {
        // 초점 이동 성공 — 예약을 비운다(이미 다른 예약이 들어왔다면 그 쪽은 지우지 않는다)
        if (pendingFocusRef.current === pending) pendingFocusRef.current = null;
      } else if (pending.attempts < MAX_FOCUS_ATTEMPTS) {
        // 아직 그려지지 않았거나 안 보여서 초점이 먹지 않았다 — 한 프레임 뒤 다시 시도한다
        frame = requestAnimationFrame(attempt);
      } else if (pendingFocusRef.current === pending) {
        // 한계까지 시도해도 못 옮겼다 — 예약을 비운다(남겨 두면 나중에 엉뚱한 초점 이동이 일어난다)
        pendingFocusRef.current = null;
      }
    };
    attempt();
    // 예약된 다음 프레임을 취소한다 — steps 가 다시 바뀌거나 unmount 될 때 이 체인이 살아 남으면
    // 오래된 예약의 초점 이동이 뒤에 그어진다(백그라운드 탭에선 프레임이 멈춰 있다 오랜 뒤에 한꺼번에
    // 도착하기도 한다). 두 겹(여기 취소 + 위 세대 검사)으로 오래된 초점 이동을 막는다(WD-1034).
    return () => cancelAnimationFrame(frame);
  }, [state.steps]);

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
    // 연결선을 하나씩 끊는다 — 각 끊기가 onRemoveEdge 안에서 초점을 앞 노드로 옮기게 예약하며(키보드 경로),
    // 여러 연결선이 한 번에 끊기면 마지막 예약이 남아 그 앞 노드로 초점이 간다(WD-1034).
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
