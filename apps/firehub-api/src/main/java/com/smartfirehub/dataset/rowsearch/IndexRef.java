package com.smartfirehub.dataset.rowsearch;

/**
 * 행 검색 색인 하나를 가리키는 참조. 테넌트를 타입에 실어 색인 엔진이 테넌트 없이 호출되지 않게 한다.
 *
 * <p><b>세대(generation):</b> 원본 테이블을 교체(swap)하면 row_id 가 새로 매겨지므로 색인도 세대를 올려 새로 채운다(ADR 0002 결정 9,
 * WD-722). PG 색인은 테이블 이름({@code fh_search_{id}} / {@code _prev})이 세대 역할이라 이 값을 쓰지 않는다. Qdrant 색인은 한
 * 컬렉션 안에 옛 세대 점과 새 세대 점이 함께 있어, 쓰기·검색·재사용 판정에 이 값을 쓴다. 값은 {@code dataset_search_index.generation}
 * 에서 읽는다(1 부터).
 *
 * <p>{@link #ANY_GENERATION}(0)은 「세대와 상관없는 작업」 표시다 — 전체 삭제·벡터 비우기처럼 모든 세대를 건드리는 호출(데이터셋 삭제, 검색 끄기,
 * 정책 정리)이 상태 행을 읽지 않고 쓴다. Qdrant 구현은 세대가 필요한 쓰기·검색에 0 이 오면 거부한다.
 *
 * @param sourceTable 원본 데이터 테이블 이름(스키마 없이, dataset.table_name)
 * @param generation 색인 세대(1 부터). {@link #ANY_GENERATION} 이면 세대 무관 작업
 */
public record IndexRef(long tenantId, long datasetId, String sourceTable, long generation) {

  /** 색인 테이블 이름 접두사. 사용자 테이블 이름 예약(DataTableService)과 사용자 SQL 차단(SqlValidator)도 이 값을 쓴다. */
  public static final String TABLE_PREFIX = "fh_search_";

  /** 세대와 상관없는 작업(전체 삭제·벡터 비우기)의 표시. */
  public static final long ANY_GENERATION = 0L;

  /** 세대 무관 작업용 참조(전체 삭제·벡터 비우기). 쓰기·검색에는 상태 행의 세대를 실은 4인자 생성자를 쓴다. */
  public IndexRef(long tenantId, long datasetId, String sourceTable) {
    this(tenantId, datasetId, sourceTable, ANY_GENERATION);
  }

  /** 상태 행의 세대를 실은 참조(쓰기·검색용). */
  public static IndexRef of(long tenantId, long datasetId, String sourceTable, SearchIndexState s) {
    return new IndexRef(tenantId, datasetId, sourceTable, s.generation());
  }

  /** 색인 테이블 이름. 데이터셋 id 기반이라 원본 이름 길이(63바이트 한도)와 무관하고 이름 변경에도 안정적이다. */
  public String indexTable() {
    return TABLE_PREFIX + datasetId;
  }

  /** swap 재구축 중 벡터 재사용용으로 보관하는 이전 색인 테이블 이름(PG 색인 전용). */
  public String prevTable() {
    return indexTable() + "_prev";
  }

  /**
   * 세대가 필요한 작업(쓰기·검색·재사용)에서 세대가 실려 있는지 확인한다. 세대 무관 참조로 쓰면 Qdrant 에 세대 0 점이 생겨 swap 뒤 엉뚱한 행을 가리키게
   * 되므로 막는다.
   *
   * @return 세대
   * @throws IllegalStateException 세대 무관 참조일 때
   */
  public long requireGeneration() {
    if (generation <= ANY_GENERATION) {
      throw new IllegalStateException("행 검색 색인 세대가 없는 참조로 쓰기·검색할 수 없습니다: datasetId=" + datasetId);
    }
    return generation;
  }
}
