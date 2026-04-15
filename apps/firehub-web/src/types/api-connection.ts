/**
 * API 연결 도메인 타입 정의
 * 백엔드 DTO와 1:1 매핑되는 인터페이스를 정의한다.
 */

/** API 연결 응답 (기존 + 신규 상태 필드) */
export interface ApiConnectionResponse {
  id: number;
  name: string;
  description: string | null;
  authType: 'API_KEY' | 'BEARER';
  maskedAuthConfig: Record<string, string>;
  /** 외부 API의 기본 URL (예: https://api.example.com) */
  baseUrl: string;
  /** 헬스체크 경로 (/로 시작, 선택). null이면 자동 점검 안 함 */
  healthCheckPath: string | null;
  /** 마지막 헬스체크 결과 (UP/DOWN/null=미확인) */
  lastStatus: 'UP' | 'DOWN' | null;
  /** 마지막 헬스체크 시각 (ISO 8601) */
  lastCheckedAt: string | null;
  /** 마지막 응답 지연 (ms) */
  lastLatencyMs: number | null;
  /** 마지막 에러 메시지 */
  lastErrorMessage: string | null;
  createdBy: number;
  createdAt: string;
  updatedAt: string;
}

/** API 연결 생성 요청 */
export interface CreateApiConnectionRequest {
  name: string;
  description?: string;
  authType: 'API_KEY' | 'BEARER';
  authConfig: Record<string, string>;
  /** 외부 API 기본 URL (필수, http/https로 시작) */
  baseUrl: string;
  /** 헬스체크 경로 (선택, /로 시작) */
  healthCheckPath?: string;
}

/** API 연결 수정 요청 */
export interface UpdateApiConnectionRequest {
  name?: string;
  description?: string;
  authType?: 'API_KEY' | 'BEARER';
  authConfig?: Record<string, string>;
  baseUrl?: string;
  healthCheckPath?: string;
}

/**
 * 파이프라인 스텝 선택용 슬림 API 연결 정보
 * GET /api/v1/api-connections/selectable 응답
 */
export interface ApiConnectionSelectable {
  id: number;
  name: string;
  authType: string;
  baseUrl: string;
}

/**
 * 연결 테스트 응답
 * POST /api/v1/api-connections/:id/test 응답
 */
export interface TestConnectionResponse {
  ok: boolean;
  status: number | null;
  latencyMs: number;
  errorMessage: string | null;
}
