import fs from 'fs/promises';
import path from 'path';
import os from 'os';
import { FireHubApiClient } from '../mcp/api-client.js';

const CHAT_FILES_DIR = path.join(os.tmpdir(), 'firehub-chat-files');

export interface DownloadedFile {
  originalName: string;
  localPath: string;
  mimeType: string;
  fileCategory: string;
  fileSize: number;
}

export async function downloadChatFiles(
  apiClient: FireHubApiClient,
  fileIds: number[],
  sessionTag: string,
): Promise<{ files: DownloadedFile[]; failed: number }> {
  const sessionDir = path.join(CHAT_FILES_DIR, sessionTag);
  await fs.mkdir(sessionDir, { recursive: true });

  const results = await Promise.allSettled(
    fileIds.map(async (fileId) => {
      const info = await apiClient.getFileInfo(fileId);
      const content = await apiClient.downloadFile(fileId);
      // Use safe ASCII filename to avoid Claude CLI Read tool issues with non-ASCII characters
      const ext = path.extname(info.originalName) || '.bin';
      const safeName = `file-${fileId}${ext}`;
      const localPath = path.join(sessionDir, safeName);
      await fs.writeFile(localPath, content);
      return {
        originalName: info.originalName,
        localPath,
        mimeType: info.mimeType,
        fileCategory: info.fileCategory,
        fileSize: info.fileSize,
      };
    }),
  );

  const files = results
    .filter((r): r is PromiseFulfilledResult<DownloadedFile> => r.status === 'fulfilled')
    .map((r) => r.value);
  const failed = results.filter((r) => r.status === 'rejected').length;

  if (failed > 0) {
    const reasons = results
      .filter((r): r is PromiseRejectedResult => r.status === 'rejected')
      .map((r) => String(r.reason));
    console.warn(`[FileDownloader] ${failed} file(s) failed to download:`, reasons);
  }

  return { files, failed };
}

export async function cleanupChatFiles(sessionTag: string): Promise<void> {
  const sessionDir = path.join(CHAT_FILES_DIR, sessionTag);
  await fs.rm(sessionDir, { recursive: true, force: true }).catch(() => {});
}
