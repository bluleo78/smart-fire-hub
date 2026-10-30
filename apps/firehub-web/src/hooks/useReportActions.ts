/**
 * useReportActions — 리포트 PDF 다운로드 및 인쇄 액션을 제공하는 공통 훅.
 *
 * ExecutionDetailPage, ReportModal, ReportViewerPage 세 곳에서 재사용한다.
 * 동일한 PDF 다운로드/인쇄 로직의 중복을 제거하기 위해 추출되었다.
 */
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { proactiveApi } from '@/api/proactive';
import { handleApiErrorAsync } from '@/lib/api-error';
import { downloadBlob } from '@/lib/download';

/**
 * 리포트 iframe sandbox — 스크립트는 막고(allow-scripts 없음) 부모의 print() 만 허용한다(WD-343).
 * ReportIframe 과 폴백 인쇄 프레임이 함께 쓴다 — 어긋나면 한쪽 인쇄만 조용히 무시된다.
 */
export const REPORT_IFRAME_SANDBOX = 'allow-same-origin allow-modals';

/** 폴백 본문 인쇄용 최소 스타일 — 앱 CSS(tailwind)는 임시 문서에 없으므로 읽기 좋은 기본값만 둔다 */
const PRINT_STYLE =
  'body{font-family:system-ui,-apple-system,"Noto Sans KR",sans-serif;color:#111;margin:24px;line-height:1.6}' +
  'h1,h2,h3{margin:1.2em 0 .5em}table{border-collapse:collapse}th,td{border:1px solid #ccc;padding:4px 8px}' +
  'pre,code{white-space:pre-wrap;font-family:ui-monospace,monospace}';

/** 폴백 인쇄에 쓰는 임시 iframe — 다음 인쇄 때 지워 한 개만 남긴다 */
let printFrame: HTMLIFrameElement | null = null;

/**
 * HTML 조각만 담은 임시 iframe 을 띄워 인쇄한다.
 * 리포트 iframe 과 같은 sandbox(스크립트 차단 + print 허용)로 만들어 본문 안 스크립트는 실행되지 않는다.
 * afterprint 에서 지우지 않는 이유: Safari 등 print() 가 막지 않는 브라우저는 인쇄 미리보기를 만들기 전에
 * afterprint 가 와 빈 페이지가 인쇄될 수 있다. 대신 다음 인쇄 때 이전 프레임을 지워 쌓이지 않게 한다.
 */
function printHtml(bodyHtml: string): void {
  printFrame?.remove();
  const frame = document.createElement('iframe');
  printFrame = frame;
  frame.setAttribute('sandbox', REPORT_IFRAME_SANDBOX);
  frame.setAttribute('aria-hidden', 'true');
  frame.tabIndex = -1;
  // visibility:hidden·0 크기 문서는 일부 엔진이 빈 페이지로 인쇄하므로, 화면 밖에 A4 폭으로 둔다
  frame.style.cssText = 'position:fixed;left:-10000px;top:0;width:794px;height:1123px;border:0';
  frame.srcdoc = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><title>리포트</title><style>${PRINT_STYLE}</style></head><body>${bodyHtml}</body></html>`;
  frame.onload = () => frame.contentWindow?.print();
  document.body.appendChild(frame);
}

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
  } catch (error) {
    // 서버 문구를 그대로 보인다(WD-198) — 리포트 등급 차단·등급 내보내기 정책 거부는 이유가 있는 403 이라 「실패」로만 뭉개면 다시 시도하게 된다.
    await handleApiErrorAsync(error, 'PDF 다운로드에 실패했습니다.');
  }
}

interface UseReportActionsOptions {
  jobId: number;
  executionId: number;
  /** iframe ref — 인쇄 시 iframe 내부 문서를 인쇄하기 위해 필요 */
  iframeRef: React.RefObject<HTMLIFrameElement | null>;
  /** HTML 리포트가 없을 때(마크다운 폴백) 인쇄할 본문 영역 — 없으면 인쇄할 것이 없다고 안내한다 */
  contentRef?: React.RefObject<HTMLElement | null>;
}

interface UseReportActionsReturn {
  /** PDF 다운로드 핸들러 */
  handleDownloadPdf: () => Promise<void>;
  /** 리포트 인쇄 핸들러 — iframe 이 있으면 iframe 문서를, 없으면 폴백 본문만 인쇄 */
  handlePrint: () => void;
  /** PDF 다운로드 진행 중 여부 */
  downloading: boolean;
}

export function useReportActions({
  jobId,
  executionId,
  iframeRef,
  contentRef,
}: UseReportActionsOptions): UseReportActionsReturn {
  const [downloading, setDownloading] = useState(false);

  // 폴백 인쇄 프레임은 다음 인쇄 때 지우므로, 화면을 떠날 때도 지워 리포트 사본을 남기지 않는다
  useEffect(
    () => () => {
      printFrame?.remove();
      printFrame = null;
    },
    [],
  );

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
      return;
    }
    // 마크다운 폴백 — window.print() 는 사이드바·모달 오버레이까지 앱 화면 전체를 인쇄하므로(WD-343),
    // 본문만 담은 임시 iframe 을 만들어 그 문서를 인쇄한다.
    const content = contentRef?.current;
    if (content) {
      printHtml(content.innerHTML);
      return;
    }
    toast.info('인쇄할 리포트가 없습니다.');
  }, [iframeRef, contentRef]);

  return { handleDownloadPdf, handlePrint, downloading };
}
