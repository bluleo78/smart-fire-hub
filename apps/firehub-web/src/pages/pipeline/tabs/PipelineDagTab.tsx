import { lazy, Suspense, memo } from 'react';
import { Card } from '../../../components/ui/card';
import { Skeleton } from '../../../components/ui/skeleton';
import type { PipelineStepResponse } from '../../../types/pipeline';

const DagViewer = lazy(() => import('../components/DagViewer').then(m => ({ default: m.DagViewer })));

interface PipelineDagTabProps {
  steps: PipelineStepResponse[];
}

export const PipelineDagTab = memo(function PipelineDagTab({ steps }: PipelineDagTabProps) {
  return (
    <Card className="p-4">
      <Suspense fallback={<Skeleton className="h-96 w-full" />}>
        <DagViewer steps={steps} />
      </Suspense>
    </Card>
  );
});
