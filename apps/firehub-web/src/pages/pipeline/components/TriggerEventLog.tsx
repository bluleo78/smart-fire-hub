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
import { useTriggerEvents } from '@/hooks/queries/usePipelines';
import { formatDate } from '@/lib/formatters';
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

/**
 * 이벤트에 남은 사유(detail.reason)를 꺼낸다(WD-152). 건너뜀·오류 이벤트는 서버가 한국어 사유를 남기므로 배지 아래에 보여 준다 — 예전엔
 * 「건너뜀」 배지만 보여 왜 실행되지 않았는지 알 수 없었다. detail 은 자유 형식 JSON 이라 문자열일 때만 쓰고, 예외 원문(detail.error)은
 * 내부 구조를 드러낼 수 있어 보여 주지 않는다. WD-152 이전에 쌓인 영어 사유는 그대로 보인다(이관하지 않음).
 */
function getEventReason(event: TriggerEventResponse): string | null {
  const reason = event.detail?.reason;
  return typeof reason === 'string' && reason.trim() !== '' ? reason : null;
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
        {events.map((event) => {
          const reason = getEventReason(event);
          return (
            <TableRow key={event.id}>
              <TableCell className="text-sm">{formatDate(event.createdAt)}</TableCell>
              <TableCell className="text-sm">{event.triggerName}</TableCell>
              <TableCell>
                <Badge variant={getEventBadgeVariant(event.eventType)}>
                  {getEventLabel(event.eventType)}
                </Badge>
                {/* 사유는 배지 아래 보조 문구로 둔다 — 긴 문장도 표 너비 안에서 줄바꿈되게 whitespace-normal 을 준다 */}
                {reason && (
                  <p className="mt-1 text-xs text-muted-foreground whitespace-normal">{reason}</p>
                )}
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
          );
        })}
      </TableBody>
    </Table>
  );
}
