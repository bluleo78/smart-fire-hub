/**
 * 플랫폼 평면 DTO 와 1:1 대응하는 타입.
 * 근거: apps/firehub-api/src/main/java/com/smartfirehub/platform/dto/*.java
 * 여기 없는 필드는 서버가 주지 않는 필드다 — 화면에서 만들어 쓰지 않는다.
 */

export interface PlatformTokenResponse {
  accessToken: string;
  /**
   * 백엔드 레코드에는 필드가 있지만 응답 본문에서는 쓰지 않는다 — 리프레시 토큰은
   * HttpOnly 쿠키 `platformRefreshToken`(path=/api/platform/auth)으로 온다.
   * 클라이언트는 이 값을 절대 저장하지 않는다.
   */
  refreshToken: string | null;
  tokenType: string;
  expiresIn: number;
  permissions: string[];
}

export interface PlatformMeResponse {
  userId: number;
  username: string;
  name: string;
  permissions: string[];
}

/** `GET /tenants` 목록 행과 `GET /tenants/{id}` 상세가 **같은 DTO** 다. 상세에 더 있는 것이 없다. */
export interface TenantSummaryResponse {
  id: number;
  slug: string;
  name: string;
  /** 'ACTIVE' | 'SUSPENDED' — 서버가 문자열로 준다. */
  status: string;
  memberCount: number;
  /** ISO 8601 + 저장 TZ 오프셋(예: 2026-03-03T15:30:00Z, WD-11). 표시는 formatDateOnly/formatDateTimeMinute(로컬). */
  createdAt: string;
}

/**
 * `role` 은 `membership.role`(OWNER/ADMIN/MEMBER)로 **표시용**이다.
 * 실제 권한은 테넌트 평면 RBAC 이고 운영자 평면은 그것을 읽지 않는다(`role` 은 RLS 테이블이라
 * 플랫폼 토큰으로 조회하면 조용히 0행이 된다).
 */
export interface TenantMemberResponse {
  userId: number;
  username: string;
  email: string | null;
  role: string;
  status: string;
}

export interface CreateTenantRequest {
  slug: string;
  name: string;
  ownerUserId: number;
}

/** `GET /api/platform/users?q=` 응답 1건. 열거 표면을 넓히지 않도록 최소 3필드다. */
export interface PlatformUserResponse {
  id: number;
  email: string | null;
  name: string;
}

/**
 * `GET /api/platform/accounts?q=` 응답 1건(#784). Owner 검색(`PlatformUserResponse`)과 달리 비활성 계정도 나오고
 * 활성·운영자 여부를 싣는다 — 비활성화한 계정을 다시 찾아 재활성화해야 하기 때문이다.
 */
export interface PlatformAccountResponse {
  id: number;
  /** 로그인 아이디. */
  username: string;
  email: string | null;
  name: string;
  /** 전역 계정 활성 여부(`user.is_active`). 워크스페이스 멤버십 상태가 아니다. */
  active: boolean;
  /** 플랫폼 롤 보유자. 이 화면에서 비활성화할 수 없다(서버도 409). */
  operator: boolean;
}

/** 서버 `PageResponse<T>` 와 1:1. */
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * `GET /api/platform/audit-logs` 한 행(WD-4). 서버 `AuditLogResponse` 그대로 — 테넌트 감사 로그와 같은 DTO 다.
 * 이 화면에는 tenant_id 가 NULL 인 행(운영자 계정 조치, 워크스페이스가 정해지지 않은 로그인)만 온다.
 */
export interface PlatformAuditLogResponse {
  id: number;
  userId: number | null;
  /** 행위자 username(운영자 조치면 운영자, 로그인이면 그 사용자). */
  username: string;
  actionType: string;
  resource: string;
  resourceId: string | null;
  description: string | null;
  /** ISO 8601 + 저장 TZ 오프셋(예: 2026-09-30T15:30:00Z). 표시는 formatDateTimeSecond(로컬). */
  actionTime: string;
  ipAddress: string | null;
  userAgent: string | null;
  /** 'SUCCESS' | 'FAILURE' */
  result: string;
  errorMessage: string | null;
  /** 운영자 계정 조치는 { plane, targetUsername }, 테넌트 생명주기는 { plane, tenantSlug, tenantName }(WD-12). */
  metadata: Record<string, unknown> | null;
}

/** 조회 파라미터. undefined 는 axios 가 쿼리에서 뺀다. from(이상)·to(미만)는 오프셋 포함 절대 시각(WD-11). */
export interface PlatformAuditLogQuery {
  actor?: string;
  target?: string;
  actionType?: string;
  from?: string;
  to?: string;
  page: number;
  size: number;
}

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  errors?: Record<string, string>;
}
