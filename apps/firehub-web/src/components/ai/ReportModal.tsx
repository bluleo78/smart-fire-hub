/**
 * ReportModal — AI 챗/알림에서 리포트를 빠르게 확인하는 모달.
 *
 * shadcn Dialog 기반. 현재 화면 위에 오버레이로 표시하여
 * 페이지 이동 없이 리포트를 확인할 수 있다.
 * 하단에 "실행 상세 보기" 링크로 상세 페이지 진입 가능.
 */

import { ExternalLink, FileDown, Loader2, Printer, XIcon } from 'lucide-react';
import { useCallback, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import { Link, useNavigate } from 'react-router-dom';
import remarkGfm from 'remark-gfm';
import { toast } from 'sonner';

import { proactiveApi } from '@/api/proactive';
import ReportIframe from '@/components/ai/ReportIframe';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { downloadBlob } from '@/lib/download';
import { getStatusBadgeVariant, getStatusLabel, timeAgo } from '@/lib/formatters';
import { getSections } from '@/lib/proactive-utils';
import { useQuery } from '@tanstack/react-query';

/** ReactMarkdown remark 플러그인 — GFM(테이블, 취소선 등) 지원 */
const REMARK_PLUGINS = [remarkGfm];

interface ReportModalProps {
  /** 모달 열림 상태 */
  open: boolean;
  /** 모달 닫기 핸들러 */
  onClose: () => void;
  /** 프로액티브 작업 ID */
  jobId: number;
  /** 실행 ID */
  executionId: number;
}

/**
 * 리포트 모달 컴포넌트
 *
 * HTML 리포트가 있으면 iframe으로, 없으면 마크다운 폴백으로 렌더링한다.
 * 인쇄, PDF 다운로드, 새 탭 열기, 상세 페이지 이동 액션을 제공한다.
 */
export default function ReportModal({ open, onClose, jobId, executionId }: ReportModalProps) {
  const navigate = useNavigate();
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [downloading, setDownloading] = useState(false);

  // HTML 리포트 조회 — 모달이 열려 있고 유효한 ID일 때만 fetch
  const {
    data: htmlResponse,
    isLoading: htmlLoading,
  } = useQuery({
    queryKey: ['execution-html', jobId, executionId],
    queryFn: () => proactiveApi.getExecutionHtml(jobId, executionId),
    enabled: open && !!jobId && !!executionId,
  });

  // 실행 메타데이터 조회 — 상태 뱃지, 시간 표시에 사용
  const {
    data: execution,
    isLoading: metaLoading,
  } = useQuery({
    queryKey: ['proactive', 'executions', jobId, executionId],
    queryFn: () => proactiveApi.getExecution(jobId, executionId).then((r) => r.data),
    enabled: open && !!jobId && !!executionId,
  });

  /** HTML 문자열 추출 (axios response.data) */
  const rawHtml = htmlResponse?.data ?? null;

  /** 전체 로딩 상태 — HTML과 메타 둘 다 로딩 중이면 true */
  const isLoading = htmlLoading || metaLoading;

  // PDF 다운로드 핸들러 — ReportViewerPage와 동일한 패턴
  const handleDownloadPdf = useCallback(async () => {
    setDownloading(true);
    try {
      const response = await proactiveApi.downloadExecutionPdf(jobId, executionId);
      downloadBlob(`report-${executionId}.pdf`, response.data as Blob);
    } catch {
      toast.error('PDF 다운로드에 실패했습니다.');
    } finally {
      setDownloading(false);
    }
  }, [jobId, executionId]);

  // 인쇄 핸들러 — iframe 내부 문서를 인쇄한다
  const handlePrint = useCallback(() => {
    const iframe = iframeRef.current;
    if (iframe?.contentWindow) {
      iframe.contentWindow.print();
    } else {
      window.print();
    }
  }, []);

  // 실행 상세 페이지로 이동 — 모달을 닫고 navigate
  const handleGoToDetail = useCallback(() => {
    onClose();
    navigate(`/ai-insights/jobs/${jobId}/executions/${executionId}`);
  }, [onClose, navigate, jobId, executionId]);

  /**
   * 마크다운 폴백 렌더링
   * HTML 리포트가 없지만 실행 결과(result)가 있을 때 섹션별 마크다운으로 표시
   */
  const renderMarkdownFallback = () => {
    if (!execution?.result) return null;
    const sections = getSections(execution.result);
    if (sections.length === 0) return null;

    return (
      <div className="p-6 space-y-6 overflow-auto h-full">
        {sections.map((section) => (
          <div key={section.key}>
            <h3 className="text-sm font-semibold text-foreground mb-2">{section.label}</h3>
            <div className="prose prose-sm dark:prose-invert max-w-none">
              <ReactMarkdown remarkPlugins={REMARK_PLUGINS}>
                {section.content}
              </ReactMarkdown>
            </div>
          </div>
        ))}
      </div>
    );
  };

  return (
    <Dialog open={open} onOpenChange={(v) => !v && onClose()}>
      <DialogContent className="sm:max-w-5xl h-[85vh] flex flex-col p-0 gap-0" showCloseButton={false}>
        {/* 헤더 — 제목, 상태 뱃지, 시간, 액션 버튼 */}
        <DialogHeader className="flex flex-row items-center justify-between px-6 py-4 border-b shrink-0 space-y-0">
          <div className="flex items-center gap-3">
            <DialogTitle className="text-base font-semibold">
              리포트 #{executionId}
            </DialogTitle>
            {/* 실행 상태 뱃지 */}
            {execution && (
              <>
                <Badge variant={getStatusBadgeVariant(execution.status)}>
                  {getStatusLabel(execution.status)}
                </Badge>
                <span className="text-xs text-muted-foreground">
                  {timeAgo(execution.startedAt)}
                </span>
              </>
            )}
          </div>

          {/* 액션 버튼 그룹 — 인쇄, PDF, 새 탭, 닫기 */}
          <div className="flex items-center gap-1">
            <Button
              variant="ghost"
              size="icon"
              onClick={handlePrint}
              title="인쇄"
              className="h-8 w-8"
            >
              <Printer className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              onClick={handleDownloadPdf}
              disabled={downloading}
              title="PDF 다운로드"
              className="h-8 w-8"
            >
              {downloading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <FileDown className="h-4 w-4" />
              )}
            </Button>
            {/* 새 탭에서 리포트 뷰어 열기 */}
            <Button variant="ghost" size="icon" asChild className="h-8 w-8" title="새 탭에서 열기">
              <Link
                to={`/ai-insights/jobs/${jobId}/executions/${executionId}/report`}
                target="_blank"
              >
                <ExternalLink className="h-4 w-4" />
              </Link>
            </Button>
            {/* 닫기 — p-0 레이아웃에서 기본 close 버튼이 겹치므로 헤더에 배치 */}
            <div className="w-px h-5 bg-border mx-1" />
            <Button
              variant="ghost"
              size="icon"
              onClick={onClose}
              title="닫기"
              className="h-8 w-8"
            >
              <XIcon className="h-4 w-4" />
            </Button>
          </div>
        </DialogHeader>

        {/* 본문 — 로딩 / HTML 리포트 / 마크다운 폴백 / 빈 상태 */}
        <div className="flex-1 overflow-hidden">
          {isLoading && (
            <div className="flex items-center justify-center h-full gap-2 text-muted-foreground">
              <Loader2 className="h-5 w-5 animate-spin" />
              <span className="text-sm">리포트를 불러오는 중...</span>
            </div>
          )}

          {/* HTML 리포트가 있으면 iframe으로 렌더링 */}
          {!isLoading && rawHtml && (
            <div className="h-full bg-white">
              <ReportIframe ref={iframeRef} html={rawHtml} />
            </div>
          )}

          {/* HTML이 없고 결과가 있으면 마크다운 폴백 */}
          {!isLoading && !rawHtml && execution?.result && renderMarkdownFallback()}

          {/* 리포트도 결과도 없는 빈 상태 */}
          {!isLoading && !rawHtml && !execution?.result && (
            <div className="flex items-center justify-center h-full text-muted-foreground">
              <p className="text-sm">리포트가 없습니다.</p>
            </div>
          )}
        </div>

        {/* 푸터 — 실행 상세 페이지로 이동 */}
        <DialogFooter className="border-t px-6 py-3 shrink-0 sm:justify-start">
          <Button
            variant="ghost"
            size="sm"
            onClick={handleGoToDetail}
            className="gap-1.5 text-muted-foreground hover:text-foreground"
          >
            실행 상세 보기 →
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
