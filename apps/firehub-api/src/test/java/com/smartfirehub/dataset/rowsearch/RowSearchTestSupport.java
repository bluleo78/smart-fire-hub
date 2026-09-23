package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import org.jooq.DSLContext;

/** 공유 test DB 잔여물 정리 — setUp 첫 줄에서 호출해 이전 실행의 tearDown 실패가 연쇄되지 않게 한다. */
final class RowSearchTestSupport {
  private RowSearchTestSupport() {}

  /**
   * 고정 이름 픽스처(원본 테이블·그 데이터셋 메타·색인 테이블)를 지운다. 테스트 사용자는 실행마다 고유한 이름으로
   * 만들므로({@code TenantRlsTestSupport.insertUser}) 여기서 지우지 않는다 — 각 테스트가 tearDown 에서 id 로 지운다.
   *
   * <p>RLS GUC 는 따로 set_config 하지 않는다 — jOOQ {@code dsl.transaction} 이 Spring
   * {@code SpringTransactionProvider} 를 거쳐 {@code TenantAwareTransactionManager.doBegin} 을 타므로,
   * {@link TenantContext#runScoped} 로 세운 테넌트가 트랜잭션 시작 시 GUC 로 주입된다.
   */
  static void cleanup(DSLContext dsl, DataTableService tables, String srcTable) {
    TenantContext.runScoped(
        1L,
        () ->
            dsl.transaction(
                cfg -> {
                  var tx = org.jooq.impl.DSL.using(cfg);
                  tx.fetch("SELECT id FROM dataset WHERE table_name = ?", srcTable)
                      .forEach(
                          r -> {
                            long id = r.get(0, Long.class);
                            dropIndexTablesIn(tx, id);
                            tx.execute("DELETE FROM dataset_column WHERE dataset_id = ?", id);
                            tx.execute("DELETE FROM dataset WHERE id = ?", id);
                          });
                  tx.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(srcTable + "_tmp"));
                  tx.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(srcTable));
                }));
  }

  /** 가짜 데이터셋 id 를 쓰는 테스트용 — dataset 행이 없으므로 색인 테이블을 id 로 직접 지운다. */
  static void dropIndexTables(DSLContext dsl, long datasetId) {
    TenantContext.runScoped(
        1L,
        () ->
            dsl.transaction(
                cfg -> {
                  dropIndexTablesIn(org.jooq.impl.DSL.using(cfg), datasetId);
                }));
  }

  /** 색인 테이블 이름은 정본({@link IndexRef#indexTable()}·{@link IndexRef#prevTable()})에서 얻는다. */
  private static void dropIndexTablesIn(DSLContext tx, long datasetId) {
    IndexRef ref = new IndexRef(1L, datasetId, "");
    tx.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.indexTable()));
    tx.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.prevTable()));
  }
}
