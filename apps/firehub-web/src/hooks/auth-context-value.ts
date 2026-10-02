import { createContext } from 'react';

import type { LoginFormData, SignupFormData } from '../lib/validations/auth';
import type { UserResponse } from '../types/auth';
import type { RoleResponse } from '../types/role';
import type { MembershipResponse } from '../types/tenant';

export interface AuthContextValue {
  user: UserResponse | null;
  roles: RoleResponse[];
  isLoading: boolean;
  isAuthenticated: boolean;
  login: (data: LoginFormData) => Promise<void>;
  signup: (data: SignupFormData) => Promise<void>;
  logout: () => Promise<void>;
  hasRole: (roleName: string) => boolean;
  isAdmin: boolean;
  refreshUser: () => Promise<void>;
  /**
   * 현재 토큰이 가리키는 테넌트. `null` 이면 **미선택** — 워크스페이스 선택 화면으로 보내야 한다.
   * 표시 목적일 뿐이고 격리는 서버측 JWT + RLS 가 한다.
   */
  activeTenantId: number | null;
  /** 선택/전환 UI 가 그릴 목록. */
  tenantOptions: MembershipResponse[];
  /**
   * 실행 테넌트를 확정한다. 최초 선택과 전환이 같은 함수다.
   *
   * <p>성공하면 **되돌아오지 않는다** — 하드 리로드를 걸기 때문이다.
   */
  selectTenant: (tenantId: number) => Promise<void>;
  /** 임시 비밀번호 계정이 아직 비밀번호를 바꾸지 않았다 — ProtectedRoute 가 /change-password 로 보낸다(WD-2). */
  mustChangePassword: boolean;
  /** 비밀번호 변경 성공 후 호출: refresh 로 표식이 꺼진 토큰을 받고 사용자·역할을 다시 읽는다. */
  completePasswordChange: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
