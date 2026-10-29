import { type EdgeProps, getBezierPath } from '@xyflow/react';
import { Plus, X } from 'lucide-react';

export interface AddStepEdgeData {
  readOnly?: boolean;
  onInsertBetween?: (sourceId: string, targetId: string) => void;
  /** 연결 끊기 — 선택된 연결선에만 버튼을 보인다(WD-346) */
  onRemoveEdge?: (sourceId: string, targetId: string) => void;
  [key: string]: unknown;
}

/** 연결선 가운데의 둥근 아이콘 버튼 — 클릭이 캔버스(엣지 선택)로 번지지 않게 막는다 */
function EdgeIconButton({
  label,
  className,
  onClick,
  children,
}: {
  label: string;
  className: string;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick();
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
              onClick={() => edgeData?.onInsertBetween?.(source, target)}
            >
              <Plus className="h-3 w-3" />
            </EdgeIconButton>
            {selected && (
              <EdgeIconButton
                label="연결 끊기"
                className="text-destructive hover:bg-destructive hover:text-white"
                onClick={() => edgeData?.onRemoveEdge?.(source, target)}
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
