package com.smartfirehub.dataset.storage;

/**
 * 데이터셋 행(row)이 실제로 저장되는 백엔드 종류(WD-72).
 *
 * <p>이번 단계에서는 모든 테넌트가 {@link #POSTGRESQL} 이다. TIBERO/MYSQL 은 이후 단계에서 구현이 붙을 자리를 미리 잡아 둔 식별자다.
 */
public enum DataStoreBackend {
  POSTGRESQL(10000, 255),
  /**
   * VARCHAR2 상한 4000 — {@code TiberoDialect.MAX_VARCHAR} 가 이 값을 가리킨다(WD-636). 길이 미지정 VARCHAR 도
   * 상한(4000)으로 만든다(WD-655).
   */
  TIBERO(4000, 4000),
  /** 아직 구현이 없어 PG 와 같은 상한·기본 길이를 둔다. */
  MYSQL(10000, 255);

  /** 이 백엔드의 VARCHAR 최대 길이(WD-636) — 저장소별 상한의 단일 출처. */
  private final int maxVarcharLength;

  /** 이 백엔드에서 길이 미지정 VARCHAR 가 실제로 갖는 길이(WD-655) — 저장소별 기본 길이의 단일 출처. */
  private final int defaultVarcharLength;

  DataStoreBackend(int maxVarcharLength, int defaultVarcharLength) {
    this.maxVarcharLength = maxVarcharLength;
    this.defaultVarcharLength = defaultVarcharLength;
  }

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

  /**
   * 이 백엔드의 VARCHAR 최대 길이(WD-636) — PG 10000, Tibero 4000. 상수를 여기 두고 {@code
   * ColumnPhysicalType.MAX_VARCHAR_LENGTH}·{@code TiberoDialect.MAX_VARCHAR} 가 이 값을 가리킨다(패키지 순환 없이
   * 단일 출처). 기능 응답으로 웹 폼에 내려 서버가 DDL 전에 거부하는 값과 폼 상한을 맞춘다.
   */
  public int maxVarcharLength() {
    return maxVarcharLength;
  }

  /**
   * 이 백엔드에서 길이 미지정 VARCHAR 의 길이(WD-655) — PG·MySQL {@code VARCHAR(255)}, Tibero {@code
   * VARCHAR2(4000 CHAR)}. DDL 조립과 필드 수정의 길이 변경 판정이 같은 값을 보도록 여기 둔다(예전에는 판정이 PG 기본값 255 만 봐 Tibero 를
   * 잘못 비교했다).
   */
  public int defaultVarcharLength() {
    return defaultVarcharLength;
  }

  /**
   * 저장된(또는 요청한) VARCHAR 길이가 이 백엔드에서 실제로 뜻하는 길이(WD-655) — 값이 없으면 {@link #defaultVarcharLength()}.
   *
   * @param maxLength 메타데이터·요청의 길이(null 이면 길이 미지정)
   */
  public int effectiveVarcharLength(Integer maxLength) {
    return maxLength != null ? maxLength : defaultVarcharLength;
  }
}
