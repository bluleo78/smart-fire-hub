import { Download, Zap } from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';

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
import { useProactiveJobs, useReports } from '@/hooks/queries/useProactiveMessages';
import { downloadReportPdf } from '@/hooks/useReportActions';
import { formatDate, timeAgo } from '@/lib/formatters';

/**
 * 생성된 리포트 목록 탭.
 *
 * 스마트 작업을 거치지 않고 리포트를 바로 찾을 수 있게 잡 횡단으로 나열한다.
 * 행 전체가 리포트 뷰어로 가는 단일 클릭 타겟이며, 잡 이름은 링크가 아닌 배지로만 표시한다
 * (행 안에 링크를 중첩하면 키보드 순회·스크린리더에서 목적지가 모호해진다).
 */
export default function ReportListTab() {
  const navigate = useNavigate();
  const [limit, setLimit] = useState(20);
  const { data: reports = [], isLoading } = useReports({ limit, offset: 0 });
  // 빈 상태를 "잡이 없음" / "잡은 있으나 리포트 없음" 으로 가르기 위해 잡 목록도 본다.
  // 리포트가 하나라도 있으면 이 분기 자체가 필요 없으므로 요청을 내지 않는다.
  const { data: jobs = [] } = useProactiveJobs({ enabled: !isLoading && reports.length === 0 });

  // 빈 상태는 두 갈래다 — 잡이 없는 사용자에게 "리포트가 없다"고만 하면 다음 행동을 알 수 없다
  const hasNoJobs = jobs.length === 0;
  const emptyMessage = hasNoJobs
    ? '스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.'
    : '아직 생성된 리포트가 없습니다.';
  const emptyAction = hasNoJobs ? (
    <Button size="sm" onClick={() => navigate('/ai-insights/jobs/new')}>
      스마트 작업 만들기
    </Button>
  ) : (
    <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/jobs')}>
      스마트 작업 보기
    </Button>
  );

  return (
    // 높이를 뷰포트에 고정하지 않는다 — 리포트가 몇 건뿐일 때 거대한 빈 상자가 남는다.
    // 대신 최대 높이만 제한해 목록이 길어지면 내부 스크롤이 생기게 한다.
    <div className="flex flex-col">
      <div className="max-h-[calc(100vh-280px)] overflow-auto border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>제목</TableHead>
              <TableHead className="w-[200px]">스마트 작업</TableHead>
              <TableHead className="w-[240px]">생성일</TableHead>
              {/* 아이콘 버튼 2개(각 h-8 + gap) 가 잘리지 않도록 고정 폭을 준다 —
                  퍼센트 폭은 제목 컬럼에 밀려 액션이 화면 밖으로 나간다 */}
              <TableHead className="w-[96px] text-center">액션</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              <TableSkeletonRows columns={4} rows={5} />
            ) : reports.length > 0 ? (
              reports.map((report) => (
                <TableRow
                  key={report.executionId}
                  className="cursor-pointer hover:bg-muted/50"
                  onClick={() =>
                    navigate(
                      `/ai-insights/jobs/${report.jobId}/executions/${report.executionId}/report`,
                    )
                  }
                >
                  {/* 제목 셀은 유동 폭 — 픽셀 고정 대신 셀 폭을 따라 truncate 되게 한다 */}
                  <TableCell className="max-w-0">
                    <div className="text-sm font-medium truncate">{report.title}</div>
                    {report.summary && (
                      <div className="text-xs text-muted-foreground truncate">{report.summary}</div>
                    )}
                  </TableCell>
                  <TableCell>
                    <Badge variant="outline" className="text-xs">
                      {report.jobName}
                    </Badge>
                  </TableCell>
                  <TableCell className="text-sm">
                    {formatDate(report.completedAt)} ({timeAgo(report.completedAt)})
                  </TableCell>
                  <TableCell className="text-center">
                    {/* hover-only로 숨기지 않는다 — 터치·키보드에서 접근 불가가 된다 */}
                    <div className="flex justify-center gap-1">
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label="PDF 다운로드"
                        onClick={(e) => {
                          e.stopPropagation();
                          void downloadReportPdf(report.jobId, report.executionId);
                        }}
                      >
                        <Download className="h-4 w-4" />
                      </Button>
                      {/* 잡 이름을 링크로 만들지 않는 대신, 잡으로 가는 경로를 액션으로 제공한다 */}
                      <Button
                        variant="ghost"
                        size="icon-sm"
                        aria-label="스마트 작업 보기"
                        onClick={(e) => {
                          e.stopPropagation();
                          navigate(`/ai-insights/jobs/${report.jobId}`);
                        }}
                      >
                        <Zap className="h-4 w-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))
            ) : (
              <TableEmptyRow colSpan={4} message={emptyMessage} emptyAction={emptyAction} />
            )}
          </TableBody>
        </Table>
      </div>

      {reports.length >= limit && (
        <div className="py-2 flex justify-center">
          <Button variant="ghost" size="sm" onClick={() => setLimit((l) => l + 20)}>
            더 보기
          </Button>
        </div>
      )}
    </div>
  );
}
