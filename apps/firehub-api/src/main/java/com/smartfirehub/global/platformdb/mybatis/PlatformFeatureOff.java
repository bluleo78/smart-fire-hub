package com.smartfirehub.global.platformdb.mybatis;

import com.smartfirehub.global.platformdb.PlatformDbFeature;

/**
 * 꺼진 기능 경로(벡터·트라이그램)의 MyBatis 구현이 던지는 예외를 한 곳에서 만든다(WD-707 스펙 D1).
 *
 * <p>무엇을: 서비스가 {@code PlatformDbFeatures} 가드 뒤에서만 부르는 리포지토리 메서드(벡터 테이블·트라이그램 검색)는 MyBatis 구현을 만들지
 * 않고 {@link UnsupportedOperationException} 을 던진다. 메시지에 기능 이름을 담는다.
 *
 * <p>왜 빈 결과가 아니라 예외인가: 빈 목록·0 을 돌려주면 서비스 가드가 빠진 회귀가 조용히 숨는다. PG 운영은 jOOQ 구현이라 pgvector·pg_trgm
 * MyBatis 판은 두지 않는다(PG 프로필 MyBatis 빈도 같은 예외). 묶음 ②(데이터셋)·⑦(문서 청크)이 같은 문구를 쓰도록 여기 둔다.
 */
public final class PlatformFeatureOff {

  private PlatformFeatureOff() {}

  /**
   * 꺼진 기능 메서드가 호출됐을 때 던질 예외.
   *
   * @param feature 그 메서드가 기대는 기능(VECTOR_SEARCH 또는 TRIGRAM_SEARCH)
   * @return 기능 이름을 담은 예외(호출자가 throw 한다)
   */
  public static UnsupportedOperationException unsupported(PlatformDbFeature feature) {
    return new UnsupportedOperationException(
        feature.name() + " 가 꺼진 플랫폼 DB 에서는 호출하지 않는다 — 서비스가 PlatformDbFeatures 로 건너뛴다");
  }
}
