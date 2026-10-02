import type { PlatformAccountResponse } from '../types/platform';
import { client } from './client';

/** 운영자 콘솔 전역 계정 API(#784). 경로·동사는 테넌트 정지/활성화(`tenants.ts`)와 같은 형태다. */
export const accountsApi = {
  /** 이메일·이름·아이디 부분일치. 2자 미만이면 서버가 400 — 호출부가 먼저 거른다. 최대 20건. */
  search: (q: string) => client.get<PlatformAccountResponse[]>('/accounts', { params: { q } }),
  /** 204. 로그인·세션 갱신·권한 필요 작업은 즉시 차단, 일부 화면은 access 만료(최대 30분)까지. */
  deactivate: (id: number) => client.post<void>(`/accounts/${id}/deactivate`),
  /** 204. 폐기된 세션은 되살아나지 않는다 — 사용자는 다시 로그인한다. */
  activate: (id: number) => client.post<void>(`/accounts/${id}/activate`),
};
