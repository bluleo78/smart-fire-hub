import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { formatDate } from '@/lib/formatters';
import { useTriggerEvents } from '@/hooks/queries/usePipelines';
import type { TriggerEventResponse } from '@/types/pipeline';

function getEventBadgeVariant(eventType: TriggerEventResponse['eventType']) {
  switch (eventType) {
    case 'FIRED':
      return 'default' as const;
    case 'SKIPPED':
      return 'secondary' as const;
    case 'ERROR':
      return 'destructive' as const;
    case 'MISSED':
      return 'outline' as const;
    default:
      return 'outline' as const;
  }
}

function getEventLabel(eventType: TriggerEventResponse['eventType']) {
  const labels: Record<string, string> = {
    FIRED: '발화',
    SKIPPED: '건너뜀',
    ERROR: '오류',
    MISSED: '누락',
  };
  return labels[eventType] || eventType;
}

interface TriggerEventLogProps {
  pipelineId: number;
}

export default function TriggerEventLog({ pipelineId }: TriggerEventLogProps) {
  const { data: events, isLoading } = useTriggerEvents(pipelineId);

  if (isLoading) {
    return <div className="text-sm text-muted-foreground p-4">이벤트 로딩 중...</div>;
  }

  if (!events || events.length === 0) {
    return (
      <div className="text-sm text-muted-foreground p-4 text-center">
        트리거 이벤트가 없습니다.
      </div>
    );
  }

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>시각</TableHead>
          <TableHead>트리거명</TableHead>
          <TableHead>이벤트</TableHead>
          <TableHead>실행 ID</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {events.map((event) => (
          <TableRow key={event.id}>
            <TableCell className="text-sm">{formatDate(event.createdAt)}</TableCell>
            <TableCell className="text-sm">{event.triggerName}</TableCell>
            <TableCell>
              <Badge variant={getEventBadgeVariant(event.eventType)}>
                {getEventLabel(event.eventType)}
              </Badge>
            </TableCell>
            <TableCell>
              {event.executionId ? (
                <Link
                  to={`/pipelines/${pipelineId}/executions/${event.executionId}`}
                  className="text-sm font-mono text-primary hover:underline"
                  onClick={(e) => e.stopPropagation()}
                >
                  #{event.executionId}
                </Link>
              ) : (
                <span className="text-sm text-muted-foreground">-</span>
              )}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
}
