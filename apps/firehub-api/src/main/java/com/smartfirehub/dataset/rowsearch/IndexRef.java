package com.smartfirehub.dataset.rowsearch;

/**
 * 행 검색 색인 하나를 가리키는 참조. 테넌트를 타입에 실어 색인 엔진이 테넌트 없이 호출되지 않게 한다.
 *
 * @param sourceTable 원본 데이터 테이블 이름(스키마 없이, dataset.table_name)
 */
public record IndexRef(long tenantId, long datasetId, String sourceTable) {

  /** 색인 테이블 이름 접두사. 사용자 테이블 이름 예약(DataTableService)과 사용자 SQL 차단(SqlValidator)도 이 값을 쓴다. */
  public static final String TABLE_PREFIX = "fh_search_";

  /** 색인 테이블 이름. 데이터셋 id 기반이라 원본 이름 길이(63바이트 한도)와 무관하고 이름 변경에도 안정적이다. */
  public String indexTable() {
    return TABLE_PREFIX + datasetId;
  }

  /** swap 재구축 중 벡터 재사용용으로 보관하는 이전 색인 테이블 이름. */
  public String prevTable() {
    return indexTable() + "_prev";
  }
}
