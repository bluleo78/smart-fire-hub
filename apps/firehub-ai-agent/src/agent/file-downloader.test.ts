import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import path from 'path';
import os from 'os';

// Mock fs/promises
vi.mock('fs/promises', () => ({
  default: {
    mkdir: vi.fn().mockResolvedValue(undefined),
    writeFile: vi.fn().mockResolvedValue(undefined),
    rm: vi.fn().mockResolvedValue(undefined),
  },
}));

import fs from 'fs/promises';
import { downloadChatFiles, cleanupChatFiles } from './file-downloader.js';
import type { FireHubApiClient } from '../mcp/api-client.js';

const CHAT_FILES_DIR = path.join(os.tmpdir(), 'firehub-chat-files');

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
    const result = await downloadChatFiles(apiClient, [1], 'user1-123');

    expect(result.files).toHaveLength(1);
    expect(result.failed).toBe(0);
    expect(result.files[0]).toMatchObject({
      originalName: 'test.txt',
      mimeType: 'text/plain',
      fileCategory: 'TEXT',
      fileSize: 1024,
      localPath: path.join(CHAT_FILES_DIR, 'user1-123', 'test.txt'),
    });
  });

  // FD-02: creates the session directory
  it('FD-02: creates session directory with recursive option', async () => {
    const apiClient = makeApiClient();
    await downloadChatFiles(apiClient, [1], 'user1-456');

    expect(fs.mkdir).toHaveBeenCalledWith(
      path.join(CHAT_FILES_DIR, 'user1-456'),
      { recursive: true },
    );
  });

  // FD-03: writes file content to local path
  it('FD-03: writes file content to the correct local path', async () => {
    const fileContent = Buffer.from('hello world');
    const apiClient = makeApiClient({
      downloadFile: vi.fn().mockResolvedValue(fileContent),
    } as Partial<FireHubApiClient>);

    await downloadChatFiles(apiClient, [1], 'user1-789');

    expect(fs.writeFile).toHaveBeenCalledWith(
      path.join(CHAT_FILES_DIR, 'user1-789', 'test.txt'),
      fileContent,
    );
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

    const result = await downloadChatFiles(apiClient, [1, 2], 'user1-partial');

    expect(result.files).toHaveLength(1);
    expect(result.failed).toBe(1);
    expect(result.files[0].originalName).toBe('ok.txt');
  });

  // FD-05: all files fail — returns empty files array
  it('FD-05: all downloads fail — returns empty files array and failed count', async () => {
    const apiClient = makeApiClient({
      getFileInfo: vi.fn().mockRejectedValue(new Error('Network error')),
    } as Partial<FireHubApiClient>);

    const result = await downloadChatFiles(apiClient, [1, 2, 3], 'user1-allfail');

    expect(result.files).toHaveLength(0);
    expect(result.failed).toBe(3);
  });

  // FD-06: empty fileIds returns empty results
  it('FD-06: empty fileIds returns empty files with zero failed', async () => {
    const apiClient = makeApiClient();
    const result = await downloadChatFiles(apiClient, [], 'user1-empty');

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

    const result = await downloadChatFiles(apiClient, [1, 2], 'user1-multi');

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

    await downloadChatFiles(apiClient, [1], 'user1-warn');

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

  // FD-09: removes session directory
  it('FD-09: removes session directory recursively and forcefully', async () => {
    await cleanupChatFiles('user1-cleanup');

    expect(fs.rm).toHaveBeenCalledWith(
      path.join(CHAT_FILES_DIR, 'user1-cleanup'),
      { recursive: true, force: true },
    );
  });

  // FD-10: does not throw if directory does not exist
  it('FD-10: silently succeeds even if directory does not exist', async () => {
    (fs.rm as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('ENOENT'));

    await expect(cleanupChatFiles('user1-missing')).resolves.toBeUndefined();
  });
});
