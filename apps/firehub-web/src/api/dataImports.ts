import type { ColumnMappingEntry,ImportPreviewResponse, ImportResponse, ImportStartResponse, ImportValidateResponse } from '../types/dataImport';
import { client } from './client';

/** 미리보기 전송 시 CSV를 잘라 보낼 최대 바이트. 헤더+샘플 5행 파싱에 충분하며, 대용량 파일 업로드로 인한 413(Entity Too Large)을 방지한다. */
const PREVIEW_MAX_BYTES = 2 * 1024 * 1024; // 2MB

/** CSV 여부 판별. CSV만 앞부분 슬라이스가 안전하다(XLSX/XLS는 zip 기반이라 일부만 자르면 파일이 깨짐). */
function isCsvFile(file: File): boolean {
  return /\.csv$/i.test(file.name) || file.type === 'text/csv';
}

/**
 * 대용량 CSV를 미리보기용으로 앞부분만 잘라낸다. 슬라이스 끝을 마지막 개행(\n)까지로 맞춰 부분 행이
 * 파서에 들어가지 않게 한다. 개행 바이트 0x0A는 UTF-8·EUC-KR·CP949에서 멀티바이트 문자 꼬리로
 * 나타나지 않으므로 인코딩과 무관하게 안전하다. 파일이 한도 이하이면 원본을 그대로 반환한다.
 */
async function sliceCsvHead(file: File, maxBytes: number): Promise<Blob> {
  if (file.size <= maxBytes) return file;
  const head = file.slice(0, maxBytes);
  const bytes = new Uint8Array(await head.arrayBuffer());
  let end = bytes.length;
  for (let i = bytes.length - 1; i >= 0; i--) {
    if (bytes[i] === 0x0a) {
      end = i + 1;
      break;
    }
  }
  return file.slice(0, end);
}

export const dataImportsApi = {
  uploadFile: (datasetId: number, file: File, mappings?: ColumnMappingEntry[], importMode?: string) => {
    const formData = new FormData();
    formData.append('file', file);
    if (mappings) {
      formData.append('mappings', JSON.stringify(mappings));
    }
    if (importMode) {
      formData.append('importMode', importMode);
    }
    return client.post<ImportStartResponse>(`/datasets/${datasetId}/imports`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  previewImport: async (datasetId: number, file: File) => {
    // 대용량 CSV는 앞부분만 잘라 전송해 413을 회피한다. XLSX/XLS는 잘라 보낼 수 없어 전체 전송.
    const payload = isCsvFile(file) ? await sliceCsvHead(file, PREVIEW_MAX_BYTES) : file;
    // 일부만 보낸 경우 partial=true로 알려 서버가 전체 행수 계산(countRows)을 건너뛰게 한다.
    const partial = payload.size < file.size;
    const formData = new FormData();
    // 파일명을 유지해 서버의 확장자 기반 파일 타입 판별을 보존한다.
    formData.append('file', payload, file.name);
    const url = `/datasets/${datasetId}/imports/preview${partial ? '?partial=true' : ''}`;
    return client.post<ImportPreviewResponse>(url, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  validateImport: (datasetId: number, file: File, mappings: ColumnMappingEntry[]) => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('mappings', JSON.stringify(mappings));
    return client.post<ImportValidateResponse>(`/datasets/${datasetId}/imports/validate`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  getImports: (datasetId: number) =>
    client.get<ImportResponse[]>(`/datasets/${datasetId}/imports`),
  getImportById: (datasetId: number, importId: number) =>
    client.get<ImportResponse>(`/datasets/${datasetId}/imports/${importId}`),
};
