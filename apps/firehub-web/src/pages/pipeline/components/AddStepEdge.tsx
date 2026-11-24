import { type EdgeProps, getBezierPath } from '@xyflow/react';
import { Plus, X } from 'lucide-react';

export interface AddStepEdgeData {
  readOnly?: boolean;
  /**
   * 스텝 삽입 — 세 번째 인자 detail 은 클릭 횟수(키보드로 합성된 click 은 0, 마우스는 1 이상).
   * PipelineCanvas 가 초점 이동을 키보드 경로로만 걸러내는 데 쓴다(WD-1034).
   */
  onInsertBetween?: (sourceId: string, targetId: string, detail?: number) => void;
  /** 연결 끊기 — 선택된 연결선에만 버튼을 보인다(WD-346). detail 은 「스텝 삽입」과 같다(WD-1034) */
  onRemoveEdge?: (sourceId: string, targetId: string, detail?: number) => void;
  [key: string]: unknown;
}

/** 초점을 연결선 요소로 옮긴다 — 버튼이 선택 해제로 DOM 에서 빠져도 초점이 body 로 떨어지지 않게(WD-1034) */
function focusEdgeElement(edgeId: string) {
  // id 는 UUID·난수에서 나와 셀렉터 주입은 어렵지만, 방어로 CSS.escape 를 지난다(PipelineCanvas 와 동일)
  const edgeEl = document.querySelector<SVGElement>(`.react-flow__edge[data-id="${CSS.escape(edgeId)}"]`);
  if (!edgeEl) return; // 연결선 요소를 못 찾으면 아무것도 하지 않는다
  // 초점 이동에 tabIndex 이 필요하면 잠시 넣었다가 focus 후 원래대로 되돌린다 —
  // 원래부터 tabIndex 있던 요소는 건드리지 않는다(이미 포커스 가능하면 속성을 바꾸지 않는다).
  const hadTabIndex = edgeEl.hasAttribute('tabindex');
  if (!hadTabIndex) edgeEl.setAttribute('tabindex', '-1');
  // preventScroll — 초점 대상은 방금 누른 버튼 옆 연결선이라 이미 보이고,
  // 캔버스 스크롤이 React Flow 의 자체 이동과 겹치지 않게 한다(StepConfigPanel·PipelineCanvas 과 동일).
  edgeEl.focus({ preventScroll: true });
  if (!hadTabIndex) edgeEl.removeAttribute('tabindex');
}

/** 연결선 가운데의 둥근 아이콘 버튼 — 클릭이 캔버스(엣지 선택)로 번지지 않게 막는다 */
function EdgeIconButton({
  label,
  className,
  edgeId,
  onClick,
  children,
}: {
  label: string;
  className: string;
  /** 이 버튼이 속한 연결선 id — Escape 때 초점 이동 대상을 찾는 데 쓴다(WD-1034) */
  edgeId: string;
  /** 클릭 횟수 detail 을 함께 넘긴다 — 키보드 합성 click 은 0, 마우스는 1 이상이라 초점 이동을 가를 수 있다(WD-1034) */
  onClick: (detail: number) => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick(e.detail);
      }}
      // 키 선택 처리가 연결선으로 번지지 않게 Enter·Space 의 keydown 전파만 끊는다(WD-1009).
      // React Flow EdgeWrapper.onKeyDown 은 `.nokey`·입력 요소를 보지 않고 Enter/Space 면
      // 이 연결선을 선택하고(Escape 면 선택을 풀고) 다른 연결선·노드 선택을 풀어 버린다. 버튼의 기본 동작(click 합성)은 그대로 둔다.
      // Escape 는 앱 수준 처리(다이얼로그 닫기 등)가 죽지 않도록 일부러 버블시킨다.
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') e.stopPropagation();
        // Escape: 전파는 막지 않고 초점만 연결선으로 옮긴다(WD-1034).
        // 이벤트 순서는 핸들러 → 전파 → xyflow 선택 해제 → 렌더이고, xyflow 는 전파 중
        // EdgeWrapper.onKeyDown 에서 edgeRef.blur() 로 방금 초점 준 연결선을 다시 블러 시키므로,
        // dispatch 가 끝난 뒤 마이크로태스크에서 한 번 더 초점을 연결선으로 옮긴다 —
        // 버튼이 선택 해제로 DOM 에서 빠져도 초점이 body 로 떨어지지 않는다(WCAG 2.4.3).
        else if (e.key === 'Escape') {
          focusEdgeElement(edgeId);
          queueMicrotask(() => focusEdgeElement(edgeId));
        }
      }}
      className={`flex h-5 w-5 items-center justify-center rounded-full border bg-background shadow-sm ${className}`}
      title={label}
      aria-label={label}
    >
      {children}
    </button>
  );
}

export function AddStepEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  style,
  markerEnd,
  source,
  target,
  data,
  selected,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  });

  const edgeData = data as AddStepEdgeData | undefined;

  return (
    <>
      <path
        id={id}
        style={style}
        className="react-flow__edge-path"
        d={edgePath}
        markerEnd={markerEnd as string}
      />
      {!edgeData?.readOnly && (
        // 선택되면 「연결 끊기」 버튼을 옆에 붙인다 — 키보드 Delete 를 모르는 사용자·모바일에서도 끊을 수 있게
        <foreignObject
          width={selected ? 44 : 20}
          height={20}
          x={labelX - 10}
          y={labelY - 10}
          className="overflow-visible"
          requiredExtensions="http://www.w3.org/1999/xhtml"
        >
          <div className="flex gap-1">
            <EdgeIconButton
              label="스텝 삽입"
              className="text-muted-foreground hover:bg-primary hover:text-primary-foreground"
              edgeId={id}
              onClick={(detail) => edgeData?.onInsertBetween?.(source, target, detail)}
            >
              <Plus className="h-3 w-3" />
            </EdgeIconButton>
            {selected && (
              <EdgeIconButton
                label="연결 끊기"
                className="text-destructive hover:bg-destructive hover:text-white"
                edgeId={id}
                onClick={(detail) => edgeData?.onRemoveEdge?.(source, target, detail)}
              >
                <X className="h-3 w-3" />
              </EdgeIconButton>
            )}
          </div>
        </foreignObject>
      )}
    </>
  );
}
