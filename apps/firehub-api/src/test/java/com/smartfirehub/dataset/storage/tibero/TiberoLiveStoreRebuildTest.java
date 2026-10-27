package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.DataTableStore.RebuildBackupState;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tibero 저장소 수준 재생성 보관 실서버 끝-끝 테스트(WD-256) — 실행: {@code scripts/tibero-test.sh api}.
 *
 * <p>일회용 테넌트(예약 대역 id, 계정 12개)에서 <b>진짜 저장소 빈</b>({@link TiberoDataTableStore})으로 확인한다: (1) PG 트랜잭션
 * 안에서 보관 → 같은 이름·다른 열로 {@code createTable} → 롤백하면 보상 하나가 새 테이블을 지우고 옛 테이블(열·행·트리거)을 되돌린다 — {@code
 * createTable} 의 롤백 DROP 이 되살린 테이블을 지우지 않는다; (1b) 커밋된 재생성의 되돌리기는 메타 트랜잭션 커밋 뒤에만 물리 복원하고 롤백이면 보관본을
 * 남긴다; (2) 커밋 뒤 {@code dropRebuildBackup(oid)} 가 보관본만 지운다; (3) 다른 프로세스가 보관한 뒤 죽은 흔적을 맞춤이 복원하고 트리거를
 * 되살린다. 만든 테넌트·계정만 정리한다. 접속 정보는 출력하지 않는다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoLiveStoreRebuildTest extends IntegrationTestBase {

  private static final String T = "ptmp_9_live";
  private static final String FP = "d".repeat(32);

  @Autowired private TiberoDataTableStore tableStore;
  @Autowired private DSLContext dsl;
  @Autowired private TenantDataStoreRepository storeRepository;
  @Autowired private ExternalAccountProvisioner provisioner;
  @Autowired private ExternalStoreConnections connections;
  @Autowired private EncryptionService encryption;
  @Autowired private DataStorePasswordGenerator passwordGenerator;

  private TiberoTestEnvironment.Provisioned env;

  @BeforeAll
  void prepare() {
    TiberoTestEnvironment.assumeDriverPresent();
    env =
        TiberoTestEnvironment.provision(
            dsl,
            fixtureTransactionTemplate,
            storeRepository,
            provisioner,
            connections,
            encryption,
            passwordGenerator);
  }

  @AfterAll
  void cleanUp() {
    if (env != null) {
      // 테이블·시퀀스는 계정 DROP(CASCADE)과 함께 사라진다.
      env.close();
      env = null;
    }
    TenantContext.clear();
  }

  @BeforeEach
  void useTiberoTenant() {
    TenantContext.set(env.tenantId);
  }

  private String schema() {
    return connections.schemaOf(env.tenantId);
  }

  private static List<DatasetColumnRequest> oldColumns() {
    return List.of(new DatasetColumnRequest("name", "name", "TEXT", null, true, true, null, false));
  }

  private static List<DatasetColumnRequest> newColumns() {
    return List.of(
        new DatasetColumnRequest("amount", "amount", "INTEGER", null, true, false, null, false));
  }

  /** OWNER 세션에서 한 열 질의 결과. */
  private List<String> query(String sql) {
    return connections.withDdl(
        env.tenantId,
        c -> {
          List<String> out = new ArrayList<>();
          try (Statement st = c.createStatement();
              ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
              out.add(rs.getString(1));
            }
          }
          return out;
        });
  }

  private void exec(String sql) {
    connections.withDdl(
        env.tenantId,
        c -> {
          try (Statement st = c.createStatement()) {
            st.execute(sql);
          }
          return null;
        });
  }

  private List<String> columns(String physical) {
    return query(
        "SELECT LOWER(COLUMN_NAME) FROM USER_TAB_COLUMNS WHERE TABLE_NAME = '"
            + physical
            + "' ORDER BY COLUMN_ID");
  }

  private boolean exists(String physical) {
    return !query("SELECT 1 FROM USER_TABLES WHERE TABLE_NAME = '" + physical + "'").isEmpty();
  }

  private boolean triggerEnabled() {
    return !query(
            "SELECT 1 FROM USER_TRIGGERS WHERE STATUS = 'ENABLED' AND TRIGGER_NAME = '"
                + TiberoNames.triggerName(schema(), T)
                + "'")
        .isEmpty();
  }

  @Test
  void rollbackRestores_commitThenDropBackup_crashTraceReconciled() {
    String tp = TiberoNames.physical(T);
    String bp = TiberoNames.physical(TiberoNames.rebuildBackupTableName(schema(), T));
    tableStore.createTable(T, oldColumns());
    exec("INSERT INTO " + TiberoNames.table(schema(), T) + " (\"NAME\") VALUES ('row1')");

    // (1) 보관 + 새 테이블 생성 뒤 PG 트랜잭션 롤백 → 보상이 옛 테이블을 되돌린다.
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          tableStore.moveToRebuildBackup(T, "ignored", FP);
          tableStore.createTable(T, newColumns());
          assertThat(columns(tp)).contains("amount");
          status.setRollbackOnly();
        });
    assertThat(exists(bp)).as("보관본 없음").isFalse();
    assertThat(columns(tp)).contains("name").doesNotContain("amount");
    assertThat(query("SELECT COUNT(*) FROM " + TiberoNames.table(schema(), T)))
        .containsExactly("1");
    assertThat(triggerEnabled()).as("트리거 되살림").isTrue();
    exec("INSERT INTO " + TiberoNames.table(schema(), T) + " (\"NAME\") VALUES ('row2')");
    assertThat(query("SELECT COUNT(DISTINCT ID) FROM " + TiberoNames.table(schema(), T)))
        .as("트리거가 ID 를 채운다")
        .containsExactly("2");

    // (1b) 커밋된 재생성 뒤 스텝 실패 → 되돌리기는 메타 트랜잭션이 커밋된 뒤에만 물리 복원한다(리뷰 C1). 롤백이면 보관본을 그대로 둔다.
    long[] rebuilt = {0};
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          rebuilt[0] = tableStore.moveToRebuildBackup(T, "ignored", FP);
          tableStore.createTable(T, newColumns());
        });
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          tableStore.restoreRebuildBackup(T, "ignored", rebuilt[0]);
          assertThat(exists(bp)).as("커밋 전에는 물리 복원 없음").isTrue();
          status.setRollbackOnly();
        });
    assertThat(exists(bp)).as("메타 롤백 → 보관본 그대로").isTrue();
    assertThat(columns(tp)).contains("amount");
    fixtureTransactionTemplate.executeWithoutResult(
        status -> tableStore.restoreRebuildBackup(T, "ignored", rebuilt[0]));
    assertThat(exists(bp)).isFalse();
    assertThat(columns(tp)).contains("name").doesNotContain("amount");
    assertThat(query("SELECT COUNT(*) FROM " + TiberoNames.table(schema(), T)))
        .containsExactly("2");
    assertThat(triggerEnabled()).isTrue();

    // (2) 커밋된 재생성 → 스텝 성공 시 이 실행의 보관본만 지운다.
    long[] oid = {0};
    fixtureTransactionTemplate.executeWithoutResult(
        status -> {
          oid[0] = tableStore.moveToRebuildBackup(T, "ignored", FP);
          tableStore.createTable(T, newColumns());
        });
    assertThat(exists(bp)).isTrue();
    assertThat(tableStore.dropRebuildBackup(T, "ignored", oid[0])).isTrue();
    assertThat(exists(bp)).isFalse();
    assertThat(columns(tp)).contains("amount");

    // (3) 다른 프로세스가 보관한 뒤 죽은 흔적(지문 같음) → 맞춤이 복원하고 트리거를 되살린다.
    connections.withDdl(env.tenantId, c -> TiberoRebuildBackup.moveToBackup(c, schema(), T, FP));
    assertThat(exists(tp)).isFalse();
    assertThat(tableStore.reconcileRebuildBackup(T, FP)).isEqualTo(RebuildBackupState.RESTORED);
    assertThat(exists(bp)).isFalse();
    assertThat(columns(tp)).contains("amount");
    assertThat(triggerEnabled()).isTrue();
    assertThat(tableStore.reconcileRebuildBackup(T, FP)).isEqualTo(RebuildBackupState.NONE);
  }

  /** 한 객체의 마지막 DDL 시각(초 단위 문자열) — 두 번째 보장이 DDL 을 다시 실행하지 않았는지 보는 데 쓴다. */
  private String lastDdlTime(String objectName) {
    return query(
            "SELECT TO_CHAR(LAST_DDL_TIME, 'YYYYMMDDHH24MISS') FROM USER_OBJECTS WHERE OBJECT_NAME = '"
                + objectName
                + "'")
        .get(0);
  }

  /**
   * 옛 모양 테이블(ID 기본값 없음 + {@code BEFORE INSERT OR UPDATE} 트리거, 데이터 있음)을 저장소의 {@code
   * ensureRowTriggers} 로 올린다(WD-233 리뷰 I-2, 스펙 §8 「옛 모양 테이블 올리기 한 번」). 올린 뒤 ID 기본값이 현재 시퀀스를 가리키고,
   * 트리거가 UPDATE 전용이 되고, 사용자 INSERT 의 ID 가 이어서 채워지고, 사용자 UPDATE 가 갱신 시각을 옮겨야 한다. 두 번째 호출은 판정만 하고 DDL
   * 을 다시 실행하지 않는다 (테이블·트리거의 {@code LAST_DDL_TIME} 이 그대로).
   */
  @Test
  void legacyShapeTable_upgradedByEnsureRowTriggers_once() throws Exception {
    String t = "ptmp_9_legacy";
    String tbl = TiberoNames.table(schema(), t);
    String trigger = TiberoNames.triggerName(schema(), t);
    for (String sql : TiberoLiveTriggerTest.legacyCreateTable(schema(), t, oldColumns())) {
      exec(sql);
    }
    exec(TiberoLiveTriggerTest.legacyRowTrigger(schema(), t));
    exec("INSERT INTO " + tbl + " (\"NAME\") VALUES ('old1')");
    exec("INSERT INTO " + tbl + " (\"NAME\") VALUES ('old2')");
    assertThat(
            query(
                "SELECT TRIGGERING_EVENT FROM USER_TRIGGERS WHERE TRIGGER_NAME = '"
                    + trigger
                    + "'"))
        .containsExactly("INSERT OR UPDATE");

    tableStore.ensureRowTriggers(List.of(t));

    // 판정 두 개가 모두 참이어야 다음 쓰기부터 DDL 없이 지나간다.
    boolean defaultBound =
        connections.<Boolean>withDdl(
            env.tenantId, c -> TiberoCatalog.idDefaultUsesSequence(c, schema(), t));
    String event =
        connections.<String>withDdl(
            env.tenantId,
            c -> TiberoCatalog.enabledTriggerEvents(c, List.of(trigger)).get(trigger));
    assertThat(defaultBound).as("ID 기본값이 현재 시퀀스").isTrue();
    assertThat(TiberoCatalog.classify(event))
        .as("UPDATE 전용 트리거")
        .isEqualTo(TiberoCatalog.TriggerShape.CURRENT);
    assertThat(
            query(
                "SELECT TRIGGERING_EVENT FROM USER_TRIGGERS WHERE TRIGGER_NAME = '"
                    + trigger
                    + "'"))
        .containsExactly("UPDATE");

    // 사용자 INSERT: 트리거 대신 기본값이 ID 를 이어서 채운다(옛 행 1·2 다음 3).
    exec("INSERT INTO " + tbl + " (\"NAME\") VALUES ('new1')");
    assertThat(query("SELECT \"ID\" FROM " + tbl + " ORDER BY 1")).containsExactly("1", "2", "3");
    // 사용자 UPDATE: 옛 시각을 직접 넣어도 트리거가 현재 시각으로 덮는다.
    exec(
        "UPDATE "
            + tbl
            + " SET \"_UPDATED_AT\" = TIMESTAMP '2000-01-01 00:00:00' WHERE \"ID\" = 1");
    assertThat(query("SELECT TO_CHAR(\"_UPDATED_AT\", 'YYYY') FROM " + tbl + " WHERE \"ID\" = 1"))
        .allSatisfy(y -> assertThat(Integer.parseInt(y)).isGreaterThanOrEqualTo(2026));

    // 멱등: 두 번째 보장은 DDL 을 실행하지 않는다 — LAST_DDL_TIME 은 초 단위라 1초 넘게 기다린 뒤 비교한다.
    String tableDdl = lastDdlTime(TiberoNames.physical(t));
    String triggerDdl = lastDdlTime(trigger);
    Thread.sleep(1_200);
    tableStore.ensureRowTriggers(List.of(t));
    assertThat(lastDdlTime(TiberoNames.physical(t))).as("테이블 DDL 시각").isEqualTo(tableDdl);
    assertThat(lastDdlTime(trigger)).as("트리거 DDL 시각").isEqualTo(triggerDdl);
    System.out.println("PROBE wave5_legacy_upgrade_idempotent: SUPPORTED");
  }
}
