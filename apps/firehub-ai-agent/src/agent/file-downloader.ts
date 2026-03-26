import fs from 'fs/promises';
import path from 'path';
import { FireHubApiClient } from '../mcp/api-client.js';

export interface DownloadedFile {
  originalName: string;
  localPath: string;
  mimeType: string;
  fileCategory: string;
  fileSize: number;
}

/** Replace characters that break Claude CLI's Read tool (spaces, etc.) */
function sanitizeFilename(name: string): string {
  return name.replace(/\s+/g, '_');
}

export async function downloadChatFiles(
  apiClient: FireHubApiClient,
  fileIds: number[],
  downloadDir: string,
): Promise<{ files: DownloadedFile[]; failed: number }> {
  await fs.mkdir(downloadDir, { recursive: true });

  const results = await Promise.allSettled(
    fileIds.map(async (fileId) => {
      const info = await apiClient.getFileInfo(fileId);
      const content = await apiClient.downloadFile(fileId);
      const safeName = sanitizeFilename(info.originalName);
      const localPath = path.join(downloadDir, safeName);
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

export async function cleanupChatFiles(dir: string): Promise<void> {
  await fs.rm(dir, { recursive: true, force: true }).catch(() => {});
}
