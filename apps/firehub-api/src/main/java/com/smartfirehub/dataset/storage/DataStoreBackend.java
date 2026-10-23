package com.smartfirehub.dataset.storage;

/**
 * 데이터셋 행(row)이 실제로 저장되는 백엔드 종류(WD-72).
 *
 * <p>이번 단계에서는 모든 테넌트가 {@link #POSTGRESQL} 이다. TIBERO/MYSQL 은 이후 단계에서 구현이 붙을 자리를 미리 잡아 둔 식별자다.
 */
public enum DataStoreBackend {
  POSTGRESQL,
  TIBERO,
  MYSQL;

  /**
   * ai-agent 에 넘기는 SQL 방언 문자열(WD-79) — 에이전트는 "TIBERO" 와 "POSTGRESQL" 두 값만 안다. Tibero 만 Tibero 방언이고,
   * 그 밖의 백엔드(아직 구현이 없는 MySQL 포함)는 기존 PG 지침을 받는다. 채팅·프로액티브 요청이 같은 규칙을 쓰도록 한 곳에 둔다.
   */
  public static String dialectOf(DataStoreBackend backend) {
    return backend == TIBERO ? "TIBERO" : "POSTGRESQL";
  }

  /**
   * 행 검색(pgvector 색인) 지원 여부(WD-80). 색인이 PostgreSQL 전용이라 PG 만 true 다. 관문·기능 응답·배경 스윕이 같은 판정을 쓰도록 한
   * 곳에 둔다.
   */
  public boolean supportsRowSearch() {
    return this == POSTGRESQL;
  }

  /** GEOMETRY 컬럼 지원 여부(WD-80). PostGIS 가 있는 PostgreSQL 만 true 다. */
  public boolean supportsGeometry() {
    return this == POSTGRESQL;
  }
}
