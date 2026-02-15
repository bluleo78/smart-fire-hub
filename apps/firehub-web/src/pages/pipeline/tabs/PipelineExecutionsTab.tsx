import { memo, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Badge } from '../../../components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '../../../components/ui/table';
import { formatDate, getStatusBadgeVariant, getStatusLabel } from '../../../lib/formatters';
import type { PipelineExecutionResponse } from '../../../types/pipeline';

interface PipelineExecutionsTabProps {
  executions: PipelineExecutionResponse[] | undefined;
  pipelineId: number;
}

export const PipelineExecutionsTab = memo(function PipelineExecutionsTab({
  executions,
  pipelineId,
}: PipelineExecutionsTabProps) {
  const navigate = useNavigate();

  const handleRowClick = useCallback(
    (executionId: number) => {
      navigate(`/pipelines/${pipelineId}/executions/${executionId}`);
    },
    [navigate, pipelineId]
  );

  return (
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
                onClick={() => handleRowClick(exec.id)}
              >
                <TableCell className="font-mono">#{exec.id}</TableCell>
                <TableCell>
                  <Badge variant={getStatusBadgeVariant(exec.status)}>
                    {getStatusLabel(exec.status)}
                  </Badge>
                </TableCell>
                <TableCell>{exec.executedBy}</TableCell>
                <TableCell>{formatDate(exec.startedAt)}</TableCell>
                <TableCell>{formatDate(exec.completedAt)}</TableCell>
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
  );
});
