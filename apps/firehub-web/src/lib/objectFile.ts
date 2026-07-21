import { toast } from 'sonner';

import { objectsApi } from '../api/objects';

/**
 * FILE(오브젝트) 데이터셋 공통 유틸.
 * DatasetObjectsTab(데이터셋 상세 탭)과 DatasetFilesWidget(AI 챗 인라인 카드)이 공유한다.
 * 두 화면의 파일 크기·수정일 표기와 다운로드 동작을 단일 소스로 유지하기 위해 추출했다.
 */

/** 바이트를 사람이 읽기 쉬운 단위로 표기(B/KB/MB). */
export function formatObjectSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

/** ISO 문자열을 로컬 날짜시간으로(없거나 잘못되면 '-'). */
export function formatObjectDate(iso: string | null): string {
  if (!iso) return '-';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '-' : d.toLocaleString();
}

/**
 * 오브젝트를 새 탭에서 열기/다운로드한다.
 * presigned GET URL은 만료가 짧아 클릭 시점에 발급한다. 팝업 차단을 피하려고 탭을 동기적으로
 * 먼저 연 뒤 URL을 받아 이동시킨다(noopener는 window.open이 null을 반환하므로 사용하지 않는다).
 * 키의 마지막 세그먼트가 파일명이라 저장명도 원본명이 된다.
 */
export async function openObjectInNewTab(datasetId: number, key: string): Promise<void> {
  const win = window.open('', '_blank');
  if (!win) {
    toast.error('팝업이 차단되어 파일을 열 수 없습니다');
    return;
  }
  try {
    const { data: res } = await objectsApi.presignedUrl(datasetId, key);
    win.location.href = res.url;
  } catch {
    win.close();
    toast.error('다운로드 URL 발급에 실패했습니다');
  }
}
