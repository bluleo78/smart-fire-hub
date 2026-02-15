import { useState, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { usePipeline, useExecutePipeline, useExecutions } from '../../hooks/queries/usePipelines';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Card } from '../../components/ui/card';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { PipelineDagTab } from './tabs/PipelineDagTab';
import { PipelineStepsTab } from './tabs/PipelineStepsTab';
import { PipelineExecutionsTab } from './tabs/PipelineExecutionsTab';
import { Play, ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';
import { formatDate } from '../../lib/formatters';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

export default function PipelineDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const pipelineId = Number(id);

  const { data: pipeline, isLoading } = usePipeline(pipelineId);
  const { data: executions } = useExecutions(pipelineId);
  const executePipeline = useExecutePipeline(pipelineId);

  const [activeTab, setActiveTab] = useState('dag');

  const handleExecute = useCallback(async () => {
    try {
      await executePipeline.mutateAsync();
      toast.success('파이프라인 실행이 시작되었습니다.');
      setActiveTab('executions');
    } catch (error) {
      if (axios.isAxiosError(error) && error.response?.data) {
        const errData = error.response.data as ErrorResponse;
        toast.error(errData.message || '파이프라인 실행에 실패했습니다.');
      } else {
        toast.error('파이프라인 실행에 실패했습니다.');
      }
    }
  }, [executePipeline, setActiveTab]);

  if (isLoading) {
    return (
      <div className="space-y-6">
        <Skeleton className="h-8 w-64" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  if (!pipeline) {
    return (
      <div className="flex flex-col items-center justify-center py-12">
        <p className="text-muted-foreground">파이프라인을 찾을 수 없습니다.</p>
        <Button className="mt-4" onClick={() => navigate('/pipelines')}>
          목록으로 돌아가기
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-4">
        <Button variant="ghost" size="icon" onClick={() => navigate('/pipelines')}>
          <ArrowLeft className="h-5 w-5" />
        </Button>
        <div className="flex-1">
          <div className="flex items-center gap-3">
            <h1 className="text-2xl font-bold">{pipeline.name}</h1>
            <Badge variant={pipeline.isActive ? 'default' : 'secondary'}>
              {pipeline.isActive ? '활성' : '비활성'}
            </Badge>
          </div>
          {pipeline.description && (
            <p className="text-muted-foreground mt-1">{pipeline.description}</p>
          )}
        </div>
        <Button onClick={handleExecute} disabled={executePipeline.isPending}>
          <Play className="mr-2 h-4 w-4" />
          {executePipeline.isPending ? '실행 중...' : '실행'}
        </Button>
      </div>

      <Card className="p-4">
        <div className="grid grid-cols-3 gap-4 text-sm">
          <div>
            <span className="text-muted-foreground">생성자:</span> {pipeline.createdBy}
          </div>
          <div>
            <span className="text-muted-foreground">스텝 수:</span> {pipeline.steps.length}
          </div>
          <div>
            <span className="text-muted-foreground">생성일:</span> {formatDate(pipeline.createdAt)}
          </div>
          {pipeline.updatedBy && (
            <div>
              <span className="text-muted-foreground">수정자:</span> {pipeline.updatedBy}
            </div>
          )}
        </div>
      </Card>

      <Tabs value={activeTab} onValueChange={setActiveTab}>
        <TabsList>
          <TabsTrigger value="dag">DAG</TabsTrigger>
          <TabsTrigger value="steps">스텝</TabsTrigger>
          <TabsTrigger value="executions">실행</TabsTrigger>
        </TabsList>

        {activeTab === 'dag' && <PipelineDagTab steps={pipeline.steps} />}
        {activeTab === 'steps' && <PipelineStepsTab steps={pipeline.steps} />}
        {activeTab === 'executions' && (
          <PipelineExecutionsTab executions={executions} pipelineId={pipelineId} />
        )}
      </Tabs>
    </div>
  );
}
