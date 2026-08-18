import fs from 'fs/promises';
import type { Dirent } from 'fs';
import path from 'path';
import { FireHubApiClient } from '../mcp/api-client.js';
import { attachmentsDir, legacyAttachmentsDir } from './tenant-paths.js';

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

/**
 * 첨부 파일 목록의 한 줄 안내 포맷을 생성한다.
 *
 * <p>fileId 포함 사유 (refs #264): dataset-manager 등 임포트 워크플로의 MCP 도구
 * (preview_csv / validate_import / start_import)는 서버 측 fileId를 필수 인자로
 * 받는다. 안내문에서 누락되면 모델이 도구를 호출하지 못해 사용자가 직접 임포트하라는
 * 응답으로 빠진다.
 *
 * @param file 다운로드된 첨부 파일
 * @param includeCategory true면 카테고리(`csv`, `image` 등) 표시
 */
export function formatAttachmentLine(file: DownloadedFile, includeCategory: boolean): string {
  const sizeKb = (file.fileSize / 1024).toFixed(1);
  const meta = includeCategory ? `${file.fileCategory}, ${sizeKb}KB` : `${sizeKb}KB`;
  return `- ${file.originalName} (${meta}, fileId=${file.fileId}): ${file.localPath}`;
}

/**
 * 비이미지 파일 배열을 텍스트 안내 섹션으로 변환한다 (SDK/CLI 경로 공통 포맷).
 *
 * @returns "[첨부 파일]\n- ...\nRead 도구로 ..." 형식의 문자열. 파일이 없으면 빈 문자열.
 */
export function buildNonImageAttachmentSection(files: DownloadedFile[]): string {
  if (files.length === 0) return '';
  const lines = files.map((f) => formatAttachmentLine(f, true)).join('\n');
  return `[첨부 파일]\n${lines}\nRead 도구로 읽을 수 있습니다.`;
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

/** 사이드카 파일 TTL: 7일 이상 된 파일은 만료로 간주 */
const SIDECAR_TTL_MS = 7 * 24 * 60 * 60 * 1000;

function attachmentPath(tenantId: number, sessionId: string): string {
  return path.join(attachmentsDir(tenantId), `${sessionId}.json`);
}

/**
 * 만료된 사이드카 파일 일괄 삭제.
 * TTL(7일)을 초과한 *.json 파일을 비동기로 제거한다.
 * saveSessionAttachments 호출 시 백그라운드에서 실행되어 디스크 누수를 방지한다.
 *
 * <p><b>왜 전 테넌트를 도는가.</b> 이 함수는 호출한 테넌트의 저장 직후에 불리는데, 자기 테넌트만
 * 훑으면 요청이 오지 않는 테넌트의 만료 사이드카는 영구히 남는다(TTL 자체가 무력화된다). TTL
 * 정리는 파일 나이만 보는 순수 파일시스템 작업이라 테넌트 의미가 필요 없으므로, 베이스
 * 디렉터리의 `t*` 하위와 **테넌트 세그먼트 도입 전에 루트에 흩어진 레거시 파일**을 함께 훑는다.
 */
export async function purgeExpiredSessionAttachments(): Promise<void> {
  const base = legacyAttachmentsDir();
  let entries: Dirent[];
  try {
    entries = await fs.readdir(base, { withFileTypes: true });
  } catch {
    // 디렉터리가 없으면 정리 불필요
    return;
  }

  // 베이스에 바로 놓인 파일 = 테넌트 세그먼트 도입 전 레거시 사이드카. 하위 디렉터리 = 테넌트별.
  // 베이스의 목록은 위에서 이미 받았으므로 다시 readdir 하지 않는다.
  const groups: Array<{ dir: string; names: string[] }> = [
    { dir: base, names: entries.filter((e) => e.isFile()).map((e) => e.name) },
  ];
  for (const entry of entries.filter((e) => e.isDirectory())) {
    const dir = path.join(base, entry.name);
    try {
      groups.push({ dir, names: await fs.readdir(dir) });
    } catch {
      // 한 테넌트 디렉터리를 못 읽어도 나머지 정리는 계속한다.
    }
  }

  const now = Date.now();
  await Promise.allSettled(
    groups.flatMap(({ dir, names }) =>
      names
        .filter((name) => name.endsWith('.json'))
        .map(async (name) => {
          const filePath = path.join(dir, name);
          try {
            const stat = await fs.stat(filePath);
            if (now - stat.mtimeMs > SIDECAR_TTL_MS) {
              await fs.unlink(filePath);
            }
          } catch {
            // 개별 파일 실패는 무시 (이미 삭제된 경우 등)
          }
        }),
    ),
  );
}

/** 세션에 연결된 첨부 파일 메타데이터를 사이드카 파일로 저장 */
export async function saveSessionAttachments(
  tenantId: number,
  sessionId: string,
  attachments: AttachmentMeta[],
): Promise<void> {
  if (attachments.length === 0) return;
  await fs.mkdir(attachmentsDir(tenantId), { recursive: true });
  // 기존 첨부에 추가 (멀티턴 대응)
  const existing = await loadSessionAttachments(tenantId, sessionId);
  const merged = [...existing, ...attachments];
  await fs.writeFile(attachmentPath(tenantId, sessionId), JSON.stringify(merged));
  // 만료된 사이드카 파일 백그라운드 정리 (디스크 누수 방지)
  purgeExpiredSessionAttachments().catch(() => {});
}

/**
 * 세션의 첨부 파일 메타데이터 로드 (없으면 빈 배열).
 *
 * <p>테넌트 경로에 없으면 세그먼트 도입 전 레거시 경로를 한 번 더 본다 — 과거 세션의 첨부 표시가
 * 조용히 사라지지 않게 하기 위한 읽기 전용 폴백이다(쓰기는 항상 테넌트 경로로만 간다).
 */
export async function loadSessionAttachments(
  tenantId: number,
  sessionId: string,
): Promise<AttachmentMeta[]> {
  const candidates = [
    attachmentPath(tenantId, sessionId),
    path.join(legacyAttachmentsDir(), `${sessionId}.json`),
  ];
  for (const candidate of candidates) {
    try {
      const data = await fs.readFile(candidate, 'utf-8');
      return JSON.parse(data) as AttachmentMeta[];
    } catch {
      continue;
    }
  }
  return [];
}
