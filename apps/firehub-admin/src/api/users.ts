import type { PlatformUserResponse } from '../types/platform';
import { client } from './client';

export const usersApi = {
  /**
   * 이메일/이름 부분일치 검색. 서버가 최소 길이(2자)와 결과 상한(20건)을 강제한다 —
   * 2자 미만이면 400 이므로 호출부가 먼저 걸러야 한다.
   */
  search: (q: string) => client.get<PlatformUserResponse[]>('/users', { params: { q } }),
};
