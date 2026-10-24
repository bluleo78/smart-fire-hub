/**
 * ReportViewerPage — HTML 리포트 전체 화면 뷰어
 *
 * URL: /ai-insights/jobs/:jobId/executions/:executionId/report
 * 백엔드가 반환하는 HTML 리포트를 iframe srcdoc으로 렌더링한다.
 * sandbox 속성으로 스크립트 실행을 차단하여 XSS를 방지한다.
 * 상단 바에 뒤로가기, PDF 다운로드, 인쇄 버튼을 제공한다.
 */

import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, ExternalLink, FileDown, Loader2, Printer } from 'lucide-react';
import { useRef } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';

import { proactiveApi } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Button } from '@/components/ui/button';
import { useReportActions } from '@/hooks/useReportActions';
import { isForbiddenError, isNotFoundError, isReportLevelBlocked } from '@/lib/api-error';
import { REPORT_NOT_FOUND_NOTICE } from '@/lib/proactive-utils';

import { ProactiveReadForbiddenNotice } from './components/ProactiveReadForbiddenNotice';
import { ReportLevelBlockedNotice } from './components/ReportLevelBlockedNotice';

export default function ReportViewerPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const jobIdNum = Number(jobId);
  const executionIdNum = Number(executionId);
  // 경로 파라미터가 숫자가 아닌 경우(잘못된 URL 직접 접근, 손상된 링크 등) — 별도 에러 상태로 분기한다.
  // "리포트가 없습니다" 빈 상태는 정상적으로 리포트가 없는 경우를 위한 것이라, NaN을 그대로 흘려보내면
  // 헤더에 "리포트 #NaN", 링크에 "/ai-insights/jobs/NaN"처럼 깨진 UI가 노출된다.
  const isInvalidParams = isNaN(jobIdNum) || isNaN(executionIdNum);

  // HTML 리포트 조회
  const { data: htmlResponse, isLoading, isError, error } = useQuery({
    queryKey: ['execution-html', jobIdNum, executionIdNum],
    queryFn: () => proactiveApi.getExecutionHtml(jobIdNum, executionIdNum),
    enabled: !isInvalidParams,
  });

  const rawHtml = htmlResponse?.data ?? null;
  // 조회 권한 부족(403) — 「리포트를 불러올 수 없습니다」 대신 권한 안내를 보이는 기준 (WD-137). 다시 시도해도 같다.
  // 리포트 등급 차단(WD-198)은 같은 403 이지만 권한 요청 안내가 아니라 등급 안내다 — 먼저 가른다.
  const isLevelBlocked = isError && isReportLevelBlocked(error);
  const isForbidden = isError && isForbiddenError(error) && !isLevelBlocked;
  // 404 — 서버는 리포트가 없을 때와 작업 작성자가 아닐 때(받은 사람이 주소로 직접 연 경우)를 똑같이 404 로 숨긴다.
  // 「불러올 수 없습니다」는 일시 오류처럼 읽혀 다시 시도하게 만들므로, 두 경우를 함께 말하는 안내를 보인다 (WD-162).
  const isNotFound = isError && isNotFoundError(error);

  // PDF 다운로드 + 인쇄 공통 훅
  const { handleDownloadPdf, handlePrint, downloading } = useReportActions({
    jobId: jobIdNum,
    executionId: executionIdNum,
    iframeRef,
  });

  return (
    <div className="flex flex-col h-dvh bg-background">
      {/* 상단 고정 헤더 바 — 네비게이션 + 액션 버튼들 */}
      <div className="flex items-center justify-between px-4 py-3 border-b bg-background print:hidden">
        {/* 왼쪽: 뒤로가기 + 제목 */}
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => navigate(-1)}
            className="gap-1.5"
          >
            <ArrowLeft className="h-4 w-4" />
            뒤로
          </Button>
          <div className="h-5 w-px bg-border" />
          <h1 className="text-sm font-semibold text-foreground">
            {isInvalidParams ? '리포트' : `리포트 #${executionIdNum}`}
          </h1>
        </div>

        {/* 오른쪽: 액션 버튼들 */}
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handlePrint}
            disabled={isError || isLoading || !rawHtml}
            className="gap-1.5"
          >
            <Printer className="h-3.5 w-3.5" />
            인쇄
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handleDownloadPdf}
            disabled={downloading || isError || isLoading || !rawHtml}
            className="gap-1.5"
          >
            {downloading ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              <FileDown className="h-3.5 w-3.5" />
            )}
            PDF
          </Button>
        </div>
      </div>

      {/* 본문 영역 — 상태에 따라 잘못된 요청/로딩/에러/빈 상태/리포트를 렌더링 */}
      <div className="flex-1 overflow-hidden">
        {isInvalidParams && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">잘못된 요청입니다.</p>
            {/* jobId/executionId가 유효하지 않으므로 "작업 상세 보기" 같은 깨진 링크는 노출하지 않는다 */}
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              <ArrowLeft className="h-4 w-4" />
              돌아가기
            </Button>
          </div>
        )}

        {!isInvalidParams && isLoading && (
          <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span className="text-sm">리포트를 불러오는 중...</span>
          </div>
        )}

        {!isInvalidParams && isLevelBlocked && !isLoading && (
          <ReportLevelBlockedNotice testId="report-level-blocked" />
        )}

        {!isInvalidParams && isForbidden && !isLoading && (
          <ProactiveReadForbiddenNotice subject="리포트" testId="report-forbidden" />
        )}

        {!isInvalidParams && isNotFound && !isLoading && (
          <div
            className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground"
            data-testid="report-not-found"
          >
            <p className="text-sm text-center break-keep px-4">{REPORT_NOT_FOUND_NOTICE}</p>
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              <ArrowLeft className="h-4 w-4" />
              돌아가기
            </Button>
          </div>
        )}

        {!isInvalidParams && isError && !isForbidden && !isLevelBlocked && !isNotFound && !isLoading && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">리포트를 불러올 수 없습니다.</p>
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              <ArrowLeft className="h-4 w-4" />
              돌아가기
            </Button>
          </div>
        )}

        {!isInvalidParams && !isLoading && !isError && !rawHtml && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">리포트가 없습니다.</p>
            <Button variant="outline" size="sm" asChild>
              <Link to={`/ai-insights/jobs/${jobIdNum}`}>
                <ExternalLink className="h-3.5 w-3.5" />
                작업 상세 보기
              </Link>
            </Button>
          </div>
        )}

        {/* HTML 리포트를 공통 ReportIframe 컴포넌트로 렌더링 */}
        {rawHtml && (
          <ReportIframe ref={iframeRef} html={rawHtml} />
        )}
      </div>
    </div>
  );
}
