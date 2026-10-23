package com.smartfirehub.dashboard.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.dataset.storage.DataRowStore;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
import com.smartfirehub.global.config.TransactionTemplateConfig;
import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.pipeline.service.validator.SqlValidator;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 시스템 상태의 빈 데이터셋 판정과 트랜잭션 구간 검증(WD-80).
 *
 * <p>Tibero 테넌트의 빈 테이블 판정은 Tibero 질의라 PG 트랜잭션 밖에서, 데이터셋·감사 로그 조회(RLS 대상)는 readOnly 트랜잭션 안에서 해야 한다.
 * PG 는 기존 {@code @Transactional(readOnly = true)} 처럼 전부 readOnly 트랜잭션 하나 안이다. 클래스에 @Transactional 을
 * 붙이지 않고 실제 트랜잭션 매니저로 서비스를 손으로 만들어, mock 이 호출 시점의 트랜잭션 상태를 기록한다.
 *
 * <p>픽스처 데이터셋의 테이블 이름이 저장소에 넘어간 이름에 들어 있으면, 그 조회가 RLS GUC 가 주입된 트랜잭션 안에서 돌았다는 행위 증거다(GUC 가 없으면
 * dataset 이 0행이다). {@code AuthzPathTransactionTest} 가 예전에 어노테이션으로 고정하던 것을 이것이 대신한다.
 */
class DashboardServiceTiberoHealthTest extends IntegrationTestBase {

  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private DSLContext dsl;

  private Long userId;
  private String tableName;

  /** 물리 테이블이 없는 DOCUMENT 데이터셋의 table_name — 빈 테이블 판정에 넘어가면 안 된다. */
  private String documentTableName;

  @BeforeEach
  void createFixture() {
    userId = TenantRlsTestSupport.insertUser(dsl, "wd80_health_");
    tableName = "wd80_empty_" + System.nanoTime();
    documentTableName = "wd80_doc_" + System.nanoTime();
    inTenantFixture(
        () -> {
          dsl.insertInto(DATASET)
              .set(DATASET.NAME, "WD-80 빈 데이터셋 " + tableName)
              .set(DATASET.TABLE_NAME, tableName)
              .set(DATASET.STORAGE_TYPE, "TABLE")
              .set(DATASET.ORIGIN_TYPE, "SOURCE")
              .set(DATASET.CREATED_BY, userId)
              .execute();
          dsl.insertInto(DATASET)
              .set(DATASET.NAME, "WD-80 문서 데이터셋 " + documentTableName)
              .set(DATASET.TABLE_NAME, documentTableName)
              .set(DATASET.STORAGE_TYPE, "DOCUMENT")
              .set(DATASET.ORIGIN_TYPE, "SOURCE")
              .set(DATASET.CREATED_BY, userId)
              .execute();
        });
  }

  @AfterEach
  void deleteFixture() {
    inTenantFixture(
        () ->
            dsl.deleteFrom(DATASET)
                .where(DATASET.TABLE_NAME.in(tableName, documentTableName))
                .execute());
    TenantRlsTestSupport.deleteUser(dsl, userId);
  }

  /** 호출 시점 기록: 가시성 조건(조회 구간)의 tx·readOnly, 빈 테이블 판정의 tx, 판정에 넘어간 이름. */
  private record Recorded(
      DashboardService service,
      List<Boolean> readTx,
      List<Boolean> readOnly,
      List<Boolean> emptyCheckTx,
      List<Collection<String>> emptyCheckNames) {}

  private Recorded setup(DataStoreBackend backend) {
    List<Boolean> readTx = new ArrayList<>();
    List<Boolean> readOnly = new ArrayList<>();
    List<Boolean> emptyCheckTx = new ArrayList<>();
    List<Collection<String>> emptyCheckNames = new ArrayList<>();

    // 가시성 조건은 조회 구간에서 한 번 만들어진다 — 그 시점의 트랜잭션 상태가 곧 조회 구간의 상태다. 조건은 전부 보이게 둔다.
    DatasetAccessGuard guard = mock(DatasetAccessGuard.class);
    when(guard.visibleCondition(any(Clearance.class)))
        .thenAnswer(
            inv -> {
              readTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              readOnly.add(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
              return DSL.noCondition();
            });
    DataRowStore rowStore = mock(DataRowStore.class);
    when(rowStore.emptyTables(any()))
        .thenAnswer(
            inv -> {
              emptyCheckTx.add(TransactionSynchronizationManager.isActualTransactionActive());
              emptyCheckNames.add(List.copyOf(inv.<Collection<String>>getArgument(0)));
              return Set.of(tableName);
            });
    DataStoreResolver resolver = mock(DataStoreResolver.class);
    when(resolver.currentBackend()).thenReturn(backend);
    when(resolver.rowStore()).thenReturn(rowStore);

    // 인자 없는 형태(요청 경로)는 현재 사용자 자격을 쓴다.
    ClearanceResolver clearanceResolver = mock(ClearanceResolver.class);
    when(clearanceResolver.current()).thenReturn(mock(Clearance.class));

    // Tibero 판정은 PipelineDataSchema 가 한다(서비스 공통 판정 지점) — 같은 리졸버 mock 으로 실제 빈을 만든다.
    PipelineDataSchema pipelineDataSchema =
        new PipelineDataSchema(resolver, mock(SqlValidator.class));

    DashboardService service =
        new DashboardService(
            dsl,
            guard,
            clearanceResolver,
            resolver,
            pipelineDataSchema,
            TransactionTemplateConfig.readOnly(transactionManager));
    return new Recorded(service, readTx, readOnly, emptyCheckTx, emptyCheckNames);
  }

  /** Tibero: 조회는 readOnly 트랜잭션 안, 빈 테이블 판정은 트랜잭션 밖. 판정 결과 하나가 빈 데이터셋 수 1 이 된다. */
  @Test
  void tibero_emptyTablesOutsideTx_countsResult() {
    Recorded r = setup(DataStoreBackend.TIBERO);

    SystemHealthResponse health = r.service().getSystemHealth(mock(Clearance.class));

    assertThat(health.datasetHealth().empty()).isEqualTo(1);
    assertThat(r.readTx()).containsExactly(true);
    assertThat(r.readOnly()).containsExactly(true);
    assertThat(r.emptyCheckTx()).containsExactly(false);
    assertThat(r.emptyCheckNames()).hasSize(1);
    assertThat(r.emptyCheckNames().get(0)).contains(tableName);
  }

  /**
   * 물리 테이블이 없는 DOCUMENT/FILE 데이터셋은 빈 테이블 판정에 넘기지 않는다. Tibero 저장소는 없는 테이블을 판정 실패로 세고(불필요한 원격 질의), PG
   * 는 예전 pg_stat_user_tables 조인이 이 데이터셋을 이미 뺐다 — 두 백엔드 모두 표 데이터셋 이름만 넘어가야 한다.
   */
  @Test
  void tibero_documentDatasetNotPassedToEmptyTables() {
    Recorded r = setup(DataStoreBackend.TIBERO);

    r.service().getSystemHealth(mock(Clearance.class));

    assertThat(r.emptyCheckNames()).hasSize(1);
    assertThat(r.emptyCheckNames().get(0)).contains(tableName).doesNotContain(documentTableName);
  }

  /** PG: 기존처럼 조회와 빈 테이블 판정이 readOnly 트랜잭션 하나 안이다. */
  @Test
  void pg_emptyTablesInsideReadOnlyTx() {
    Recorded r = setup(DataStoreBackend.POSTGRESQL);

    SystemHealthResponse health = r.service().getSystemHealth(mock(Clearance.class));

    assertThat(health.datasetHealth().empty()).isEqualTo(1);
    assertThat(r.readTx()).containsExactly(true);
    assertThat(r.readOnly()).containsExactly(true);
    assertThat(r.emptyCheckTx()).containsExactly(true);
    assertThat(r.emptyCheckNames().get(0)).contains(tableName);
  }

  /** 인자 없는 형태(홈 대시보드 API)도 Tibero 판정은 트랜잭션 밖이다 — 자기 호출이 바깥 트랜잭션을 열어 두지 않는다. */
  @Test
  void tibero_noArgVariant_emptyTablesOutsideTx() {
    Recorded r = setup(DataStoreBackend.TIBERO);

    SystemHealthResponse health = r.service().getSystemHealth();

    assertThat(health.datasetHealth().empty()).isEqualTo(1);
    assertThat(r.readTx()).containsExactly(true);
    assertThat(r.emptyCheckTx()).containsExactly(false);
  }

  /** 인자 없는 형태의 PG: 자격 해석부터 판정까지 readOnly 트랜잭션 하나 안이다(예전 메서드 어노테이션과 같다). */
  @Test
  void pg_noArgVariant_insideReadOnlyTx() {
    Recorded r = setup(DataStoreBackend.POSTGRESQL);

    r.service().getSystemHealth();

    assertThat(r.readTx()).containsExactly(true);
    assertThat(r.readOnly()).containsExactly(true);
    assertThat(r.emptyCheckTx()).containsExactly(true);
  }
}
