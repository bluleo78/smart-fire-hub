/**
 * 폴더 업로드용 파일 수집 유틸.
 * 드래그-드롭(FileSystemEntry 재귀 순회)과 폴더 선택(webkitdirectory)에서
 * "파일 + 상대경로"를 만들어 업로드 뮤테이션에 넘긴다. 상대경로는 서버가 "<prefix><상대경로>" 키로
 * 저장하므로 폴더 구조가 그대로 보존된다.
 */

/** 업로드 항목 — 실제 파일 + 저장 상대경로(평면 파일은 파일명, 폴더 파일은 하위 경로 포함). */
export interface UploadItem {
  file: File;
  path: string;
}

/**
 * FileSystemEntry(webkit)를 재귀 순회하여 파일과 상대경로를 수집한다.
 * base는 현재 엔트리의 부모 경로(끝에 '/' 포함 또는 빈 문자열).
 */
export async function collectEntry(
  entry: FileSystemEntry,
  base: string,
  out: UploadItem[],
): Promise<void> {
  if (entry.isFile) {
    const fileEntry = entry as FileSystemFileEntry;
    const file = await new Promise<File>((resolve, reject) => fileEntry.file(resolve, reject));
    out.push({ file, path: base + entry.name });
    return;
  }
  if (entry.isDirectory) {
    const reader = (entry as FileSystemDirectoryEntry).createReader();
    // readEntries는 한 번에 일부만 반환하므로 빈 배열이 나올 때까지 반복 호출해야 전부 읽는다.
    const readBatch = (): Promise<FileSystemEntry[]> =>
      new Promise((resolve, reject) => reader.readEntries(resolve, reject));
    let batch = await readBatch();
    while (batch.length > 0) {
      for (const child of batch) await collectEntry(child, base + entry.name + '/', out);
      batch = await readBatch();
    }
  }
}

/** 여러 최상위 엔트리(드롭된 파일/폴더 혼합)를 순회해 업로드 항목 목록으로 만든다. */
export async function collectEntries(entries: FileSystemEntry[]): Promise<UploadItem[]> {
  const out: UploadItem[] = [];
  for (const entry of entries) await collectEntry(entry, '', out);
  return out;
}

/**
 * File 목록을 업로드 항목으로 변환한다. webkitdirectory 선택 시 각 File은 webkitRelativePath를 가지므로
 * 그 값을 상대경로로 쓰고, 평면 파일 선택이면 파일명만 쓴다.
 */
export function filesToItems(files: File[]): UploadItem[] {
  return files.map((file) => ({ file, path: file.webkitRelativePath || file.name }));
}
