import { Download, Zap } from 'lucide-react';
import { Link, useNavigate } from 'react-router-dom';

import { PendingButton } from '@/components/PendingButton';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { LoadMoreFailure } from '@/components/ui/load-more-failure';
import { SecurityLevelBadge } from '@/components/ui/SecurityLevelBadge';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { TableEmptyRow, TableErrorRow } from '@/components/ui/table-empty';
import { TableSkeletonRows } from '@/components/ui/table-skeleton';
import { useCanWriteProactive,useProactiveJobs, useReports } from '@/hooks/queries/useProactiveMessages';
import { useLoadMoreLimit } from '@/hooks/useLoadMoreLimit';
import { downloadReportPdf } from '@/hooks/useReportActions';
import { isForbiddenError } from '@/lib/api-error';
import { formatDate, timeAgo } from '@/lib/formatters';

import { ProactiveReadForbiddenNotice } from '../components/ProactiveReadForbiddenNotice';

/**
 * 생성된 리포트 목록 탭.
 *
 * 스마트 작업을 거치지 않고 리포트를 바로 찾을 수 있게 잡 횡단으로 나열한다.
 * 행 전체가 리포트 뷰어로 가는 마우스 클릭 타겟이고, 키보드로는 제목 링크로 연다(WD-335).
 * 잡 이름은 링크가 아닌 배지로만 표시한다 — 행 안에 목적지가 다른 링크가 둘이면 키보드 순회·스크린리더에서 모호해진다.
 */
export default function ReportListTab() {
  const navigate = useNavigate();
  const { limit, loadMore, pending, failed, settle } = useLoadMoreLimit();
  const {
    data: reports = [],
    isLoading,
    isError,
    isSuccess,
    isPlaceholderData,
    error,
  } = useReports({ limit, offset: 0 });
  // 「더 보기」 조회가 실패하면 렌더 중에 limit 을 마지막 성공 값으로 되돌려 보던 목록을 남긴다(WD-956) — 오류 렌더가 그려지지 않는다.
  settle({ isError, isSuccess, isPlaceholderData });
  // 조회 실패를 빈 목록으로 그리지 않는다 (WD-137) — 403 이면 권한 안내, 그 밖은 불러오기 실패 안내.
  const isForbidden = isError && isForbiddenError(error);
  // 빈 상태를 "잡이 없음" / "잡은 있으나 리포트 없음" 으로 가르기 위해 잡 목록도 본다.
  // 리포트가 하나라도 있거나 조회가 실패했으면(빈 상태를 그리지 않으므로) 이 분기 자체가 필요 없어 요청을 내지 않는다.
  const { data: jobs = [] } = useProactiveJobs({ enabled: !isLoading && !isError && reports.length === 0 });
  // 작업 생성은 서버가 proactive:write 로 막는다 — 권한이 없으면 눌러도 403 인 「스마트 작업 만들기」 CTA 를 숨긴다(WD-133).
  const { canWrite } = useCanWriteProactive();

  // 빈 상태는 두 갈래다 — 잡이 없는 사용자에게 "리포트가 없다"고만 하면 다음 행동을 알 수 없다
  const hasNoJobs = jobs.length === 0;
  const emptyMessage = hasNoJobs
    ? '스마트 작업을 만들면 생성된 리포트가 여기에 쌓입니다.'
    : '아직 생성된 리포트가 없습니다.';
  // 잡이 없는데 만들 권한도 없으면 이어갈 행동이 없으므로 CTA 를 두지 않는다(안내 문구만 남긴다).
  const emptyAction = hasNoJobs ? (
    canWrite ? (
      <Button size="sm" onClick={() => navigate('/ai-insights/jobs/new')}>
        스마트 작업 만들기
      </Button>
    ) : undefined
  ) : (
    <Button variant="outline" size="sm" onClick={() => navigate('/ai-insights/jobs')}>
      스마트 작업 보기
    </Button>
  );

  // 조회 권한이 없으면 표·빈 상태·「스마트 작업 만들기」 CTA 대신 권한 안내만 보인다 — 다시 시도해도 같다.
  if (isForbidden) {
    return <ProactiveReadForbiddenNotice subject="리포트" testId="reports-forbidden" />;
  }

  return (
    // 높이를 뷰포트에 고정하지 않는다 — 리포트가 몇 건뿐일 때 거대한 빈 상자가 남는다.
    // 대신 최대 높이만 제한해 목록이 길어지면 내부 스크롤이 생기게 한다.
    <div className="flex flex-col">
      <div className="max-h-[calc(100vh-280px)] overflow-auto border rounded-md">
        <Table>
          <TableHeader>
            <TableRow>
              {/* 제목 칸에 최소 폭을 둔다 — 없으면 좁은 화면에서 고정 폭 칸들에 밀려 40px 로 줄어 제목이 0px 이 됐다(WD-334).
                  넘치는 폭은 바깥 상자의 가로 스크롤로 본다. */}
              <TableHead className="min-w-[180px]">제목</TableHead>
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
                  <TableCell className="max-w-0 min-w-[180px]">
                    <div className="flex items-center gap-2 min-w-0">
                      {/* 키보드 접근성(WD-335): 행 클릭은 마우스로만 열리므로 제목을 뷰어 링크로 둔다 — Tab 으로 닿고 Enter 로 열린다.
                          행에 role="button" 을 주면 표의 행·칸 의미가 사라져 링크로 한다(WD-286 역할 목록과 같음).
                          클릭은 행 onClick 과 겹쳐 두 번 이동하지 않게 번지지 않게 막는다. */}
                      <Link
                        to={`/ai-insights/jobs/${report.jobId}/executions/${report.executionId}/report`}
                        className="text-sm font-medium truncate min-w-0 rounded-sm hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                        onClick={(e) => e.stopPropagation()}
                      >
                        {report.title}
                      </Link>
                      {/* 리포트 등급 배지(WD-198) — 열 수 없는 등급이면 서버가 요약을 비워 보낸다.
                          목록 칸이라 말줄임 + title(WD-658), 상한은 데이터셋 목록과 같게 둬 제목 폭을 남긴다(shrink-0: 제목이 먼저 줄어듦, WD-864) */}
                      {report.securityLevel && (
                        <SecurityLevelBadge level={report.securityLevel} truncate className="shrink-0 max-w-[5.25rem]" />
                      )}
                    </div>
                    {report.summary && (
                      <div className="text-xs text-muted-foreground truncate">{report.summary}</div>
                    )}
                  </TableCell>
                  {/* 작업명 배지는 칸 폭(헤더 200px)에서 말줄임한다 — 공백 없는 긴 작업명(최대 200자)이
                      칸을 늘려 생성일·액션 칸을 화면 밖으로 밀지 않게 한다. 전체 이름은 title 툴팁으로 본다 (WD-558) */}
                  <TableCell className="max-w-[200px]">
                    <Badge variant="outline" className="text-xs max-w-full" title={report.jobName}>
                      <span className="truncate">{report.jobName}</span>
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
            ) : isError ? (
              // 같은 키 재조회 실패면 이전 행이 남아 위 분기가 그대로 그린다. 「더 보기」(키가 바뀜) 실패는 보던 limit 으로 되돌려 이 갈래로 오지 않고 아래 「다시 시도」 안내로 알린다.
              <TableErrorRow colSpan={4} className="py-12" message="리포트 목록을 불러오지 못했습니다." />
            ) : (
              <TableEmptyRow colSpan={4} message={emptyMessage} emptyAction={emptyAction} />
            )}
          </TableBody>
        </Table>
      </div>

      {failed ? (
        <LoadMoreFailure onRetry={loadMore} pending={pending} />
      ) : (
        // 응답 대기 중(isPlaceholderData)에도 버튼을 남기고 pending 으로만 잠근다 — 사라지면 키보드 초점이 body 로 떨어진다(WD-1016).
        // isPlaceholderData 를 「더 보기 로딩 중」으로 읽는 것은 이 탭에서 키를 바꾸는 입력이 limit 뿐이기 때문이다 — 필터 등이 생기면 다시 검토한다.
        (reports.length >= limit || isPlaceholderData) && (
          <div className="py-2 flex justify-center">
            <PendingButton variant="ghost" size="sm" pending={isPlaceholderData} onClick={loadMore}>
              더 보기
            </PendingButton>
          </div>
        )
      )}
    </div>
  );
}
