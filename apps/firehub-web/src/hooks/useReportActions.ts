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

/**
 * 리포트 PDF를 내려받는다. 실패 시 토스트로 알린다.
 *
 * 훅과 분리해 둔 이유: 리포트 목록은 행마다 다운로드 버튼을 두는데, 훅은 map() 안에서
 * 행별로 호출할 수 없다. 훅(단건 화면)과 목록이 같은 동작을 공유하도록 평범한 함수로 뺐다.
 */
export async function downloadReportPdf(jobId: number, executionId: number): Promise<void> {
  try {
    const response = await proactiveApi.downloadExecutionPdf(jobId, executionId);
    downloadBlob(`report-${executionId}.pdf`, response.data as Blob);
  } catch {
    toast.error('PDF 다운로드에 실패했습니다.');
  }
}

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
      await downloadReportPdf(jobId, executionId);
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
