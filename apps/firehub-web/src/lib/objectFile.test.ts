import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { objectsApi } from '../api/objects';
import { formatObjectDate, formatObjectSize, openObjectInNewTab } from './objectFile';

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
    // 발급은 datasetId + 전체 key 로 이뤄진다.
    expect(objectsApi.presignedUrl).toHaveBeenCalledWith(7, 'equip/report.md');
    expect(fakeWin.location.href).toBe('https://minio.example/download/report.md');
    expect(toastError).not.toHaveBeenCalled();
  });

  it('팝업이 차단되면(open이 null) 발급을 시도하지 않고 에러 토스트를 띄운다', async () => {
    openSpy.mockReturnValue(null);

    await openObjectInNewTab(7, 'equip/report.md');

    expect(objectsApi.presignedUrl).not.toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith('팝업이 차단되어 파일을 열 수 없습니다');
  });

  it('URL 발급 실패 시 열어둔 탭을 닫고 에러 토스트를 띄운다', async () => {
    (objectsApi.presignedUrl as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'));

    await openObjectInNewTab(7, 'equip/report.md');

    expect(fakeWin.close).toHaveBeenCalled();
    expect(toastError).toHaveBeenCalledWith('다운로드 URL 발급에 실패했습니다');
  });
});
