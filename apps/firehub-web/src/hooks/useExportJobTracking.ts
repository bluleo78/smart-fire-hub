import { useCallback } from 'react';
import { toast } from 'sonner';

import { exportsApi } from '../api/exports';
import { jobsApi } from '../api/jobs';
import { downloadBlob } from '../lib/download';

export function useExportJobTracking() {
  const startTracking = useCallback((jobId: string, datasetName: string) => {
    const toastId = toast.loading(`내보내기 준비 중... (${datasetName})`, {
      duration: Infinity,
    });

    const pollInterval = setInterval(async () => {
      try {
        const { data: status } = await jobsApi.getJobStatus(jobId);

        if (status.stage === 'COMPLETED') {
          clearInterval(pollInterval);
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
          clearInterval(pollInterval);
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
        clearInterval(pollInterval);
        toast.error('내보내기 상태 확인 실패.', { id: toastId });
      }
    }, 2000);
  }, []);

  return { startTracking };
}
