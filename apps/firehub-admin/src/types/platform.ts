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
  /** `yyyy-MM-ddTHH:mm:ss` (타임존 없음). */
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
 * `GET /api/platform/settings` 응답 1건. `tenantOverridable` 도 `overridden` 도 **없다** —
 * 그건 테넌트 평면의 `ResolvedSettingResponse` 다. 재정의 가능 여부는 클라이언트 사본
 * (`lib/override-policy.ts`)이 판정한다.
 */
export interface SettingResponse {
  key: string;
  value: string | null;
  description: string | null;
  updatedAt: string | null;
}

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  errors?: Record<string, string>;
}
