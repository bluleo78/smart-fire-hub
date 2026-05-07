package com.smartfirehub.pipeline.service.executor;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OffsetPaginationHandler {

  /** Safety cap: stop after fetching this many pages regardless of totalCount. */
  private static final int MAX_PAGES = 10_000;

  /**
   * Decides whether there is a next page to fetch.
   *
   * @param currentOffset the offset used for the page just fetched
   * @param pageSize configured page size
   * @param totalCount total row count from API, or null if not available
   * @param currentPageRows number of rows returned in the latest page
   * @return true if another page should be fetched
   */
  public boolean hasNextPage(
      int currentOffset, int pageSize, Integer totalCount, int currentPageRows) {
    // Safety: never fetch beyond MAX_PAGES pages
    if (currentOffset >= (long) MAX_PAGES * pageSize) {
      return false;
    }

    // 빈 페이지(0건)이면 totalCount 유무와 무관하게 즉시 종료
    // 외부 API 중 totalCount를 제공하지 않는 경우, 마지막 페이지가 pageSize와 정확히 일치할 때
    // 다음 페이지를 한 번 더 요청하게 되는데, 그 응답이 0건이면 여기서 멈춘다.
    if (currentPageRows == 0) {
      return false;
    }

    if (totalCount != null) {
      return currentOffset + pageSize < totalCount;
    }

    // totalCount 미제공 시: 부분 페이지(currentPageRows < pageSize)이면 마지막 페이지로 판단.
    // 단, 마지막 페이지가 정확히 pageSize건인 경우 다음 페이지를 한 번 더 요청하게 되며,
    // 그 응답이 0건이면 위의 currentPageRows == 0 조건으로 종료된다.
    // 이 추가 요청을 완전히 제거하려면 totalCount를 API에서 제공받아야 한다.
    return currentPageRows >= pageSize;
  }

  /** Returns the offset to use for the next page. */
  public int getNextOffset(int currentOffset, int pageSize) {
    return currentOffset + pageSize;
  }

  /**
   * Builds the query-parameter map for a paginated request.
   *
   * @param offsetParam query param name for the offset (e.g. "offset")
   * @param limitParam query param name for the page size (e.g. "limit")
   * @param offset current offset value
   * @param pageSize page size value
   * @return map with pagination query parameters
   */
  public Map<String, String> buildPaginationParams(
      String offsetParam, String limitParam, int offset, int pageSize) {

    Map<String, String> params = new LinkedHashMap<>();
    if (offsetParam != null && !offsetParam.isBlank()) {
      params.put(offsetParam, String.valueOf(offset));
    }
    if (limitParam != null && !limitParam.isBlank()) {
      params.put(limitParam, String.valueOf(pageSize));
    }
    return params;
  }
}
