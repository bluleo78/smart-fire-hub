/**
 * API 연결 도메인 타입 정의
 * 백엔드 DTO와 1:1 매핑되는 인터페이스를 정의한다.
 */

/**
 * API 연결 인증 방식 — 서버가 받는 값.
 * BASIC(WD-681): authConfig { username, password }. 응답의 maskedAuthConfig 는 아이디는 그대로, 비밀번호는 가린 값이다.
 */
export type ApiConnectionAuthType = 'API_KEY' | 'BEARER' | 'BASIC';

/**
 * 조회로 내려올 수 있는 인증 방식 — 받는 값(ApiConnectionAuthType)에 더해, DB CHECK 제약이 아직 허용하는 옛 OAUTH2 행도 있다.
 * 서버는 OAUTH2 를 새로 받지 않지만(400) 예전에 저장된 행은 그대로 조회되므로, 화면은 이 값을 표시 이름 없이 서버 값 그대로 보인다.
 */
export type StoredApiConnectionAuthType = ApiConnectionAuthType | 'OAUTH2';

/** API 연결 응답 (기존 + 신규 상태 필드) */
export interface ApiConnectionResponse {
  id: number;
  name: string;
  description: string | null;
  authType: StoredApiConnectionAuthType;
  maskedAuthConfig: Record<string, string>;
  /**
   * 외부 API의 기본 URL (예: https://api.example.com).
   * 옛 연결처럼 사용자 정보가 들어 있으면 서버가 비밀번호를 가려 `https://user:****@host` 로 내려준다(WD-681) —
   * 화면은 이 값을 그대로 보이고 그대로 다시 보낸다(서버가 가린 값을 알아보고 저장된 원래 값을 유지한다).
   */
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
  authType: ApiConnectionAuthType;
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
  /** 상세 편집은 조회한 방식(옛 OAUTH2 포함)을 그대로 들고 시작하므로 조회 값 범위를 받는다 — 서버가 받지 않는 값은 400 으로 안내한다. */
  authType?: StoredApiConnectionAuthType;
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
  /** 실제로 호출된 URL (baseUrl + healthCheckPath 합성 결과) */
  requestUrl: string | null;
  /** 응답 본문 미리보기 (최대 4KB, 잘리면 "... (truncated)" 표시) */
  responseBodyPreview: string | null;
  /** 응답 헤더 맵. Authorization/Set-Cookie/Cookie/X-Api-Key 등 민감 헤더는 "****"로 마스킹됨. */
  responseHeaders: Record<string, string>;
  /** 응답 Content-Type — JSON pretty-print 판단용 */
  responseContentType: string | null;
}
