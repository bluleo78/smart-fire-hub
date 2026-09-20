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

/**
 * `GET /api/platform/settings/ai-credential` 응답 — 백엔드 `PlatformAiCredentialView` 와 1:1
 * 대응(타입형 AI 설정 전환, Task 13). 테넌트 평면의 `AiCredentialResponse`(firehub-web)와 달리
 * `tenantOwned` 필드가 **없다** — 플랫폼에는 상위 평면이 없어 그 개념 자체가 성립하지 않는다.
 *
 * - `payload`: 평문(비밀 아님) — baseURL·providerId·reasoningEffort 등.
 * - `secretFieldNames`: 값이 실재하는 비밀 필드 이름만. 값 자체는 어떤 경로로도 내려오지 않는다.
 */
export interface PlatformAiCredentialResponse {
  agentType: string;
  payload: Record<string, unknown>;
  secretFieldNames: string[];
}

/**
 * `PUT /api/platform/settings/ai-credential` 요청 바디 — 백엔드 `AiCredentialUpsertRequest` 와
 * 1:1 대응(테넌트/플랫폼 공용 DTO). `secret` 계약: 필드를 생략하면 현재 값 유지, 빈 문자열이면
 * 삭제. `null` 은 서버가 400 으로 거부한다.
 */
export interface PlatformAiCredentialUpsertPayload {
  agentType: string;
  payload: Record<string, string>;
  secret: Record<string, string>;
}

/**
 * `POST /api/platform/settings/ai-credential/probe` 요청 바디 — opencode 전용 모델 목록 조회.
 *
 * <b>플랫폼 평면은 `apiKey` 생략을 허용하지 않는다</b>(설계서 「프로브」 절, Ruling #24 —
 * `PlatformAiCredentialController` javadoc). 테넌트 평면의 "생략 시 저장된 값 재사용"은 상위
 * 평면(플랫폼)의 값을 빌려주지 <b>않기 위한</b> 장치였는데, 플랫폼 자체가 최상위라 빌려줄
 * 상위가 없다 — 그래서 이 타입에서는 `apiKey` 를 선택(optional)이 아니라 **필수**로 둔다.
 */
export interface PlatformAiCredentialProbeRequest {
  baseURL: string;
  apiKey: string;
}

/**
 * 프로브 응답 — 성공·실패 모두 HTTP 200 으로 오고(`SMTP 연결 테스트` 선례), 이 바디의 `ok` 로
 * 판정한다. 요청 형태 자체가 잘못됐을 때(예: apiKey 누락)만 4xx 다.
 */
export interface PlatformAiCredentialProbeResponse {
  ok: boolean;
  models: string[];
  message: string | null;
}

/**
 * `GET /api/platform/ai/auth-status` 응답(Ruling #47, Task 13 fix round 1) — 백엔드
 * `PlatformAiController` 의 사실상 유일한 응답 모양이자, 테넌트 평면 `AiController` 와 문자
 * 그대로 같다. `applicable:false` 는 opencode(Anthropic 인증 개념이 없다) 전용이고, 그 외
 * 세 유형은 `email`/`subscriptionType` 이 있을 수도 없을 수도 있다(ai-agent 응답을 그대로
 * 통과시킨다).
 */
export interface PlatformAiAuthStatusResponse {
  valid: boolean;
  applicable?: boolean;
  email?: string;
  subscriptionType?: string;
}
