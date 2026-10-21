package com.smartfirehub.dataset.service;

import com.smartfirehub.pipeline.service.validator.SqlValidator;

/**
 * SQL 본문이 특정 데이터셋 테이블을 실제로 참조하는지 판정한다(WD-143 — 데이터셋 삭제 전 "쓰는 곳" 집계).
 *
 * <p>화면 쿼리 편집기로 저장한 쿼리는 {@code saved_query.dataset_id} 가 비어 있고, 파이프라인 SQL 스텝도 선언 입력 없이 본문으로만 테이블을
 * 읽을 수 있다. FK 만 보면 이런 곳이 빠져 삭제 확인 창이 "쓰는 곳 없음"으로 보였다. 그래서 SQL 을 파싱해 참조 테이블을 확정한다.
 *
 * <p>해석 규칙은 SQL 관문({@code DatasetAccessGuard#judgeSql})과 같다 — 같은 {@link
 * SqlValidator#forAdhocDataSchemaQueries()} 파서의 {@link SqlValidator#referencedTables}(CTE 이름 제외,
 * 문자열 리터럴·주석 안의 이름은 테이블 노드가 아님, PG 식별자 폴딩 끝난 이름)를 쓰고, 스키마는 "없음(검색 경로상 현재 테넌트 데이터 스키마) 또는 현재 테넌트 데이터
 * 스키마"만 이 데이터셋으로 본다. 다른 테넌트는 데이터 스키마가 따로라(DataSchema) 같은 테이블 이름이어도 걸리지 않는다.
 */
final class DatasetSqlReferenceMatcher {

  /** 관문과 같은 파서 설정. 상태가 없는 검증기라 공유해도 안전하다(관문도 단일 인스턴스를 공유한다). */
  private static final SqlValidator SQL_PARSER = SqlValidator.forAdhocDataSchemaQueries();

  private DatasetSqlReferenceMatcher() {}

  /**
   * SQL 이 {@code dataSchema.tableName} 을 읽거나 쓰는가.
   *
   * <p>파싱에 실패한 SQL(문법 오류·다중 문장·허용되지 않은 문장·유니코드 이스케이프 식별자 등)은 {@code false} — 조용히 건너뛴다. 이 집계는 삭제를 막는
   * 판정이 아니라 사용자에게 알리는 안내이고, 실행할 수 없는 SQL 은 지워도 더 망가질 것이 없다. 예외를 올리면 SQL 한 건 때문에 확인 창 전체가 "참조 정보를
   * 불러오지 못했습니다"로 떨어진다.
   *
   * @param sql 판정할 SQL(파이프라인 스텝은 {{#N}}·{{last_run_at}} 치환본)
   * @param dataSchema 현재 테넌트 데이터 스키마({@code DataSchema.current()})
   * @param tableName 데이터셋의 물리 테이블 이름(dataset.table_name — 소문자 식별자)
   */
  static boolean references(String sql, String dataSchema, String tableName) {
    if (sql == null || sql.isBlank()) {
      return false;
    }
    SqlValidator.ReferencedTables refs;
    try {
      refs = SQL_PARSER.referencedTables(sql.strip());
    } catch (RuntimeException e) {
      // 파서는 UnsafeSqlException·SqlQueryException 외에 JSqlParser 내부 런타임 예외도 낼 수 있다 — 어느 쪽이든 "판정 불가"라
      // 건너뛴다.
      return false;
    }
    return refs.reads().stream().anyMatch(t -> matches(t, dataSchema, tableName))
        || refs.writes().stream().anyMatch(t -> matches(t, dataSchema, tableName));
  }

  /** 이름은 바이트 비교 — 파서가 PG 폴딩을 끝낸 값이고, 인용된 대문자 이름("Sales")은 PG 에서 다른 테이블이다. */
  private static boolean matches(SqlValidator.TableName t, String dataSchema, String tableName) {
    return t.name().equals(tableName) && (t.schema() == null || t.schema().equals(dataSchema));
  }
}
