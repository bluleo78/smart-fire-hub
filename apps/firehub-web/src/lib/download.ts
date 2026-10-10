export function downloadBlob(filename: string, blob: Blob): void {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function downloadCsv(filename: string, csvContent: string): void {
  const blob = new Blob([csvContent], { type: 'text/csv;charset=utf-8;' });
  downloadBlob(filename, blob);
}

/**
 * 응답 Content-Disposition 에서 파일 이름을 꺼낸다. RFC 5987 `filename*=UTF-8''...`(한글 이름)를 먼저 쓰고, 없으면 `filename="..."`,
 * 둘 다 없거나 해석할 수 없으면 fallback 을 돌려준다. 서버가 정한 이름(행 상한 잘림 접미사 등)을 화면 상태로 다시 만들지 않기 위해 쓴다.
 */
export function filenameFromContentDisposition(header: string | null | undefined, fallback: string): string {
  if (!header) return fallback;
  const extended = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header);
  if (extended) {
    try {
      const name = decodeURIComponent(extended[2].trim().replace(/^"|"$/g, ''));
      if (name) return name;
    } catch {
      // 잘못된 퍼센트 인코딩 — 아래 filename 으로 넘어간다.
    }
  }
  const plain = /filename\s*=\s*"([^"]*)"|filename\s*=\s*([^;]+)/i.exec(header);
  const name = (plain?.[1] ?? plain?.[2])?.trim();
  return name || fallback;
}
