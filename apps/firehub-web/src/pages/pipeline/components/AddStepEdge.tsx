import { type EdgeProps, getBezierPath } from '@xyflow/react';
import { Plus } from 'lucide-react';

export interface AddStepEdgeData {
  readOnly?: boolean;
  onInsertBetween?: (sourceId: string, targetId: string) => void;
  [key: string]: unknown;
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
        <foreignObject
          width={20}
          height={20}
          x={labelX - 10}
          y={labelY - 10}
          className="overflow-visible"
          requiredExtensions="http://www.w3.org/1999/xhtml"
        >
          <button
            onClick={(e) => {
              e.stopPropagation();
              edgeData?.onInsertBetween?.(source, target);
            }}
            className="flex h-5 w-5 items-center justify-center rounded-full border bg-background text-muted-foreground shadow-sm hover:bg-primary hover:text-primary-foreground"
            title="스텝 삽입"
          >
            <Plus className="h-3 w-3" />
          </button>
        </foreignObject>
      )}
    </>
  );
}
