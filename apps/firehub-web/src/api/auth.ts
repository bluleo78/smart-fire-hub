import type { LoginRequest, SignupRequest, TokenResponse, UserResponse } from '../types/auth';
import type { MembershipResponse } from '../types/tenant';
import { client } from './client';

export const authApi = {
  signup: (data: SignupRequest) => client.post<UserResponse>('/auth/signup', data),
  login: (data: LoginRequest) => client.post<TokenResponse>('/auth/login', data),
  refresh: () => client.post<TokenResponse>('/auth/refresh'),
  logout: () => client.post<void>('/auth/logout'),
  me: () => client.get<UserResponse>('/auth/me'),
  /**
   * 전환 UI 용 테넌트 목록. 테넌트 미선택 토큰으로도 호출할 수 있는 두 엔드포인트 중 하나다.
   */
  memberships: () => client.get<MembershipResponse[]>('/auth/memberships'),
  /**
   * 실행 테넌트를 확정한다. **최초 선택과 전환이 같은 엔드포인트**다.
   *
   * <p>응답의 `memberships` 는 빈 배열이므로 호출부는 기존 목록을 유지해야 한다.
   * 접근할 수 없는 테넌트를 넘기면 401 이 아니라 **403** 이라, `client.ts` 의 refresh 인터셉터를
   * 타지 않고 그대로 에러가 올라온다.
   */
  selectTenant: (tenantId: number) =>
    client.post<TokenResponse>('/auth/select-tenant', { tenantId }),
};
