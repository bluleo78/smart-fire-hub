import { useCallback, useRef } from 'react';
import { toast } from 'sonner';

import { exportsApi } from '../api/exports';
import { jobsApi } from '../api/jobs';
import { downloadBlob } from '../lib/download';

export function useExportJobTracking() {
  // 활성 폴링 인터벌 ID를 ref로 유지 — 언마운트/cleanup 시 clearInterval에 사용
  const pollIntervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  /**
   * 내보내기 작업 폴링을 시작한다.
   * 반환된 cleanup 함수를 useEffect 클린업에 등록해야
   * 컴포넌트 언마운트 시 interval이 자동 정리된다.
   */
  const startTracking = useCallback((jobId: string, datasetName: string) => {
    // 이전 폴링이 남아 있으면 먼저 정리
    if (pollIntervalRef.current !== null) {
      clearInterval(pollIntervalRef.current);
    }

    const toastId = toast.loading(`내보내기 준비 중... (${datasetName})`, {
      duration: Infinity,
    });

    pollIntervalRef.current = setInterval(async () => {
      try {
        const { data: status } = await jobsApi.getJobStatus(jobId);

        if (status.stage === 'COMPLETED') {
          clearInterval(pollIntervalRef.current!);
          pollIntervalRef.current = null;
          toast.loading('내보내기 완료! 다운로드 중...', { id: toastId });

          try {
            const response = await exportsApi.downloadExportFile(jobId);
            const filename =
              (status.metadata?.filename as string) || `${datasetName}_export`;
            downloadBlob(filename, response.data as Blob);
            toast.success('파일이 다운로드되었습니다.', { id: toastId });
          } catch {
            toast.error('파일 다운로드에 실패했습니다.', { id: toastId });
          }
        } else if (status.stage === 'FAILED') {
          clearInterval(pollIntervalRef.current!);
          pollIntervalRef.current = null;
          toast.error(
            `내보내기 실패: ${status.errorMessage || '알 수 없는 오류'}`,
            { id: toastId }
          );
        } else {
          toast.loading(
            `내보내기 중... ${status.progress}% (${status.message || '처리 중'})`,
            { id: toastId }
          );
        }
      } catch {
        clearInterval(pollIntervalRef.current!);
        pollIntervalRef.current = null;
        toast.error('내보내기 상태 확인 실패.', { id: toastId });
      }
    }, 2000);

    // 호출부가 useEffect cleanup으로 등록할 수 있도록 cleanup 함수 반환
    return () => {
      if (pollIntervalRef.current !== null) {
        clearInterval(pollIntervalRef.current);
        pollIntervalRef.current = null;
      }
    };
  }, []);

  return { startTracking };
}
