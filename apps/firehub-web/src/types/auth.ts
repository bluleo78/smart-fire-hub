import type { MembershipResponse } from './tenant';

export interface SignupRequest {
  username: string;
  password: string;
  name: string;
  email?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  /**
   * 이 토큰에 담긴 활성 테넌트. `null` 이면 테넌트 미선택 상태로, `select-tenant` 를 호출하기
   * 전까지 일반 API 는 전부 403 이다(GUC 미설정 → RLS 0행 → 권한 0개).
   *
   * <p>선택(옵셔널)이 아니라 **필수** 필드로 둔다 — 옵셔널이면 이 값을 읽는 것을 잊은 경로를
   * 컴파일러가 짚어 주지 않고, 잊은 경로는 조용히 "테넌트 미선택인데 진입 성공" 으로 굴러간다.
   */
  activeTenantId: number | null;
  /**
   * 선택 가능한 테넌트 목록.
   *
   * <p>**경로마다 채워지는 정도가 다르다**(백엔드 `AuthService`): `login`·`refresh` 는 실제 목록을
   * 채우지만 `select-tenant` 는 빈 배열을 준다. 즉 전환 직후의 목록 출처는 응답이 아니라
   * 하드 리로드 후의 `refresh` 다 — `select-tenant` 응답의 이 필드는 읽지 않는다.
   */
  memberships: MembershipResponse[];
}

export interface UserResponse {
  id: number;
  username: string;
  email: string | null;
  name: string;
  isActive: boolean;
  createdAt: string;
}

export interface ErrorResponse {
  status: number;
  error: string;
  message: string;
  errors?: Record<string, string>;
}
