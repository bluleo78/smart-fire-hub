import { AxiosError, type AxiosResponse } from 'axios';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { objectsApi } from '../api/objects';
import {
  folderBreadcrumbs,
  formatObjectDate,
  formatObjectSize,
  nameInFolder,
  openObjectInNewTab,
  parentFolderOf,
  toFolderParam,
} from './objectFile';

// objectsApi.presignedUrl 과 sonner toast 를 모킹하여 다운로드 흐름을 격리 검증한다.
vi.mock('../api/objects', () => ({
  objectsApi: { presignedUrl: vi.fn() },
}));
const toastError = vi.fn();
vi.mock('sonner', () => ({ toast: { error: (msg: string) => toastError(msg) } }));

describe('formatObjectSize', () => {
  it('1KB 미만은 바이트로 표기한다', () => {
    expect(formatObjectSize(0)).toBe('0 B');
    expect(formatObjectSize(512)).toBe('512 B');
    expect(formatObjectSize(1023)).toBe('1023 B');
  });

  it('1KB~1MB 미만은 KB로 표기한다', () => {
    expect(formatObjectSize(1024)).toBe('1.0 KB');
    expect(formatObjectSize(2048)).toBe('2.0 KB');
    expect(formatObjectSize(1536)).toBe('1.5 KB');
  });

  it('1MB 이상은 MB로 표기한다', () => {
    expect(formatObjectSize(1024 * 1024)).toBe('1.0 MB');
    expect(formatObjectSize(1024 * 1024 * 2.5)).toBe('2.5 MB');
  });
});

describe('formatObjectDate', () => {
  it('null/빈 값은 하이픈으로 표기한다', () => {
    expect(formatObjectDate(null)).toBe('-');
    expect(formatObjectDate('')).toBe('-');
  });

  it('잘못된 날짜 문자열은 하이픈으로 표기한다', () => {
    expect(formatObjectDate('not-a-date')).toBe('-');
  });

  it('유효한 ISO 문자열은 로컬 문자열로 변환한다(하이픈이 아니다)', () => {
    const out = formatObjectDate('2026-07-20T00:00:00Z');
    expect(out).not.toBe('-');
    expect(out.length).toBeGreaterThan(0);
  });
});

describe('openObjectInNewTab', () => {
  const openSpy = vi.fn();
  let fakeWin: { location: { href: string }; close: () => void };

  beforeEach(() => {
    vi.clearAllMocks();
    fakeWin = { location: { href: '' }, close: vi.fn() };
    openSpy.mockReturnValue(fakeWin);
    vi.stubGlobal('open', openSpy);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('presigned URL을 발급받아 미리 연 탭을 그 URL로 이동시킨다', async () => {
    (objectsApi.presignedUrl as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { url: 'https://minio.example/download/report.md', expiresInSeconds: 300 },
    });

    await openObjectInNewTab(7, 'equip/report.md');

    // 팝업 차단 회피: 탭을 먼저 동기적으로 연다.
    expect(openSpy).toHaveBeenCalledWith('', '_blank');
    // 발급은 datasetId + 전체 key 로 이뤄진다. disposition 기본은 inline(열기 — 내보내기 정책 비대상).
    expect(objectsApi.presignedUrl).toHaveBeenCalledWith(7, 'equip/report.md', 'inline');
    expect(fakeWin.location.href).toBe('https://minio.example/download/report.md');
    expect(toastError).not.toHaveBeenCalled();
  });

  it('팝업이 차단되면(open이 null) 발급을 시도하지 않고 에러 토스트를 띄운다', async () => {
    openSpy.mockReturnValue(null);

    await openObjectInNewTab(7, 'equip/report.md');

    expect(objectsApi.presignedUrl).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith('팝업이 차단되어 파일을 열 수 없습니다');
  });

  it('attachment 를 지정하면 그대로 발급 요청에 싣는다(다운로드 — 서버가 내보내기 정책 판정)', async () => {
    (objectsApi.presignedUrl as ReturnType<typeof vi.fn>).mockResolvedValue({
      data: { url: 'https://minio.example/download/report.md', expiresInSeconds: 300 },
    });

    await openObjectInNewTab(7, 'equip/report.md', 'attachment');

    expect(objectsApi.presignedUrl).toHaveBeenCalledWith(7, 'equip/report.md', 'attachment');
  });

  it('정책 거부(403)면 탭을 닫고 서버 사유 문구를 보인다', async () => {
    const message = "'기밀' 등급 데이터는 내보낼 수 없습니다.";
    const err = new AxiosError('Forbidden', 'ERR_BAD_REQUEST', undefined, undefined, {
      status: 403,
      data: { status: 403, code: 'POLICY_BLOCKED', message, errors: { action: 'EXPORT' } },
    } as AxiosResponse);
    (objectsApi.presignedUrl as ReturnType<typeof vi.fn>).mockRejectedValue(err);

    await openObjectInNewTab(7, 'equip/report.md', 'attachment');

    expect(fakeWin.close).toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith(message);
  });

  it('URL 발급 실패 시 열어둔 탭을 닫고 에러 토스트를 띄운다', async () => {
    (objectsApi.presignedUrl as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));

    await openObjectInNewTab(7, 'equip/report.md');

    expect(fakeWin.close).toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith('다운로드 URL 발급에 실패했습니다');
  });
});

// WD-761 폴더 탐색 — URL ?path= 값과 서버 응답 이름을 화면 표시·이동 대상으로 바꾸는 순수 함수들.
describe('toFolderParam', () => {
  it('없거나 빈 값·슬래시만이면 루트(빈 문자열)다', () => {
    expect(toFolderParam(null)).toBe('');
    expect(toFolderParam(undefined)).toBe('');
    expect(toFolderParam('')).toBe('');
    expect(toFolderParam('/')).toBe('');
    expect(toFolderParam('///')).toBe('');
  });

  it('앞뒤 슬래시를 떼어 a/b 꼴로 맞춘다(서버 폴더 path 의 끝 / 포함)', () => {
    expect(toFolderParam('제품/Tibero7/')).toBe('제품/Tibero7');
    expect(toFolderParam('/a/b/')).toBe('a/b');
    expect(toFolderParam('a')).toBe('a');
  });

  it('가운데 값은 건드리지 않는다 — .. 같은 값의 판정은 서버(400) 몫이다', () => {
    expect(toFolderParam('../x')).toBe('../x');
  });

  it('서버 정규화와 같게 빈·. 세그먼트를 흡수하고 \\ 를 / 로 바꾸되 이름은 다듬지 않는다', () => {
    expect(toFolderParam('a//b')).toBe('a/b');
    expect(toFolderParam('a/./b')).toBe('a/b');
    expect(toFolderParam('a\\b')).toBe('a/b');
    expect(toFolderParam('a/ /b')).toBe('a/b');
    expect(toFolderParam('report /2026')).toBe('report /2026');
  });
});

describe('folderBreadcrumbs', () => {
  it('루트면 조각이 없다', () => {
    expect(folderBreadcrumbs('')).toEqual([]);
  });

  it('세그먼트마다 이름과 그 지점까지의 경로를 준다', () => {
    expect(folderBreadcrumbs('제품/Tibero7')).toEqual([
      { name: '제품', path: '제품' },
      { name: 'Tibero7', path: '제품/Tibero7' },
    ]);
  });

  it('빈 세그먼트(연속 슬래시)는 건너뛴다', () => {
    expect(folderBreadcrumbs('a//b')).toEqual([
      { name: 'a', path: 'a' },
      { name: 'b', path: 'a/b' },
    ]);
  });
});

describe('nameInFolder', () => {
  it('현재 폴더 접두를 떼어 그 폴더 안의 이름만 보인다', () => {
    expect(nameInFolder('제품/Tibero7/설치.pdf', '제품/Tibero7')).toBe('설치.pdf');
  });

  it('루트에서는 이름 그대로다(폴더를 모르는 예전 응답의 상대 경로도 유지)', () => {
    expect(nameInFolder('보고서.md', '')).toBe('보고서.md');
    expect(nameInFolder('2026/photo.jpg', '')).toBe('2026/photo.jpg');
  });

  it('접두가 맞지 않으면(다른 폴더) 이름을 그대로 둔다', () => {
    expect(nameInFolder('기타/a.txt', '제품')).toBe('기타/a.txt');
  });

  it('폴더 이름의 앞부분만 같은 경우(제품2/…)는 떼지 않는다', () => {
    expect(nameInFolder('제품2/a.txt', '제품')).toBe('제품2/a.txt');
  });
});

describe('parentFolderOf', () => {
  it('상대 경로에서 파일이 있는 폴더(앞뒤 / 없이)를 준다', () => {
    expect(parentFolderOf('제품/Tibero7/설치.pdf')).toBe('제품/Tibero7');
  });

  it('루트 파일이면 빈 문자열이다', () => {
    expect(parentFolderOf('보고서.md')).toBe('');
  });
});
