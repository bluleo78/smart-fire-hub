import { toast } from 'sonner';

import { type ObjectDisposition, objectsApi } from '../api/objects';
import { handleApiError, isForbiddenError } from './api-error';
import { parseUtcDate } from './formatters';

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
  const d = parseUtcDate(iso);
  return Number.isNaN(d.getTime()) ? '-' : d.toLocaleString();
}

/**
 * 오브젝트를 새 탭에서 열기/다운로드한다.
 * presigned GET URL은 만료가 짧아 클릭 시점에 발급한다. 팝업 차단을 피하려고 탭을 동기적으로
 * 먼저 연 뒤 URL을 받아 이동시킨다(noopener는 window.open이 null을 반환하므로 사용하지 않는다).
 * 키의 마지막 세그먼트가 파일명이라 저장명도 원본명이 된다.
 * disposition 기본은 inline(열기) — attachment(다운로드)는 서버가 내보내기 정책을 판정하므로 403 이면 서버 문구를 보인다.
 */
export async function openObjectInNewTab(
  datasetId: number,
  key: string,
  disposition: ObjectDisposition = 'inline',
): Promise<void> {
  const win = window.open('', '_blank');
  if (!win) {
    toast.error('팝업이 차단되어 파일을 열 수 없습니다');
    return;
  }
  try {
    const { data: res } = await objectsApi.presignedUrl(datasetId, key, disposition);
    win.location.href = res.url;
  } catch (error) {
    win.close();
    // 정책 거부(403)는 서버 사유 문구를 그대로 보인다 — 그 외 실패는 기존 일반 문구.
    if (isForbiddenError(error)) {
      handleApiError(error, '다운로드 URL 발급에 실패했습니다');
    } else {
      toast.error('다운로드 URL 발급에 실패했습니다');
    }
  }
}

/*
 * WD-761 폴더 탐색 보조 함수.
 * 오브젝트 탭은 현재 폴더를 URL ?path=a/b(앞뒤 / 없이)로 들고, 서버는 폴더 path 를 'a/b/'(끝 /)로,
 * 파일 name 을 데이터셋 prefix 를 뺀 전체 상대 경로로 준다. 이 표기 차이를 화면 한 곳에서 맞추려고 모았다.
 */

/**
 * 손으로 고친 URL ?path= 값을 탭이 쓰는 폴더 값(앞뒤 / 없음, 루트는 '')으로 정리한다 — ?path=a//b 같은 값도 서버가 실제로 연 폴더와 같은
 * 값이 되어 경로 표시·이름 줄이기·캐시 키가 어긋나지 않게. 서버 정규화와 같게 빈·'.' 세그먼트만 흡수하고 이름('\\'·공백)은 그대로 둔다
 * (WD-776 — 규칙은 서버 ObjectKeyGenerator.normalizeFolderPath·isOpenableFolder 참고). '..'·제어문자는 그대로 보내 서버가 400 으로 판정한다.
 */
export function toFolderParam(raw: string | null | undefined): string {
  if (!raw) return '';
  return raw
    .split('/')
    .filter((seg) => seg !== '' && seg !== '.')
    .join('/');
}

/**
 * 폴더 이름을 화면에 보일 글자로 바꾼다(WD-776). 공백뿐인 이름은 그대로 그리면 빈 버튼·빈 경로 조각이 되어 눌러야 할 곳이 보이지 않으므로
 * 「(공백)」으로 보인다. 이동 대상(path)은 바꾸지 않는다 — 표시만 다르다.
 */
export function folderDisplayName(name: string): string {
  return name.trim() === '' ? '(공백)' : name;
}

/** 경로 표시 한 조각 — 보이는 이름 + 누르면 이동할 폴더 값. */
export interface FolderCrumb {
  name: string;
  path: string;
}

/**
 * 폴더 값('제품/Tibero7')을 경로 표시 조각들로 나눈다 — 각 조각은 그 지점까지의 폴더로 이동한다.
 * 루트('')는 조각이 없다(화면이 「전체」를 따로 붙인다). 빈 세그먼트는 서버 정규화와 같게 건너뛰고, 공백뿐인 세그먼트는 한 폴더라 남긴다(WD-776).
 */
export function folderBreadcrumbs(path: string): FolderCrumb[] {
  const segments = path.split('/').filter((s) => s.length > 0);
  return segments.map((name, i) => ({ name, path: segments.slice(0, i + 1).join('/') }));
}

/**
 * 파일의 전체 상대 경로(name)에서 현재 폴더 접두를 떼어 그 폴더 안 이름만 돌려준다.
 * 폴더 모드 서버 응답이면 마지막 세그먼트와 같다. 접두가 맞지 않으면(폴더를 모르는 예전 응답·다른 폴더) 이름을 그대로 둬
 * 정보가 사라지지 않게 한다. 접두는 끝 '/' 까지 맞춰 '제품2/…' 를 '제품' 안으로 잘못 자르지 않는다.
 */
export function nameInFolder(name: string, path: string): string {
  if (!path) return name;
  const prefix = `${path}/`;
  return name.startsWith(prefix) && name.length > prefix.length ? name.slice(prefix.length) : name;
}

/** 파일의 전체 상대 경로에서 그 파일이 있는 폴더 값(앞뒤 / 없음, 루트는 '')을 구한다 — 검색 결과에서 폴더로 이동할 때 쓴다. */
export function parentFolderOf(name: string): string {
  const idx = name.lastIndexOf('/');
  return idx < 0 ? '' : toFolderParam(name.slice(0, idx));
}
