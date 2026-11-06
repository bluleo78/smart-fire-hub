package com.smartfirehub.global.platformdb;

/**
 * 설치본의 플랫폼 DB 종류(WD-496) — 설치 시점에 {@value #PROPERTY} 로 고른다. 기본은 {@link #POSTGRES}.
 *
 * <p>플랫폼 DB 는 앱 자신의 메타데이터 테이블(Flyway {@code db/migration*})이 있는 DB 다. 사용자 데이터를 두는 데이터 저장소와는 따로
 * 고른다(ADR 0001 결정 4). 종류별 구현 빈은 {@link ConditionalOnPostgresPlatformDb}·{@link
 * ConditionalOnTiberoPlatformDb} 로 갈린다.
 */
public enum PlatformDbType {
  /** PostgreSQL — 지금까지의 유일한 플랫폼 DB. 속성이 없으면 이것이다. */
  POSTGRES,
  /** Tibero — UTC JVM 필수, MyBatis 리포지토리, 벡터·키워드 검색 비활성(ADR 0001). */
  TIBERO;

  /** 플랫폼 DB 종류를 고르는 설정 키. 값은 {@code postgres} | {@code tibero}. */
  public static final String PROPERTY = "firehub.platform-db.type";
}
