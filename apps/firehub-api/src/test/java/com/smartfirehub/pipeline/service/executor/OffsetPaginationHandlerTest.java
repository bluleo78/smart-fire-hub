package com.smartfirehub.pipeline.service.executor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * OffsetPaginationHandler.hasNextPage() 종료 조건 단위 테스트.
 *
 * <p>핵심 케이스:
 * - totalCount 제공 시 경계값 판단
 * - totalCount 미제공 시 부분·꽉찬·빈 페이지 판단
 * - 빈 페이지(0건)은 항상 종료 (refs #156)
 */
class OffsetPaginationHandlerTest {

  private OffsetPaginationHandler handler;

  @BeforeEach
  void setUp() {
    handler = new OffsetPaginationHandler();
  }

  // -------------------------------------------------------------------------
  // totalCount 제공 시 (known total)
  // -------------------------------------------------------------------------

  @Test
  void hasNextPage_withTotalCount_notLastPage_returnsTrue() {
    // offset=0, pageSize=50, totalCount=100 → 다음 페이지 있음
    assertTrue(handler.hasNextPage(0, 50, 100, 50));
  }

  @Test
  void hasNextPage_withTotalCount_lastPageExactBoundary_returnsFalse() {
    // offset=50, pageSize=50, totalCount=100 → 50+50 = 100, 다음 페이지 없음
    assertFalse(handler.hasNextPage(50, 50, 100, 50));
  }

  @Test
  void hasNextPage_withTotalCount_pastEnd_returnsFalse() {
    // offset=100 이미 totalCount=100 초과
    assertFalse(handler.hasNextPage(100, 50, 100, 0));
  }

  @Test
  void hasNextPage_withTotalCount_butCurrentPageEmpty_returnsFalse() {
    // totalCount가 있어도 0건이면 종료 (방어 조건, refs #156)
    assertFalse(handler.hasNextPage(0, 50, 200, 0));
  }

  // -------------------------------------------------------------------------
  // totalCount 미제공 시 (unknown total)
  // -------------------------------------------------------------------------

  @Test
  void hasNextPage_unknownTotal_fullPage_returnsTrue() {
    // pageSize=50, 50건 반환 → 다음 페이지 있을 수 있음
    assertTrue(handler.hasNextPage(0, 50, null, 50));
  }

  @Test
  void hasNextPage_unknownTotal_partialPage_returnsFalse() {
    // 30건(< pageSize=50) 반환 → 마지막 페이지로 판단
    assertFalse(handler.hasNextPage(0, 50, null, 30));
  }

  @Test
  void hasNextPage_unknownTotal_emptyPage_returnsFalse() {
    // 0건 반환 → 마지막 페이지로 판단 (refs #156: 명시적 빈 페이지 종료 조건)
    assertFalse(handler.hasNextPage(50, 50, null, 0));
  }

  @Test
  void hasNextPage_unknownTotal_firstPageEmpty_returnsFalse() {
    // 첫 페이지(offset=0)도 0건이면 종료
    assertFalse(handler.hasNextPage(0, 50, null, 0));
  }

  // -------------------------------------------------------------------------
  // MAX_PAGES 안전 캡
  // -------------------------------------------------------------------------

  @Test
  void hasNextPage_exceedsMaxPages_returnsFalse() {
    // MAX_PAGES=10_000, pageSize=100 → offset >= 1_000_000이면 종료
    assertFalse(handler.hasNextPage(1_000_000, 100, null, 100));
  }

  @Test
  void hasNextPage_atMaxPagesBoundary_returnsFalse() {
    // offset 정확히 MAX_PAGES * pageSize에 도달 시 종료
    assertFalse(handler.hasNextPage(10_000 * 100, 100, null, 100));
  }
}
