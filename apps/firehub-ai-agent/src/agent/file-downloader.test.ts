import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import path from 'path';

// Mock fs/promises
vi.mock('fs/promises', () => ({
  default: {
    mkdir: vi.fn().mockResolvedValue(undefined),
    writeFile: vi.fn().mockResolvedValue(undefined),
    rm: vi.fn().mockResolvedValue(undefined),
    readdir: vi.fn().mockResolvedValue([]),
    stat: vi.fn().mockResolvedValue({ mtimeMs: Date.now() }),
    unlink: vi.fn().mockResolvedValue(undefined),
    readFile: vi.fn().mockRejectedValue(Object.assign(new Error('ENOENT'), { code: 'ENOENT' })),
  },
}));

import fs from 'fs/promises';
import { downloadChatFiles, cleanupChatFiles, toAttachmentMeta, purgeExpiredSessionAttachments } from './file-downloader.js';
import type { FireHubApiClient } from '../mcp/api-client.js';

const TEST_DIR = '/tmp/test-chat-files';

function makeApiClient(overrides: Partial<FireHubApiClient> = {}): FireHubApiClient {
  return {
    getFileInfo: vi.fn().mockResolvedValue({
      id: 1,
      originalName: 'test.txt',
      mimeType: 'text/plain',
      fileCategory: 'TEXT',
      fileSize: 1024,
    }),
    downloadFile: vi.fn().mockResolvedValue(Buffer.from('file content')),
    ...overrides,
  } as unknown as FireHubApiClient;
}

describe('downloadChatFiles', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // FD-01: successfully downloads files and returns DownloadedFile list
  it('FD-01: downloads files and returns correct DownloadedFile entries', async () => {
    const apiClient = makeApiClient();
    const result = await downloadChatFiles(apiClient, [1], TEST_DIR);

    expect(result.files).toHaveLength(1);
    expect(result.failed).toBe(0);
    expect(result.files[0]).toMatchObject({
      originalName: 'test.txt',
      mimeType: 'text/plain',
      fileCategory: 'TEXT',
      fileSize: 1024,
      localPath: path.join(TEST_DIR, 'test.txt'),
    });
  });

  // FD-02: creates the download directory
  it('FD-02: creates download directory with recursive option', async () => {
    const apiClient = makeApiClient();
    await downloadChatFiles(apiClient, [1], TEST_DIR);

    expect(fs.mkdir).toHaveBeenCalledWith(TEST_DIR, { recursive: true });
  });

  // FD-03: writes file content to local path
  it('FD-03: writes file content to the correct local path', async () => {
    const fileContent = Buffer.from('hello world');
    const apiClient = makeApiClient({
      downloadFile: vi.fn().mockResolvedValue(fileContent),
    } as Partial<FireHubApiClient>);

    await downloadChatFiles(apiClient, [1], TEST_DIR);

    expect(fs.writeFile).toHaveBeenCalledWith(
      path.join(TEST_DIR, 'test.txt'),
      fileContent,
    );
  });

  // FD-03b: sanitizes spaces in filenames
  it('FD-03b: replaces spaces in filenames with underscores', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi.fn().mockResolvedValue({
        id: 1,
        originalName: '면담 노트.txt',
        mimeType: 'text/plain',
        fileCategory: 'TEXT',
        fileSize: 1024,
      }),
    } as Partial<FireHubApiClient>);

    const result = await downloadChatFiles(apiClient, [1], TEST_DIR);

    expect(result.files[0].localPath).toBe(path.join(TEST_DIR, '면담_노트.txt'));
    expect(result.files[0].originalName).toBe('면담 노트.txt');
  });

  // FD-04: handles partial failures with Promise.allSettled
  it('FD-04: partial failure — failed files counted, successful files returned', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi
        .fn()
        .mockResolvedValueOnce({
          id: 1,
          originalName: 'ok.txt',
          mimeType: 'text/plain',
          fileCategory: 'TEXT',
          fileSize: 100,
        })
        .mockRejectedValueOnce(new Error('File not found')),
      downloadFile: vi.fn().mockResolvedValue(Buffer.from('content')),
    } as Partial<FireHubApiClient>);

    const result = await downloadChatFiles(apiClient, [1, 2], TEST_DIR);

    expect(result.files).toHaveLength(1);
    expect(result.failed).toBe(1);
    expect(result.files[0].originalName).toBe('ok.txt');
  });

  // FD-05: all files fail — returns empty files array
  it('FD-05: all downloads fail — returns empty files array and failed count', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi.fn().mockRejectedValue(new Error('Network error')),
    } as Partial<FireHubApiClient>);

    const result = await downloadChatFiles(apiClient, [1, 2, 3], TEST_DIR);

    expect(result.files).toHaveLength(0);
    expect(result.failed).toBe(3);
  });

  // FD-06: empty fileIds returns empty results
  it('FD-06: empty fileIds returns empty files with zero failed', async () => {
    const apiClient = makeApiClient();
    const result = await downloadChatFiles(apiClient, [], TEST_DIR);

    expect(result.files).toHaveLength(0);
    expect(result.failed).toBe(0);
  });

  // FD-07: multiple files all succeed
  it('FD-07: multiple files all succeed', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi
        .fn()
        .mockResolvedValueOnce({
          id: 1,
          originalName: 'image.png',
          mimeType: 'image/png',
          fileCategory: 'IMAGE',
          fileSize: 2048,
        })
        .mockResolvedValueOnce({
          id: 2,
          originalName: 'data.csv',
          mimeType: 'text/csv',
          fileCategory: 'DATA',
          fileSize: 512,
        }),
      downloadFile: vi.fn().mockResolvedValue(Buffer.from('binary')),
    } as Partial<FireHubApiClient>);

    const result = await downloadChatFiles(apiClient, [1, 2], TEST_DIR);

    expect(result.files).toHaveLength(2);
    expect(result.failed).toBe(0);
    expect(result.files[0].originalName).toBe('image.png');
    expect(result.files[1].originalName).toBe('data.csv');
  });

  // FD-08: warns when some downloads fail
  it('FD-08: logs warning when files fail to download', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi.fn().mockRejectedValue(new Error('403 Forbidden')),
    } as Partial<FireHubApiClient>);

    await downloadChatFiles(apiClient, [1], TEST_DIR);

    expect(console.warn).toHaveBeenCalledWith(
      expect.stringContaining('1 file(s) failed'),
      expect.any(Array),
    );
  });
});

describe('cleanupChatFiles', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // FD-09: removes directory
  it('FD-09: removes directory recursively and forcefully', async () => {
    await cleanupChatFiles(TEST_DIR);

    expect(fs.rm).toHaveBeenCalledWith(
      TEST_DIR,
      { recursive: true, force: true },
    );
  });

  // FD-10: does not throw if directory does not exist
  it('FD-10: silently succeeds even if directory does not exist', async () => {
    (fs.rm as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('ENOENT'));

    await expect(cleanupChatFiles('/tmp/nonexistent')).resolves.toBeUndefined();
  });
});

describe('toAttachmentMeta', () => {
  it('converts DownloadedFile array to AttachmentMeta array', () => {
    const files = [
      {
        fileId: 'file-1',
        originalName: 'report.pdf',
        mimeType: 'application/pdf',
        fileSize: 1024,
        fileCategory: 'document',
        localPath: '/tmp/report.pdf',
        uploadedAt: new Date().toISOString(),
      },
      {
        fileId: 'file-2',
        originalName: 'data.csv',
        mimeType: 'text/csv',
        fileSize: 512,
        fileCategory: 'data',
        localPath: '/tmp/data.csv',
        uploadedAt: new Date().toISOString(),
      },
    ];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const result = toAttachmentMeta(files as any);
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ id: 'file-1', name: 'report.pdf', mimeType: 'application/pdf', fileSize: 1024, category: 'document' });
    expect(result[1]).toEqual({ id: 'file-2', name: 'data.csv', mimeType: 'text/csv', fileSize: 512, category: 'data' });
  });

  it('returns empty array for empty input', () => {
    const result = toAttachmentMeta([]);
    expect(result).toEqual([]);
  });
});

describe('purgeExpiredSessionAttachments', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // readdir 기본: 빈 디렉터리
    (fs.readdir as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (fs.stat as ReturnType<typeof vi.fn>).mockResolvedValue({ mtimeMs: Date.now() });
    (fs.unlink as ReturnType<typeof vi.fn>).mockResolvedValue(undefined);
  });

  // FD-P01: 디렉터리 없으면 아무 것도 하지 않음
  it('FD-P01: silently returns when attachments directory does not exist', async () => {
    (fs.readdir as ReturnType<typeof vi.fn>).mockRejectedValue(Object.assign(new Error('ENOENT'), { code: 'ENOENT' }));

    await expect(purgeExpiredSessionAttachments()).resolves.toBeUndefined();
    expect(fs.unlink).not.toHaveBeenCalled();
  });

  // FD-P02: TTL 이내 파일은 삭제하지 않음
  it('FD-P02: does not delete files within TTL', async () => {
    (fs.readdir as ReturnType<typeof vi.fn>).mockResolvedValue(['session-abc.json']);
    // mtimeMs = 현재 시각 (만료 안 됨)
    (fs.stat as ReturnType<typeof vi.fn>).mockResolvedValue({ mtimeMs: Date.now() });

    await purgeExpiredSessionAttachments();

    expect(fs.unlink).not.toHaveBeenCalled();
  });

  // FD-P03: TTL 초과 파일은 삭제
  it('FD-P03: deletes expired sidecar files older than 7 days', async () => {
    (fs.readdir as ReturnType<typeof vi.fn>).mockResolvedValue(['old-session.json', 'recent.json']);
    const EIGHT_DAYS_AGO = Date.now() - 8 * 24 * 60 * 60 * 1000;
    (fs.stat as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce({ mtimeMs: EIGHT_DAYS_AGO }) // old-session.json → 만료
      .mockResolvedValueOnce({ mtimeMs: Date.now() });    // recent.json → 유효

    await purgeExpiredSessionAttachments();

    expect(fs.unlink).toHaveBeenCalledTimes(1);
    expect((fs.unlink as ReturnType<typeof vi.fn>).mock.calls[0][0]).toContain('old-session.json');
  });

  // FD-P04: .json 확장자가 아닌 파일은 무시
  it('FD-P04: ignores non-json files in the directory', async () => {
    (fs.readdir as ReturnType<typeof vi.fn>).mockResolvedValue(['session.json', 'README.txt']);
    (fs.stat as ReturnType<typeof vi.fn>).mockResolvedValue({ mtimeMs: 0 }); // 무조건 만료

    await purgeExpiredSessionAttachments();

    // README.txt는 stat도 호출되지 않아야 함
    expect(fs.stat).toHaveBeenCalledTimes(1);
    expect(fs.unlink).toHaveBeenCalledTimes(1);
  });

  // FD-P05: 개별 파일 처리 실패는 전체를 중단하지 않음
  it('FD-P05: continues purging other files when one file fails', async () => {
    (fs.readdir as ReturnType<typeof vi.fn>).mockResolvedValue(['a.json', 'b.json']);
    const EIGHT_DAYS_AGO = Date.now() - 8 * 24 * 60 * 60 * 1000;
    (fs.stat as ReturnType<typeof vi.fn>).mockResolvedValue({ mtimeMs: EIGHT_DAYS_AGO });
    (fs.unlink as ReturnType<typeof vi.fn>)
      .mockRejectedValueOnce(new Error('Permission denied')) // a.json 실패
      .mockResolvedValueOnce(undefined);                    // b.json 성공

    await expect(purgeExpiredSessionAttachments()).resolves.toBeUndefined();
    expect(fs.unlink).toHaveBeenCalledTimes(2);
  });
});
