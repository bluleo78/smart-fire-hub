import { useMemo } from 'react';
import {
  ReactFlow,
  Background,
  Controls,
  type Node,
  type Edge,
  Position,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import type { PipelineStepResponse } from '../../types/pipeline';
import type { StepExecutionResponse } from '../../types/pipeline';

interface DagViewerProps {
  steps: PipelineStepResponse[];
  stepExecutions?: StepExecutionResponse[];
  onNodeClick?: (stepId: number) => void;
}

export function DagViewer({ steps, stepExecutions, onNodeClick }: DagViewerProps) {
  const { nodes, edges } = useMemo(() => {
    const stepByName = new Map(steps.map(s => [s.name, s]));
    const execByStepId = new Map(stepExecutions?.map(e => [e.stepId, e]) || []);

    const nodes: Node[] = steps.map((step, idx) => {
      const exec = execByStepId.get(step.id);
      let bgColor = '#f8f9fa';
      if (exec) {
        switch (exec.status) {
          case 'RUNNING': bgColor = '#dbeafe'; break;
          case 'COMPLETED': bgColor = '#dcfce7'; break;
          case 'FAILED': bgColor = '#fecaca'; break;
          case 'SKIPPED': bgColor = '#fef3c7'; break;
        }
      }
      return {
        id: step.name,
        data: { label: `${step.name}\n(${step.scriptType})` },
        position: { x: step.stepOrder * 250, y: idx * 100 },
        style: {
          background: bgColor,
          border: '1px solid #d1d5db',
          borderRadius: '8px',
          padding: '10px',
          fontSize: '12px',
          width: 180,
          whiteSpace: 'pre-line',
        },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
      };
    });

    const edges: Edge[] = [];
    steps.forEach(step => {
      step.dependsOnStepNames.forEach(depName => {
        const depStep = stepByName.get(depName);
        const depExec = depStep ? execByStepId.get(depStep.id) : undefined;
        edges.push({
          id: `${depName}-${step.name}`,
          source: depName,
          target: step.name,
          animated: depExec?.status === 'RUNNING',
        });
      });
    });

    return { nodes, edges };
  }, [steps, stepExecutions]);

  return (
    <div style={{ width: '100%', height: 400 }}>
      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodeClick={(_, node) => {
          const step = steps.find(s => s.name === node.id);
          if (step && onNodeClick) onNodeClick(step.id);
        }}
        fitView
        nodesDraggable={false}
      >
        <Background />
        <Controls />
      </ReactFlow>
    </div>
  );
}
