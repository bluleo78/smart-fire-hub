package com.smartfirehub.support;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PYTHON 읽기 슬롯(WD-29) 통합 테스트 공용 픽스처 — 기본 테넌트(1)에 "등급을 직접 지정한 물리 테이블"을 만들고, 슬롯 롤로 실제 로그인해 읽어 본다. 슬롯
 * 동기화·리스너·로컬 실행 테스트가 같은 출발 상태와 같은 판정(성공 null / 실패 SQLState)을 쓰도록 한 곳에 둔다.
 */
public final class PythonReadTestTables {

  private static final long TENANT = IntegrationTestBase.DEFAULT_TEST_TENANT_ID;

  private final DatasetService datasetService;
  private final DSLContext dsl;
  private final TransactionTemplate tx;
  private final SecurityFixture fx;

  public PythonReadTestTables(
      DatasetService datasetService, DSLContext dsl, TransactionTemplate tx, SecurityFixture fx) {
    this.datasetService = datasetService;
    this.dsl = dsl;
    this.tx = tx;
    this.fx = fx;
  }

  /**
   * TEXT 컬럼 {@code v} 하나인 데이터셋 {@code tableName} 을 만들고, 등급을 {@code level} 로 <b>직접</b>(이벤트·동기화 없이)
   * 바꾼 뒤 행 1개를 넣는다. 생성 훅은 기본 등급 기준 GRANT 를 이미 걸었으므로, 지정 등급과 슬롯 ACL 이 어긋난 상태일 수 있다.
   *
   * @return 데이터셋 id(정리용)
   */
  public long create(String tableName, String level, long owner) {
    long id =
        datasetService
            .createDataset(
                new CreateDatasetRequest(
                    tableName,
                    tableName,
                    null,
                    null,
                    "TABLE",
                    "SOURCE",
                    List.of(
                        new DatasetColumnRequest("v", "v", "TEXT", null, true, false, null, false)),
                    null),
                owner)
            .id();
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        TENANT,
        () -> {
          dsl.update(DATASET)
              .set(DATASET.SECURITY_LEVEL_ID, fx.levelId(level))
              .where(DATASET.ID.eq(id))
              .execute();
          dsl.execute("INSERT INTO " + DataSchema.qualify(tableName) + " (v) VALUES ('row')");
        });
    return id;
  }

  /** 슬롯 롤로 실제 로그인해 SELECT 한다. 권한 오류면 SQLState 를 돌려준다(성공이면 null). */
  public static String selectAs(int slot, String table, String secret) {
    String role = TenantPipelineRole.pythonReadRoleName(TENANT, slot);
    String pw = TenantPipelineRole.pythonReadPassword(TENANT, slot, secret);
    try (Connection c =
            DriverManager.getConnection(PostgresTestContainer.INSTANCE.getJdbcUrl(), role, pw);
        Statement s = c.createStatement()) {
      s.executeQuery("SELECT v FROM " + DataSchema.forTenant(TENANT) + ".\"" + table + "\"");
      return null;
    } catch (SQLException e) {
      return e.getSQLState();
    }
  }
}
