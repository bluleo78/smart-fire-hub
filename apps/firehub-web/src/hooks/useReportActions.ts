/**
 * useReportActions — 리포트 PDF 다운로드 및 인쇄 액션을 제공하는 공통 훅.
 *
 * ExecutionDetailPage, ReportModal, ReportViewerPage 세 곳에서 재사용한다.
 * 동일한 PDF 다운로드/인쇄 로직의 중복을 제거하기 위해 추출되었다.
 */
import { useCallback, useState } from 'react';
import { toast } from 'sonner';

import { proactiveApi } from '@/api/proactive';
import { downloadBlob } from '@/lib/download';

interface UseReportActionsOptions {
  jobId: number;
  executionId: number;
  /** iframe ref — 인쇄 시 iframe 내부 문서를 인쇄하기 위해 필요 */
  iframeRef: React.RefObject<HTMLIFrameElement | null>;
}

interface UseReportActionsReturn {
  /** PDF 다운로드 핸들러 */
  handleDownloadPdf: () => Promise<void>;
  /** 리포트 인쇄 핸들러 — iframe이 있으면 iframe 내부를, 없으면 window를 인쇄 */
  handlePrint: () => void;
  /** PDF 다운로드 진행 중 여부 */
  downloading: boolean;
}

export function useReportActions({
  jobId,
  executionId,
  iframeRef,
}: UseReportActionsOptions): UseReportActionsReturn {
  const [downloading, setDownloading] = useState(false);

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

  const handlePrint = useCallback(() => {
    const iframe = iframeRef.current;
    if (iframe?.contentWindow) {
      iframe.contentWindow.print();
    } else {
      window.print();
    }
  }, [iframeRef]);

  return { handleDownloadPdf, handlePrint, downloading };
}
