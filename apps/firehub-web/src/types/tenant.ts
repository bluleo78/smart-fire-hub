/**
 * 테넌트(워크스페이스) 관련 타입. 백엔드 `com.smartfirehub.tenant.dto` 와 1:1 대응한다.
 */

/**
 * 사용자가 선택할 수 있는 테넌트 한 건.
 *
 * <p>`role` 은 **표시용 라벨**(OWNER/ADMIN/MEMBER)이다. 화면에 권한 배지를 그리는 용도로만 쓰고,
 * 인가 판단에는 절대 쓰지 않는다 — 실제 인가는 테넌트 평면 RBAC(`useAuth().roles`)가 담당한다.
 * 두 평면을 혼동하면 라벨만 바꿔서 권한을 얻을 수 있다고 착각하게 된다.
 */
export interface MembershipResponse {
  tenantId: number;
  /** 서브도메인 UX 용 슬러그. DDL 상 nullable 이라 없을 수 있다. */
  tenantSlug: string | null;
  tenantName: string;
  role: string;
}

/** `POST /auth/select-tenant` 요청 본문. */
export interface SelectTenantRequest {
  tenantId: number;
}
