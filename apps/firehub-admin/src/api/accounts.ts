import type {
  CreatePlatformAccountRequest,
  PageResponse,
  PlatformAccountQuery,
  PlatformAccountResponse,
} from '../types/platform';
import { client } from './client';

/** 운영자 콘솔 전역 계정 API(#784). 경로·동사는 테넌트 정지/활성화(`tenants.ts`)와 같은 형태다. */
export const accountsApi = {
  /**
   * 계정 목록(WD-47). q 없으면 전체(생성 최신순), 있으면 이메일·이름·아이디 부분일치 필터.
   * size 는 1~100(서버 400). 응답은 감사 로그와 같은 PageResponse.
   */
  list: (params: PlatformAccountQuery) =>
    client.get<PageResponse<PlatformAccountResponse>>('/accounts', { params }),
  /**
   * 201. 소속 없는 새 계정(WD-46) — username = 소문자 이메일, 첫 로그인 시 비밀번호 변경 강제.
   * 같은 아이디·이메일이 있으면 409 `ACCOUNT_ALREADY_EXISTS`(기존 계정은 그대로).
   */
  create: (body: CreatePlatformAccountRequest) => client.post<PlatformAccountResponse>('/accounts', body),
  /** 204. 로그인·세션 갱신·권한 필요 작업은 즉시 차단, 일부 화면은 access 만료(최대 30분)까지. */
  deactivate: (id: number) => client.post<void>(`/accounts/${id}/deactivate`),
  /** 204. 폐기된 세션은 되살아나지 않는다 — 사용자는 다시 로그인한다. */
  activate: (id: number) => client.post<void>(`/accounts/${id}/activate`),
};
