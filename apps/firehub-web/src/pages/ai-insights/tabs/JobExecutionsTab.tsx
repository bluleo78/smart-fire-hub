import { FileDown, Loader2 } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { toast } from 'sonner';

import type { ProactiveJobExecution } from '@/api/proactive';
import { proactiveApi } from '@/api/proactive';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useJobExecutions } from '@/hooks/queries/useProactiveMessages';
import { downloadBlob } from '@/lib/download';
import { classifyError } from '@/lib/error-classifier';
import { formatDate, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { getSections } from '@/lib/proactive-utils';
import { cn } from '@/lib/utils';

const REMARK_PLUGINS = [remarkGfm];

function calcDuration(startedAt: string, completedAt: string): string {
  const ms = new Date(completedAt).getTime() - new Date(startedAt).getTime();
  if (ms < 1000) return `${ms}ms`;
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}초`;
  return `${Math.floor(s / 60)}분 ${s % 60}초`;
}

function ExecutionResultView({ execution, jobId }: { execution: ProactiveJobExecution; jobId: number }) {
  const [downloading, setDownloading] = useState(false);

  const handleDownloadPdf = useCallback(async () => {
    setDownloading(true);
    try {
      const response = await proactiveApi.downloadExecutionPdf(jobId, execution.id);
      downloadBlob(`report-${execution.id}.pdf`, response.data as Blob);
    } catch {
      toast.error('PDF 다운로드에 실패했습니다.');
    } finally {
      setDownloading(false);
    }
  }, [jobId, execution.id]);

  if (execution.status === 'RUNNING') {
    return (
      <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" />
        <span className="text-sm">실행 중...</span>
      </div>
    );
  }

  if (execution.status === 'FAILED') {
    const classified = classifyError(execution.errorMessage);
    return (
      <div className="p-4">
        <div className="rounded-lg border border-destructive/50 p-4 space-y-2">
          <div className="flex items-center gap-2">
            <span>{classified.icon}</span>
            <span className="text-sm font-semibold text-destructive">{classified.label}</span>
          </div>
          <p className="text-sm text-muted-foreground">
            {execution.errorMessage ?? '알 수 없는 오류가 발생했습니다.'}
          </p>
          <p className="text-xs text-muted-foreground/70 border-t pt-2">
            💡 {classified.guide}
          </p>
        </div>
      </div>
    );
  }

  if (!execution.result) {
    return (
      <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
        결과가 없습니다.
      </div>
    );
  }

  const sections = getSections(execution.result);

  if (sections.length === 0) {
    return (
      <div className="p-4 text-sm text-muted-foreground">결과 내용이 없습니다.</div>
    );
  }

  return (
    <div className="p-4 space-y-4">
      <div className="flex justify-end">
        <Button
          variant="outline"
          size="sm"
          onClick={handleDownloadPdf}
          disabled={downloading}
        >
          {downloading ? (
            <Loader2 className="h-3.5 w-3.5 mr-1 animate-spin" />
          ) : (
            <FileDown className="h-3.5 w-3.5 mr-1" />
          )}
          PDF
        </Button>
      </div>
      {sections.map((section) => (
        <div key={section.key}>
          {sections.length > 1 && (
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
              {section.label}
            </p>
          )}
          <div className="prose prose-sm dark:prose-invert max-w-none prose-p:my-1 prose-headings:my-2 prose-ul:my-1 prose-ol:my-1 prose-li:my-0 text-sm leading-relaxed">
            <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
              {section.content}
            </ReactMarkdown>
          </div>
        </div>
      ))}
    </div>
  );
}

interface JobExecutionsTabProps {
  jobId: number;
}

export default function JobExecutionsTab({ jobId }: JobExecutionsTabProps) {
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [limit, setLimit] = useState(20);

  const [refetchInterval, setRefetchInterval] = useState<number | false>(false);
  const { data: executions = [], isLoading } = useJobExecutions(
    jobId,
    { limit, offset: 0 },
    { refetchInterval },
  );

  const hasRunning = executions.some((e) => e.status === 'RUNNING');
  useEffect(() => {
    setRefetchInterval(hasRunning ? 5000 : false);
  }, [hasRunning]);

  const selected = executions.find((e) => e.id === selectedId) ?? null;

  return (
    <div className="flex flex-col" style={{ height: 'calc(100vh - 280px)' }}>
      {/* 상단: 실행 목록 */}
      <div className="h-[220px] overflow-auto border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>실행 시간</TableHead>
              <TableHead>상태</TableHead>
              <TableHead>소요 시간</TableHead>
              <TableHead>전달 채널</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : executions.length > 0 ? (
              executions.map((exec) => (
                <TableRow
                  key={exec.id}
                  className={cn(
                    'cursor-pointer',
                    selectedId === exec.id && 'border-l-2 border-l-primary bg-muted/50',
                  )}
                  onClick={() => setSelectedId(exec.id)}
                >
                  <TableCell className="text-sm">
                    {formatDate(exec.startedAt)} ({timeAgo(exec.startedAt)})
                  </TableCell>
                  <TableCell>
                    <Badge variant={getStatusBadgeVariant(exec.status)}>
                      {getStatusLabel(exec.status)}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-sm">
                    {exec.completedAt ? calcDuration(exec.startedAt, exec.completedAt) : '-'}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-1 flex-wrap">
                      {exec.deliveredChannels?.map((ch) => (
                        <Badge key={ch} variant="outline" className="text-xs">
                          {ch}
                        </Badge>
                      ))}
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={4} message="실행 이력이 없습니다." />
            )}
          </TableBody>
        </Table>
      </div>

      {executions.length >= limit && (
        <div className="py-2 flex justify-center">
          <Button variant="ghost" size="sm" onClick={() => setLimit((l) => l + 20)}>
            더 보기
          </Button>
        </div>
      )}

      {/* 하단: 결과 뷰 */}
      <div className="flex-1 overflow-auto border rounded-md mt-3">
        {selected ? (
          <ExecutionResultView execution={selected} jobId={jobId} />
        ) : (
          <div className="flex items-center justify-center h-full text-sm text-muted-foreground">
            실행을 선택하면 결과를 확인할 수 있습니다
          </div>
        )}
      </div>
    </div>
  );
}
