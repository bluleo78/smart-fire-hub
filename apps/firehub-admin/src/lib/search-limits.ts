/**
 * 계정·사용자 검색(AccountListPage, OwnerPicker)이 서버와 맞추는 한도. 서버 하한(PlatformUserService)이 바뀌면
 * 여기 한 곳만 고친다.
 */

/** 서버가 강제하는 검색어 하한. 이보다 짧으면 400 이므로 호출조차 하지 않는다. */
export const MIN_QUERY_LENGTH = 2;

/** 서버가 돌려주는 검색 결과 상한 — 안내 문구가 이 값을 말한다. */
export const MAX_SEARCH_RESULTS = 20;
