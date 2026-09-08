/**
 * 목록 페이지 공용 페이지네이션 유틸리티.
 *
 * 검색/필터로 좁혀진 목록에서 특정 페이지의 항목을 전부 삭제하면, 삭제 후 재조회 시
 * 서버에는 더 이상 존재하지 않는 페이지 인덱스를 그대로 요청하게 되어 빈 결과가 온다.
 * 이때 UI는 "검색 결과 없음"으로 오표시하지만 실제로는 이전 페이지에 항목이 남아있다
 * (#549). 삭제 직전 현재 페이지에 남은 항목 수를 확인해, 마지막 남은 1건을 지우는
 * 경우라면 페이지를 한 칸 앞으로 보정한다.
 */
export function getPageAfterDelete(currentPageItemCount: number, page: number): number {
  // 현재 페이지가 첫 페이지(0)이면 보정할 필요 없음
  if (page <= 0) {
    return page;
  }
  // 현재 페이지에 삭제 대상 1건만 남아있었다면, 삭제 후 해당 페이지는 비게 되므로 이전 페이지로 이동
  if (currentPageItemCount <= 1) {
    return page - 1;
  }
  return page;
}
