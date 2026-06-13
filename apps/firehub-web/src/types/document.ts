// 백엔드 com.smartfirehub.document.dto 미러. 백엔드 DTO 변경 시 이 파일을 갱신한다.

/** 인제스션 상태. PENDING/PARSING/EMBEDDING=진행중, COMPLETED/FAILED=종료. */
export type DocumentStatus = 'PENDING' | 'PARSING' | 'EMBEDDING' | 'COMPLETED' | 'FAILED';

/** DocumentFileResponse — 문서 파일 메타. createdAt/completedAt은 오프셋 없는 ISO 문자열. */
export interface DocumentFileResponse {
  id: number;
  datasetId: number;
  originalName: string;
  mimeType: string;
  fileSize: number;
  status: DocumentStatus;
  pageCount: number | null;
  chunkCount: number | null;
  errorDetail: string | null;
  uploadedBy: number;
  createdAt: string;
  completedAt: string | null;
}

// 검색 모드: 의미(벡터) / 키워드(트라이그램) / 하이브리드(RRF, 기본)
export type DocumentSearchMode = 'SEMANTIC' | 'KEYWORD' | 'HYBRID';

/** DocumentSearchRequest — 의미검색 요청. topK 기본 5, 최대 20(백엔드에서 정규화). */
export interface DocumentSearchRequest {
  query: string;
  datasetIds?: number[];
  topK?: number;
  // 생략 시 서버 기본 HYBRID
  mode?: DocumentSearchMode;
}

/** DocumentSearchHit — 코사인 검색 결과 1건. score는 1-거리(1=완전일치). */
export interface DocumentSearchHit {
  chunkId: number;
  documentFileId: number;
  datasetId: number;
  fileName: string;
  chunkIndex: number;
  content: string;
  score: number;
}
