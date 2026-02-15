import { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { usePipeline, useExecutePipeline, useExecutions } from '../../hooks/queries/usePipelines';
import { Button } from '../../components/ui/button';
import { Badge } from '../../components/ui/badge';
import { Card } from '../../components/ui/card';
import { Skeleton } from '../../components/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../components/ui/table';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { DagViewer } from '../../components/pipeline/DagViewer';
import { Play, ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';
import type { ErrorResponse } from '../../types/auth';
import axios from 'axios';

export function PipelineDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const pipelineId = Number(id);

  const { data: pipeline, isLoading } = usePipeline(pipelineId);
  const { data: executions } = useExecutions(pipelineId);
  const executePipeline = useExecutePipeline(pipelineId);

  const [activeTab, setActiveTab] = useState('dag');

  const handleExecute = async () => {
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
  };

  const formatDate = (dateStr: string) => {
    return new Date(dateStr).toLocaleString('ko-KR');
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
      PENDING: 'outline',
      RUNNING: 'default',
      COMPLETED: 'secondary',
      FAILED: 'destructive',
      CANCELLED: 'outline',
    };
    const labels: Record<string, string> = {
      PENDING: '대기',
      RUNNING: '실행중',
      COMPLETED: '완료',
      FAILED: '실패',
      CANCELLED: '취소됨',
    };
    return <Badge variant={variants[status] || 'outline'}>{labels[status] || status}</Badge>;
  };

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

        <TabsContent value="dag" className="space-y-4">
          <Card className="p-4">
            <DagViewer steps={pipeline.steps} />
          </Card>
        </TabsContent>

        <TabsContent value="steps" className="space-y-4">
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>이름</TableHead>
                  <TableHead>스크립트 타입</TableHead>
                  <TableHead>출력 데이터셋</TableHead>
                  <TableHead>의존성</TableHead>
                  <TableHead>순서</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pipeline.steps.map((step) => (
                  <TableRow key={step.id}>
                    <TableCell className="font-medium">{step.name}</TableCell>
                    <TableCell>
                      <Badge variant="outline">{step.scriptType}</Badge>
                    </TableCell>
                    <TableCell>{step.outputDatasetName}</TableCell>
                    <TableCell>
                      {step.dependsOnStepNames.length > 0
                        ? step.dependsOnStepNames.join(', ')
                        : '-'}
                    </TableCell>
                    <TableCell>{step.stepOrder}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </TabsContent>

        <TabsContent value="executions" className="space-y-4">
          <div className="rounded-md border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>ID</TableHead>
                  <TableHead>상태</TableHead>
                  <TableHead>실행자</TableHead>
                  <TableHead>시작</TableHead>
                  <TableHead>완료</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {executions && executions.length > 0 ? (
                  executions.map((exec) => (
                    <TableRow
                      key={exec.id}
                      className="cursor-pointer hover:bg-muted/50 transition-colors"
                      onClick={() => navigate(`/pipelines/${pipelineId}/executions/${exec.id}`)}
                    >
                      <TableCell className="font-mono">#{exec.id}</TableCell>
                      <TableCell>{getStatusBadge(exec.status)}</TableCell>
                      <TableCell>{exec.executedBy}</TableCell>
                      <TableCell>{exec.startedAt ? formatDate(exec.startedAt) : '-'}</TableCell>
                      <TableCell>{exec.completedAt ? formatDate(exec.completedAt) : '-'}</TableCell>
                    </TableRow>
                  ))
                ) : (
                  <TableRow>
                    <TableCell colSpan={5} className="text-center text-muted-foreground">
                      실행 기록이 없습니다.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
