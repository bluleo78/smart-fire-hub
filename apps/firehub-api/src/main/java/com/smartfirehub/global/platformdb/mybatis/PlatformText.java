package com.smartfirehub.global.platformdb.mybatis;

import com.smartfirehub.global.util.LikePatternUtils;

/**
 * MyBatis 리포지토리가 문장에 넘기기 전 문자열을 정리하는 공용 헬퍼(WD-710).
 *
 * <p>왜 있는가: 분석(차트·저장 쿼리·대시보드)·리포트 템플릿 구현이 같은 「빈 값이면 null」 규칙을 각자 복사하고 있었다. jOOQ 판과 같은 판정 (공백뿐인 값도 빈
 * 값)을 한 곳에 둔다.
 */
public final class PlatformText {

  private PlatformText() {}

  /**
   * 검색어 → 대소문자 무시 부분 일치 패턴({@code PlatformSql.likeIgnoreCase} 의 {@code pattern} 파라미터). 비었으면(공백만 포함)
   * null — 문장이 검색 조건을 빼 거르지 않는다. 이스케이프는 jOOQ 판과 같은 {@link LikePatternUtils#containsPattern}.
   */
  public static String searchPattern(String search) {
    return search == null || search.isBlank() ? null : LikePatternUtils.containsPattern(search);
  }

  /** 빈 문자열(공백만 포함)을 null 로 — 갱신의 「비움」(WD-197·WD-135)과 비어 있으면 거르지 않는 필터가 함께 쓴다. */
  public static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
