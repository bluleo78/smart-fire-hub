/**
 * 검색 입력이 서버와 맞추는 한도. 하한·결과 상한은 Owner 검색(OwnerPicker)만 쓰고(계정 화면은 WD-47 부터 페이지 목록), 길이 상한은 두 화면이 함께 쓴다.
 * 서버 한도(PlatformUserService·PlatformAccountService)가 바뀌면 여기 한 곳만 고친다.
 */

/** 서버가 강제하는 검색어 하한. 이보다 짧으면 400 이므로 호출조차 하지 않는다. */
export const MIN_QUERY_LENGTH = 2;

/** 서버가 돌려주는 검색 결과 상한 — 안내 문구가 이 값을 말한다. */
export const MAX_SEARCH_RESULTS = 20;

/**
 * 서버가 강제하는 검색어 길이 상한(MAX_QUERY_LENGTH). 넘기면 400 이 나 화면에 「불러오지 못함」만 떠 장애처럼 보였다 —
 * 입력란이 이 길이에서 막히게 해 서버로 보내지 않는다(WD-317).
 */
export const MAX_QUERY_LENGTH = 100;
