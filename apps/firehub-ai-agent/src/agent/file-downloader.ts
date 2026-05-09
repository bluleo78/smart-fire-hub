import fs from 'fs/promises';
import os from 'os';
import path from 'path';
import { FireHubApiClient } from '../mcp/api-client.js';

export interface DownloadedFile {
  fileId: number;
  originalName: string;
  localPath: string;
  mimeType: string;
  fileCategory: string;
  fileSize: number;
}

/** 히스토리에서 사용하는 첨부 파일 메타데이터 (로컬 경로 제외) */
export interface AttachmentMeta {
  id: number;
  name: string;
  mimeType: string;
  fileSize: number;
  category: string;
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
        fileId,
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

/** DownloadedFile 배열 → AttachmentMeta 배열 변환 */
export function toAttachmentMeta(files: DownloadedFile[]): AttachmentMeta[] {
  return files.map((f) => ({
    id: f.fileId,
    name: f.originalName,
    mimeType: f.mimeType,
    fileSize: f.fileSize,
    category: f.fileCategory,
  }));
}

/** 세션별 첨부 메타데이터 사이드카 파일 경로 */
const ATTACHMENTS_DIR = path.join(os.homedir(), '.firehub', 'session-attachments');

/** 사이드카 파일 TTL: 7일 이상 된 파일은 만료로 간주 */
const SIDECAR_TTL_MS = 7 * 24 * 60 * 60 * 1000;

function attachmentPath(sessionId: string): string {
  return path.join(ATTACHMENTS_DIR, `${sessionId}.json`);
}

/**
 * 만료된 사이드카 파일 일괄 삭제.
 * TTL(7일)을 초과한 *.json 파일을 비동기로 제거한다.
 * saveSessionAttachments 호출 시 백그라운드에서 실행되어 디스크 누수를 방지한다.
 */
export async function purgeExpiredSessionAttachments(): Promise<void> {
  let entries: string[];
  try {
    entries = await fs.readdir(ATTACHMENTS_DIR);
  } catch {
    // 디렉터리가 없으면 정리 불필요
    return;
  }
  const now = Date.now();
  await Promise.allSettled(
    entries
      .filter((name) => name.endsWith('.json'))
      .map(async (name) => {
        const filePath = path.join(ATTACHMENTS_DIR, name);
        try {
          const stat = await fs.stat(filePath);
          if (now - stat.mtimeMs > SIDECAR_TTL_MS) {
            await fs.unlink(filePath);
          }
        } catch {
          // 개별 파일 실패는 무시 (이미 삭제된 경우 등)
        }
      }),
  );
}

/** 세션에 연결된 첨부 파일 메타데이터를 사이드카 파일로 저장 */
export async function saveSessionAttachments(
  sessionId: string,
  attachments: AttachmentMeta[],
): Promise<void> {
  if (attachments.length === 0) return;
  await fs.mkdir(ATTACHMENTS_DIR, { recursive: true });
  // 기존 첨부에 추가 (멀티턴 대응)
  const existing = await loadSessionAttachments(sessionId);
  const merged = [...existing, ...attachments];
  await fs.writeFile(attachmentPath(sessionId), JSON.stringify(merged));
  // 만료된 사이드카 파일 백그라운드 정리 (디스크 누수 방지)
  purgeExpiredSessionAttachments().catch(() => {});
}

/** 세션의 첨부 파일 메타데이터 로드 (없으면 빈 배열) */
export async function loadSessionAttachments(sessionId: string): Promise<AttachmentMeta[]> {
  try {
    const data = await fs.readFile(attachmentPath(sessionId), 'utf-8');
    return JSON.parse(data) as AttachmentMeta[];
  } catch {
    return [];
  }
}
