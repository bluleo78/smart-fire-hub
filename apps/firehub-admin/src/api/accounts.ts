import type {
  CreatePlatformAccountRequest,
  PageResponse,
  PlatformAccountQuery,
  PlatformAccountResponse,
} from '../types/platform';
import { client } from './client';

/**
 * 계정 목록 쿼리 키의 머리(WD-773). 계정 화면·멤버 추가 사전 확인이 이 접두 아래에 두고, 계정을 바꾸는 곳(생성·비활성화·멤버 추가)이
 * 이 접두로 무효화한다 — 한쪽만 고치면 낡은 캐시로 안내가 조용히 어긋나므로 한 곳에 둔다(`PLATFORM_USER_SEARCH_KEY` 와 같은 판단).
 */
export const PLATFORM_ACCOUNTS_KEY = 'platform-accounts';

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
   * 같은 아이디(= 소문자 이메일)가 있으면 409 `ACCOUNT_ALREADY_EXISTS`(기존 계정은 그대로). 다른 계정의 프로필 이메일과 같은 것은 막지 않는다(WD-271).
   */
  create: (body: CreatePlatformAccountRequest) => client.post<PlatformAccountResponse>('/accounts', body),
  /** 204. 로그인·세션 갱신·권한 필요 작업은 즉시 차단, 일부 화면은 access 만료(최대 30분)까지. */
  deactivate: (id: number) => client.post<void>(`/accounts/${id}/deactivate`),
  /** 204. 폐기된 세션은 되살아나지 않는다 — 사용자는 다시 로그인한다. */
  activate: (id: number) => client.post<void>(`/accounts/${id}/activate`),
};
