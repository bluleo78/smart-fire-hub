import { toast } from 'sonner';

import { exportsApi } from '../api/exports';
import { jobsApi } from '../api/jobs';
import { downloadBlob } from './download';

/** 작업 상태를 다시 묻는 간격(ms) */
const POLL_INTERVAL_MS = 2000;

/**
 * 비동기 내보내기 작업을 끝날 때까지 추적하고, 완료되면 파일을 내려받는다.
 *
 * 왜 컴포넌트 훅이 아닌 모듈 함수인가 (WD-157):
 * 내보내기 다이얼로그는 요청 직후 닫히고, 부모가 닫힌 다이얼로그를 언마운트한다.
 * 예전에는 폴링 interval 을 다이얼로그 언마운트 정리에 묶어 두어서, 첫 상태 확인(2초) 전에
 * interval 이 지워지고 「내보내기 준비 중...」 토스트만 영원히 남았다.
 * 진행 토스트는 화면 전역에 떠 있는 것이므로, 추적도 컴포넌트 수명과 무관하게
 * 작업이 끝나거나(완료·실패) 상태 확인이 실패할 때까지 이어 간다.
 *
 * 여러 내보내기를 연달아 시작해도 작업마다 따로 추적한다(토스트도 작업마다 하나).
 * 앞선 요청이 끝나기 전에 다음 확인이 겹치지 않도록 setInterval 대신 setTimeout 을 이어 건다.
 */
export function trackExportJob(jobId: string, datasetName: string): void {
  const toastId = toast.loading(`내보내기 준비 중... (${datasetName})`, {
    duration: Infinity,
  });

  // 한 번 상태를 확인하고, 아직 진행 중이면 다음 확인을 예약한다
  const poll = async () => {
    try {
      const { data: status } = await jobsApi.getJobStatus(jobId);

      if (status.stage === 'COMPLETED') {
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
        return;
      }

      if (status.stage === 'FAILED') {
        toast.error(
          `내보내기 실패: ${status.errorMessage || '알 수 없는 오류'}`,
          { id: toastId }
        );
        return;
      }

      toast.loading(
        `내보내기 중... ${status.progress}% (${status.message || '처리 중'})`,
        { id: toastId }
      );
      setTimeout(poll, POLL_INTERVAL_MS);
    } catch {
      toast.error('내보내기 상태 확인 실패.', { id: toastId });
    }
  };

  setTimeout(poll, POLL_INTERVAL_MS);
}
