/**
 * 행 삭제 API(POST /datasets/{id}/data/delete)가 한 번에 받는 최대 행 수.
 * 서버 DataDeleteRequest 의 rowIds 상한(1000)과 같은 값 — 화면이 미리 막아 원인 없는 400 을 피한다(WD-1087).
 */
export const MAX_DELETE_ROWS = 1000;
