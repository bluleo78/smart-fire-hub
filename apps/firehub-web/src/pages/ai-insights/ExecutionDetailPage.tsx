/**
 * ExecutionDetailPage — 프로액티브 작업의 개별 실행 상세 페이지
 *
 * URL: /ai-insights/jobs/:jobId/executions/:executionId
 *
 * 실행 상태(RUNNING/COMPLETED/FAILED)에 따라 다른 UI를 렌더링한다.
 * - RUNNING: 스피너 + 폴링 안내 문구
 * - FAILED: 에러 분류 카드 (classifyError)
 * - COMPLETED: 요약(마크다운) + HTML 리포트(iframe) 또는 마크다운 섹션 폴백
 */

import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, FileDown, Loader2, Printer } from 'lucide-react';
import { useRef } from 'react';
import ReactMarkdown from 'react-markdown';
import { useNavigate, useParams } from 'react-router-dom';
import remarkGfm from 'remark-gfm';

import { proactiveApi } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { useExecution } from '@/hooks/queries/useProactiveMessages';
import { useReportActions } from '@/hooks/useReportActions';
import { classifyError } from '@/lib/error-classifier';
import { formatDate, formatDuration, getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { getSections } from '@/lib/proactive-utils';

/** 마크다운 렌더링용 prose 클래스 */
const PROSE_CLASSES = 'prose prose-sm dark:prose-invert max-w-none';
const REMARK_PLUGINS = [remarkGfm];

export default function ExecutionDetailPage() {
  const { jobId, executionId } = useParams<{ jobId: string; executionId: string }>();
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);

  const jobIdNum = Number(jobId);
  const executionIdNum = Number(executionId);

  // 실행 데이터 조회 — RUNNING 상태일 때 5초 폴링
  const { data: execution, isLoading } = useExecution(jobIdNum, executionIdNum);

  // HTML 리포트 조회 — COMPLETED 상태이고 result가 있을 때만 활성화
  const { data: htmlResponse } = useQuery({
    queryKey: ['execution-html', jobIdNum, executionIdNum],
    queryFn: () => proactiveApi.getExecutionHtml(jobIdNum, executionIdNum),
    enabled: execution?.status === 'COMPLETED' && execution?.result != null,
  });

  const rawHtml = htmlResponse?.data ?? null;

  // PDF 다운로드 + 인쇄 공통 훅
  const { handleDownloadPdf, handlePrint, downloading } = useReportActions({
    jobId: jobIdNum,
    executionId: executionIdNum,
    iframeRef,
  });

  // 로딩 상태
  if (isLoading) {
    return (
      <div className="flex items-center justify-center h-64 gap-2 text-muted-foreground">
        <Loader2 className="h-5 w-5 animate-spin" />
        <span className="text-sm">실행 정보를 불러오는 중...</span>
      </div>
    );
  }

  // 실행 데이터 없음
  if (!execution) {
    return (
      <div className="flex flex-col items-center justify-center h-64 gap-4 text-muted-foreground">
        <p className="text-sm">실행 정보를 찾을 수 없습니다.</p>
        <Button variant="ghost" size="sm" onClick={() => navigate(-1)}>
          <ArrowLeft className="h-4 w-4 mr-1" />
          돌아가기
        </Button>
      </div>
    );
  }

  // COMPLETED 상태의 요약 텍스트 추출
  const summary = execution.status === 'COMPLETED' && execution.result
    ? (execution.result.summary as string | undefined)
    : undefined;

  // HTML이 없을 때 마크다운 섹션 폴백용
  const sections = execution.status === 'COMPLETED' && execution.result && !rawHtml
    ? getSections(execution.result)
    : [];

  return (
    <div className="px-6 py-4 space-y-6">
      {/* 고정 헤더: 뒤로가기 + 실행 번호 */}
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
        <h1 className="text-lg font-semibold text-foreground">
          실행 #{executionIdNum}
        </h1>
      </div>

      {/* 4열 메타 카드 그리드: 상태, 실행 시각, 소요 시간, 전달 채널 */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* 상태 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">상태</p>
          <Badge variant={getStatusBadgeVariant(execution.status)}>
            {getStatusLabel(execution.status)}
          </Badge>
        </div>

        {/* 실행 시각 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">실행 시각</p>
          <p className="text-sm font-medium">{formatDate(execution.startedAt)}</p>
          <p className="text-xs text-muted-foreground">{timeAgo(execution.startedAt)}</p>
        </div>

        {/* 소요 시간 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">소요 시간</p>
          <p className="text-sm font-medium">
            {execution.status === 'RUNNING' ? '진행중...' : formatDuration(execution.startedAt, execution.completedAt)}
          </p>
        </div>

        {/* 전달 채널 */}
        <div className="rounded-lg border bg-card p-4">
          <p className="text-xs text-muted-foreground mb-1">전달 채널</p>
          <div className="flex flex-wrap gap-1">
            {execution.deliveredChannels?.length > 0
              ? execution.deliveredChannels.map((ch) => (
                  <Badge key={ch} variant="outline" className="text-xs">
                    {ch}
                  </Badge>
                ))
              : <span className="text-sm text-muted-foreground">-</span>
            }
          </div>
        </div>
      </div>

      {/* 상태별 결과 영역 */}

      {/* RUNNING: 스피너 + 폴링 안내 */}
      {execution.status === 'RUNNING' && (
        <div className="flex flex-col items-center justify-center py-16 gap-4">
          <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
          <p className="text-sm text-muted-foreground">리포트를 생성하고 있습니다...</p>
          <p className="text-xs text-muted-foreground">5초마다 자동으로 상태를 확인합니다.</p>
        </div>
      )}

      {/* FAILED: 에러 분류 카드 */}
      {execution.status === 'FAILED' && (() => {
        const classified = classifyError(execution.errorMessage);
        return (
          <div className="rounded-lg border border-destructive/50 bg-destructive/5 p-6 space-y-3">
            <div className="flex items-center gap-2">
              <span className="text-lg">{classified.icon}</span>
              <h3 className="font-semibold text-destructive">{classified.label}</h3>
            </div>
            {execution.errorMessage && (
              <p className="text-sm text-muted-foreground font-mono whitespace-pre-wrap">
                {execution.errorMessage}
              </p>
            )}
            <p className="text-sm text-muted-foreground">{classified.guide}</p>
          </div>
        );
      })()}

      {/* COMPLETED + HTML: 요약 섹션 + HTML 리포트 iframe */}
      {execution.status === 'COMPLETED' && rawHtml && (
        <>
          {/* 요약 섹션 — 마크다운으로 렌더링 */}
          {summary && (
            <div className="rounded-lg border bg-muted/50 p-6">
              <h2 className="text-sm font-semibold mb-3">요약</h2>
              <div className={PROSE_CLASSES}>
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{summary}</ReactMarkdown>
              </div>
            </div>
          )}

          {/* HTML 리포트 영역 — 상단 바에 인쇄/PDF 버튼 + iframe */}
          <div className="rounded-lg border overflow-hidden">
            {/* 헤더 바: 리포트 제목 + 액션 버튼들 */}
            <div className="flex items-center justify-between px-4 py-2 border-b bg-muted/30">
              <h2 className="text-sm font-semibold">리포트</h2>
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

            {/* 흰색 배경 위에 iframe 렌더링 — 콘텐츠 높이에 맞춰 자동 조절 */}
            <div className="bg-white">
              <ReportIframe ref={iframeRef} html={rawHtml} autoHeight />
            </div>
          </div>
        </>
      )}

      {/* COMPLETED + HTML 없음: 마크다운 섹션 폴백 */}
      {execution.status === 'COMPLETED' && !rawHtml && sections.length > 0 && (
        <div className="space-y-4">
          {/* PDF 다운로드 버튼 */}
          <div className="flex justify-end">
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

          {/* 각 섹션을 마크다운 카드로 렌더링 */}
          {sections.map((section) => (
            <div key={section.key} className="rounded-lg border bg-card p-6">
              <h2 className="text-sm font-semibold mb-3">{section.label}</h2>
              <div className={PROSE_CLASSES}>
                <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>{section.content}</ReactMarkdown>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
