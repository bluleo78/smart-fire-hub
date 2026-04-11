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

export default function ReportViewerPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const jobIdNum = Number(jobId);
  const executionIdNum = Number(executionId);

  // HTML 리포트 조회
  const { data: htmlResponse, isLoading, isError } = useQuery({
    queryKey: ['execution-html', jobIdNum, executionIdNum],
    queryFn: () => proactiveApi.getExecutionHtml(jobIdNum, executionIdNum),
    enabled: !isNaN(jobIdNum) && !isNaN(executionIdNum),
  });

  const rawHtml = htmlResponse?.data ?? null;

  // PDF 다운로드 + 인쇄 공통 훅
  const { handleDownloadPdf, handlePrint, downloading } = useReportActions({
    jobId: jobIdNum,
    executionId: executionIdNum,
    iframeRef,
  });

  return (
    <div className="flex flex-col h-screen bg-background">
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
            리포트 #{executionIdNum}
          </h1>
        </div>

        {/* 오른쪽: 액션 버튼들 */}
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={handlePrint}
            className="gap-1.5"
          >
            <Printer className="h-3.5 w-3.5" />
            인쇄
          </Button>
          <Button
            variant="outline"
            size="sm"
            onClick={handleDownloadPdf}
            disabled={downloading}
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

      {/* 본문 영역 — 상태에 따라 로딩/에러/빈 상태/리포트를 렌더링 */}
      <div className="flex-1 overflow-hidden">
        {isLoading && (
          <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span className="text-sm">리포트를 불러오는 중...</span>
          </div>
        )}

        {isError && !isLoading && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">리포트를 불러올 수 없습니다.</p>
            <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
              <ArrowLeft className="h-4 w-4 mr-1" />
              돌아가기
            </Button>
          </div>
        )}

        {!isLoading && !isError && !rawHtml && (
          <div className="flex flex-col items-center justify-center h-full gap-4 text-muted-foreground">
            <p className="text-sm">리포트가 없습니다.</p>
            <Button variant="outline" size="sm" asChild>
              <Link to={`/ai-insights/jobs/${jobIdNum}`}>
                <ExternalLink className="h-3.5 w-3.5 mr-1.5" />
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
