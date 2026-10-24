package com.smartfirehub.dataset.storage.tibero;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreContractTest;
import com.smartfirehub.dataset.storage.DataStorePasswordGenerator;
import com.smartfirehub.dataset.storage.DataTableStore;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.external.ExternalAccountProvisioner;
import com.smartfirehub.dataset.storage.external.ExternalStoreConnections;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Tibero 저장소에 대한 계약 테스트(WD-74) — 실서버가 있을 때만 돈다.
 *
 * <p>{@code TIBERO_TEST_HOST} 환경변수가 없으면 클래스째 건너뛰고, 환경변수만 있고 JDBC 드라이버 jar 가 없으면 {@link #prepare()}
 * 의 가정(assumption)으로 건너뛴다. pre-commit 훅은 둘 다 없이도 초록이어야 하기 때문이다. 실행은 {@code scripts/tibero-test.sh}.
 *
 * <p>{@link DataStoreContractTest} 가 {@code IntegrationTestBase} 를 상속하므로 같은 Spring 컨텍스트를 쓴다(새 컨텍스트를
 * 만들지 않는다). 테스트 테넌트의 Tibero 계정 12개는 클래스 시작 때 만들고 끝에 지우므로 {@code PER_CLASS} 생명주기를 쓴다 — 정적
 * {@code @BeforeAll} 에서는 스프링 빈을 쓸 수 없다.
 */
@EnabledIfEnvironmentVariable(named = "TIBERO_TEST_HOST", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TiberoDataStoreContractTest extends DataStoreContractTest {

  @Autowired private TiberoDataTableStore tableStore;
  @Autowired private TiberoDataRowStore rowStore;
  @Autowired private DSLContext dsl;
  @Autowired private TenantDataStoreRepository storeRepository;
  @Autowired private ExternalAccountProvisioner provisioner;
  @Autowired private ExternalStoreConnections connections;
  @Autowired private EncryptionService encryption;
  @Autowired private DataStorePasswordGenerator passwordGenerator;

  /** 클래스 시작 때 만든 전용 테넌트와 계정. 드라이버가 없어 건너뛰면 null 이다. */
  private TiberoTestEnvironment.Provisioned env;

  /** 드라이버가 있을 때만 전용 테넌트·계정을 준비한다. 없으면 가정 실패로 클래스 전체가 건너뛰어진다. */
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

  /** 계정·테넌트를 정리한다. 준비 전에 건너뛰었으면 아무것도 하지 않는다. */
  @AfterAll
  void cleanUpEnvironment() {
    if (env != null) {
      env.close();
      env = null;
    }
  }

  /**
   * 테스트 테넌트로 컨텍스트를 맞춘다. 부모의 {@code @BeforeEach}(기본 테넌트 세팅)가 먼저 실행되고(JUnit 은 상위 클래스의
   * {@code @BeforeEach} 를 먼저 호출한다) 이 메서드가 그 값을 덮어쓴다. 부모의 정리는 {@code @AfterEach} 로 컨텍스트를 비운다.
   */
  @BeforeEach
  void useTiberoTenant() {
    TenantContext.set(env.tenantId);
  }

  @Override
  protected DataTableStore tableStore() {
    return tableStore;
  }

  @Override
  protected DataRowStore rowStore() {
    return rowStore;
  }

  /** Tibero 식별자 한도(30자) 이하의 소문자 이름. */
  @Override
  protected String newTableName() {
    return "ct_" + (System.nanoTime() % 1_000_000_000_000L);
  }

  /** Tibero 는 빈 문자열을 NULL 로 저장한다. */
  @Override
  protected boolean emptyStringIsNull() {
    return true;
  }

  // ---------- 행 수 통계(WD-80) — Tibero 는 정확한 값 ----------

  /** 행 수는 정확한 COUNT(*) 이고, 없는 테이블은 빠진다(예외 없음). */
  @Test
  void rowCounts_missingTable_isOmitted() {
    String filled = createEmptyContractTable();
    insertContractRows(filled, 2);
    String empty = createEmptyContractTable();
    String missing = newTableName();

    assertThat(rowStore.rowCounts(List.of(filled, missing, empty)))
        .isEqualTo(Map.of(filled, 2L, empty, 0L));
  }

  /** 세 테이블 중 하나가 없으면 묶음 질의가 실패하고, 테이블별 재판정으로 빈 테이블만 남는다. */
  @Test
  void emptyTables_chunkFailure_fallsBackPerTable() {
    String empty = createEmptyContractTable();
    String filled = createEmptyContractTable();
    insertContractRows(filled, 1);
    String missing = newTableName();

    assertThat(rowStore.emptyTables(List.of(empty, filled, missing))).containsExactly(empty);
  }

  /** 이름 길이가 달라도(UNION ALL 문자 리터럴의 CHAR 채움) 묶음 질의가 원래 이름 그대로 돌려준다. 두 테이블 모두 있어 재판정 없이 묶음 경로다. */
  @Test
  void emptyTables_differentNameLengths_returnsExactNames() {
    String shortName = newTableName();
    String longName = shortName + "_long";
    try {
      for (String t : List.of(shortName, longName)) {
        tableStore.createTable(
            t,
            List.of(
                new com.smartfirehub.dataset.dto.DatasetColumnRequest(
                    "name", "Name", "TEXT", null, true, false, null)));
      }

      assertThat(rowStore.emptyTables(List.of(longName, shortName)))
          .containsExactlyInAnyOrder(shortName, longName);
    } finally {
      for (String t : List.of(shortName, longName)) {
        try {
          tableStore.dropTable(t);
        } catch (RuntimeException ignored) {
          // 만들기 전에 실패했을 수 있다 — 정리 실패는 무시한다.
        }
      }
    }
  }

  // ---------- 스테이징 _SEQ 인덱스(WD-77) ----------

  /**
   * 스테이징을 만들면 {@code _SEQ} 한 컬럼 인덱스({@code SX_<10hex>})가 함께 생기고, 스테이징을 지우면 테이블과 함께 사라진다 —
   * db-executor 적재의 청크별 {@code MAX("_SEQ")} 가 전체 스캔이 되지 않게 하는 인덱스다.
   */
  @Test
  void staging_hasSeqIndex_droppedWithTable() {
    String table = createEmptyContractTable();
    String staging = rowStore.createStagingTable(table, List.of("name", "qty"));
    String physical = TiberoNames.physical(staging);
    String index = TiberoNames.stagingSeqIndexName(physical);
    boolean dropped = false;
    try {
      assertThat(seqIndexColumns(index)).containsExactly(physical + "._SEQ");
      rowStore.dropStagingTable(staging);
      dropped = true;
      assertThat(seqIndexColumns(index)).isEmpty();
    } finally {
      if (!dropped) {
        rowStore.dropStagingTable(staging);
      }
    }
  }

  /** OWNER 세션에서 인덱스의 {테이블.컬럼} 목록(없으면 빈 목록). */
  private List<String> seqIndexColumns(String index) {
    return connections.withDdl(
        env.tenantId,
        conn -> {
          List<String> out = new java.util.ArrayList<>();
          try (var ps =
              conn.prepareStatement(
                  "SELECT TABLE_NAME, COLUMN_NAME FROM USER_IND_COLUMNS WHERE INDEX_NAME = ?"
                      + " ORDER BY COLUMN_POSITION")) {
            ps.setString(1, index);
            try (var rs = ps.executeQuery()) {
              while (rs.next()) {
                out.add(rs.getString(1) + "." + rs.getString(2));
              }
            }
          }
          return out;
        });
  }
}
