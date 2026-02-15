import { useParams, useNavigate } from 'react-router-dom';
import { useExecution, usePipeline } from '../../hooks/queries/usePipelines';
import { Button } from '../../components/ui/button';
import { Card } from '../../components/ui/card';
import { Skeleton } from '../../components/ui/skeleton';
import { DagViewer } from '../../components/pipeline/DagViewer';
import { ExecutionStatus } from '../../components/pipeline/ExecutionStatus';
import { ArrowLeft } from 'lucide-react';

export function ExecutionDetailPage() {
  const { id, execId } = useParams<{ id: string; execId: string }>();
  const navigate = useNavigate();
  const pipelineId = Number(id);
  const executionId = Number(execId);

  const { data: execution, isLoading: execLoading } = useExecution(pipelineId, executionId);
  const { data: pipeline } = usePipeline(pipelineId);

  if (execLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  if (!execution) {
    return (
      <div className="flex flex-col items-center justify-center py-12">
        <p className="text-muted-foreground">실행 정보를 찾을 수 없습니다.</p>
        <Button className="mt-4" onClick={() => navigate(`/pipelines/${pipelineId}`)}>
          파이프라인으로 돌아가기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => navigate(`/pipelines/${pipelineId}`)}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div>
          <h1 className="text-2xl font-bold">{execution.pipelineName} - 실행 #{execution.id}</h1>
          <p className="text-muted-foreground">파이프라인 실행 상세</p>
        </div>
      </div>

      {pipeline && (
        <Card className="p-4">
          <h2 className="text-lg font-semibold mb-3">실행 DAG</h2>
          <DagViewer
            steps={pipeline.steps}
            stepExecutions={execution.stepExecutions}
          />
        </Card>
      )}

      <ExecutionStatus execution={execution} />
    </div>
  );
}
