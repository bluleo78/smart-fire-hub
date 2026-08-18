package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * P3-b2 T5 — {@code dataset.table_name} 유니크를 {@code (tenant_id, table_name)} 으로 접은
 * 뒤(V112), 카탈로그(dataset 테이블 INSERT)와 물리 DDL({@link DataTableService})이 이어지는
 * <b>실제 프로덕션 경로</b>가 안전한지 고정한다.
 *
 * <p><b>왜 {@link DataTableService#createTable} 을 직접 부르지 않고 {@link
 * DatasetService#createDataset} 을 쓰는가.</b> 처음 버전은 {@code DataTableService.createTable}
 * 만 직접 호출했는데, 그 메서드는 물리 DDL 만 하고 {@code dataset} 카탈로그 테이블을 전혀 건드리지
 * 않는다 — {@code idx_dataset_table_name} 유니크는 카탈로그 INSERT 에서만 걸린다. 그래서 그 버전의
 * {@code twoTenantsCanUseSameTableName} 은 V112 를 되돌려도(전역 유니크로 복원해도) 빨개지지
 * <b>않았다</b>(변이 테스트로 실측 확인, 아래 참조) — 카탈로그를 건드리지 않으니 유니크 위반이
 * 애초에 일어날 수 없었다. V110 사후분석이 설명한 실제 위험 경로(existsByTableName → save() →
 * DataTableService.createTable)를 그대로 재현하려면 {@link DatasetService#createDataset} 을 통해
 * 카탈로그 INSERT 까지 함께 실행해야 한다.
 *
 * <p><b>정리를 {@code finally} 가 아니라 {@code @AfterEach} 로 하는 이유(라운드 1 리뷰
 * BLOCKER).</b> 처음 버전은 각 테스트 메서드의 {@code try/finally} 안에서 직접
 * {@code cleanupAll} 을 불렀다 — 그런데 자바의 plain try/finally 의미론상 {@code finally} 가
 * 예외를 던지면 {@code try} 블록이 던진 예외를(그게 이 클래스에서 가장 중요한
 * {@code AssertionError} 라도) <b>완전히 대체한다</b>(억제 연결 없음). 두 테넌트가 우연히 같은
 * 물리 스키마로 수렴하는 회귀가 실제로 나면, {@link #recreatingOneTenantsTableLeavesTheOthersRowsIntact}
 * 의 정리 단계({@code dropSchemasCreatedByThisTest})가 두 테넌트 모두 같은 이름을 넘겨받아
 * R7 하드가드(같은 이름을 두 번 드롭하려는 시도 자체는 아니지만, 스키마명이 {@code data} 처럼
 * 비정상적인 값이 되는 회귀 형태에서 하드가드가 걸린다)에 걸릴 수 있고, 그러면 JUnit 이 보고
 * 하는 것은 "데이터가 사라졌다" 가 아니라 "정리 헬퍼가 거부했다" 가 된다 — 다음 사람이 정리
 * 헬퍼를 고치러 가는 잘못된 방향으로 유도된다. {@code @AfterEach} 는 JUnit 5 가 실패를
 * <b>주 예외(테스트 자신의 실패)를 유지한 채 정리 실패를 suppressed 로 붙이는</b> 방식으로
 * 처리하므로, 이 위험이 구조적으로 사라진다. {@code cleanupAll} 로 "모아서 알린다" 는 이
 * 밴드의 기존 규율과도 결이 같다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 두 테넌트를 오가며 각각 새 물리
 * 트랜잭션을 열어야 그 시점 GUC 가 실제로 전환된다({@code IntegrationTestBase} 참조).
 */
class DataTableServiceTenantUniqueTest extends IntegrationTestBase {

  private static final long TENANT_BASE = TenantRlsTestSupport.randomSchemaProvisioningTenantIdBase();

  private static final List<DatasetColumnRequest> COLUMNS =
      List.of(new DatasetColumnRequest("val", "Value", "TEXT", null, true, false, null));

  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;
  @Autowired private TransactionTemplate tx;

  @Autowired
  @Qualifier("schemaOwnerDataSource")
  private DataSource schemaOwnerDataSource;

  // 테스트 메서드가 채우고 @AfterEach 가 읽는 픽스처 상태. JUnit 5 기본 생명주기(PER_METHOD)라
  // 테스트마다 새 인스턴스가 만들어지므로 필드가 다음 테스트로 새지 않는다. 초기값 null 은
  // "아직 이 필드에 해당하는 자원을 안 만들었다" 를 뜻하고, @AfterEach 가 null 을 건너뛴다 —
  // 픽스처 생성 극초반에 실패해도 cleanup 이 null 인자로 헬퍼를 불러 NPE 로 더 시끄러워지지
  // 않게 한다.
  private Long tenantA;
  private Long tenantB;
  private String schemaA;
  private String schemaB;
  private Long userA;
  private Long userB;

  private DSLContext ownerDsl() {
    return DSL.using(schemaOwnerDataSource, SQLDialect.POSTGRES);
  }

  @AfterEach
  void cleanupFixture() {
    TenantRlsTestSupport.cleanupAll(
        // dataset 카탈로그 행을 먼저 지운다 — fk_dataset_tenant 에 ON DELETE CASCADE 가 없어
        // deleteTenants 가 이 행보다 먼저 tenant 를 지우려 하면 FK 위반으로 실패한다.
        () -> ifPresent(tenantA, this::deleteOwnDatasetRows),
        () -> ifPresent(tenantB, this::deleteOwnDatasetRows),
        // datasetService.createDataset 이 감사 로그를 남긴다 — audit_log_user_id_fkey /
        // fk_audit_log_tenant 에도 cascade 가 없어 user·tenant 삭제보다 먼저 지워야 한다.
        () -> ifPresent(tenantA, this::deleteOwnAuditLogRows),
        () -> ifPresent(tenantB, this::deleteOwnAuditLogRows),
        () -> {
          String[] schemas =
              Stream.of(schemaA, schemaB).filter(Objects::nonNull).toArray(String[]::new);
          if (schemas.length > 0) {
            TenantRlsTestSupport.dropSchemasCreatedByThisTest(ownerDsl(), schemas);
          }
        },
        () -> {
          Long[] tenantIds =
              Stream.of(tenantA, tenantB).filter(Objects::nonNull).toArray(Long[]::new);
          if (tenantIds.length > 0) {
            TenantRlsTestSupport.deleteTenants(dsl, tenantIds);
          }
        },
        () -> ifPresent(userA, id -> TenantRlsTestSupport.deleteUser(dsl, id)),
        () -> ifPresent(userB, id -> TenantRlsTestSupport.deleteUser(dsl, id)));
  }

  private static void ifPresent(Long value, java.util.function.LongConsumer action) {
    if (value != null) {
      action.accept(value);
    }
  }

  /**
   * 서로 다른 테넌트가 같은 {@code table_name} 을 쓸 수 있어야 한다 — 물리 스키마가 분리됐고
   * 카탈로그 유니크도 테넌트로 접혔으므로 어느 계층에서도 충돌하지 않는다. V112 접기 전에는
   * 카탈로그 INSERT 가 전역 유니크에 걸려 두 번째 테넌트가 23505 로 거부됐다.
   */
  @Test
  void twoTenantsCanUseSameTableName() {
    tenantA = TENANT_BASE + 1;
    tenantB = TENANT_BASE + 2;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantA);
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantB);
    schemaA = TenantContext.runScopedGet(tenantA, DataSchema::current);
    schemaB = TenantContext.runScopedGet(tenantB, DataSchema::current);
    userA = TenantRlsTestSupport.insertUser(dsl, "t5-unique-a");
    userB = TenantRlsTestSupport.insertUser(dsl, "t5-unique-b");

    TenantContext.runScopedGet(
        tenantA, () -> datasetService.createDataset(newRequest("공유이름-A", "shared_name"), userA));
    // V112 접기 전이면 여기서 카탈로그 유니크 위반(23505)으로 터진다.
    TenantContext.runScopedGet(
        tenantB, () -> datasetService.createDataset(newRequest("공유이름-B", "shared_name"), userB));

    assertThat(tableExists(schemaA, "shared_name")).isTrue();
    assertThat(tableExists(schemaB, "shared_name")).isTrue();
  }

  /**
   * 한쪽 테넌트의 재생성이 다른 테넌트의 <b>데이터</b>를 지우지 않는다 — V110 사후분석이 경고한
   * 정확히 그 데이터 손실 시나리오(existsByTableName → save() → DataTableService.createTable)를
   * 실제 서비스 경로로 고정한다.
   *
   * <p><b>이 밴드 전체에서 가장 중요한 테스트다.</b> "테이블이 존재한다" 가 아니라 <b>"행이
   * 그대로 있다"</b> 를 단언한다 — {@code createTable} 의 {@code DROP TABLE IF EXISTS} 는 테이블을
   * 지웠다가 같은 이름으로 다시 만들므로, 존재 여부만 보면 데이터가 날아가도 초록색이 된다.
   */
  @Test
  void recreatingOneTenantsTableLeavesTheOthersRowsIntact() {
    tenantA = TENANT_BASE + 3;
    tenantB = TENANT_BASE + 4;
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantA);
    TenantRlsTestSupport.insertActiveTenant(dsl, tenantB);
    schemaA = TenantContext.runScopedGet(tenantA, DataSchema::current);
    schemaB = TenantContext.runScopedGet(tenantB, DataSchema::current);
    userA = TenantRlsTestSupport.insertUser(dsl, "t5-intact-a");
    userB = TenantRlsTestSupport.insertUser(dsl, "t5-intact-b");

    TenantContext.runScopedGet(
        tenantA,
        () -> {
          datasetService.createDataset(newRequest("교체검증-A", "shared_name"), userA);
          insertRow("shared_name", "tenant-a-row"); // A 의 데이터
          return null;
        });

    // B 가 같은 이름의 데이터셋을 만든다 → DatasetService.createDataset 내부에서
    // DataTableService.createTable 의 DROP TABLE IF EXISTS 가 돈다.
    TenantContext.runScopedGet(
        tenantB, () -> datasetService.createDataset(newRequest("교체검증-B", "shared_name"), userB));

    // 핵심 단언: A 의 행이 살아 있다 — 두 테넌트가 물리적으로 다른 테이블
    // (data_t{a}."shared_name" 와 data_t{b}."shared_name")을 갖기 때문이다.
    List<String> aRows = TenantContext.runScopedGet(tenantA, () -> selectAllValues("shared_name"));
    assertThat(aRows).containsExactly("tenant-a-row");
    // 그리고 B 의 테이블은 비어 있다(A 것을 넘겨받은 게 아니다).
    assertThat(TenantContext.runScopedGet(tenantB, () -> selectAllValues("shared_name"))).isEmpty();
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /**
   * 이 테스트가 만든 {@code dataset} 카탈로그 행을 지운다(RLS 스코프라 해당 테넌트 행만
   * 보인다). {@code dataset_column} 은 {@code ON DELETE CASCADE} 로 함께 사라진다. {@code
   * deleteTenants} 보다 반드시 먼저 호출해야 한다 — {@code fk_dataset_tenant} 에는 cascade 가
   * 없다.
   *
   * <p><b>{@code WHERE tenant_id = ?} 를 명시하는 이유(라운드 1 리뷰 nit).</b> RLS 만으로도
   * 오늘은 확실히 안전하다(실측: {@code dataset} 은 {@code relrowsecurity=t}, 이 헬퍼가 쓰는
   * {@code dsl} 은 소유자 {@code app} 이 아니라 {@code app_tenant} 로 접속하고 그 롤은 {@code
   * rolbypassrls=f}다 — GUC 가 없으면 fail-closed 로 "아무것도 안 지운다" 방향이라 폭발
   * 반경이 닫혀 있다). 그런데 같은 support 클래스의 {@code deleteTenants} 는 {@code where
   * tenant_id = ?} 를 명시하고, 이 밴드는 정확히 "조건 없는 삭제가 위험하다"는 이유로 R7
   * 하드가드까지 만들었다 — WHERE 없는 DELETE 를 새로 심어 그 규율과 어긋나는 선례를 남기지
   * 않는다.
   */
  private void deleteOwnDatasetRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.deleteFrom(DSL.table(DSL.name("dataset")))
                .where(DSL.field(DSL.name("tenant_id"), Long.class).eq(tenantId))
                .execute());
  }

  /**
   * {@code datasetService.createDataset} 이 남긴 이 테스트 테넌트의 감사 로그를 지운다 —
   * {@code audit_log_user_id_fkey}/{@code fk_audit_log_tenant} 에 cascade 가 없어 남겨 두면
   * user·tenant 삭제가 FK 위반으로 실패한다({@code TriggerEventServiceTest} 의 같은 패턴
   * 참조). {@code WHERE tenant_id = ?} 를 명시하는 이유는 {@link #deleteOwnDatasetRows} 와
   * 같다.
   */
  private void deleteOwnAuditLogRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () ->
            dsl.deleteFrom(DSL.table(DSL.name("audit_log")))
                .where(DSL.field(DSL.name("tenant_id"), Long.class).eq(tenantId))
                .execute());
  }

  private static CreateDatasetRequest newRequest(String name, String tableName) {
    return new CreateDatasetRequest(
        name, tableName, null, null, "TABLE", "SOURCE", COLUMNS, null);
  }

  /** 현재 테넌트 스키마 안의 {@code tableName} 에 {@code val} 한 건을 넣는다. */
  private void insertRow(String tableName, String value) {
    dsl.execute("INSERT INTO " + DataSchema.qualify(tableName) + " (val) VALUES (?)", value);
  }

  /** 현재 테넌트 스키마 안의 {@code tableName} 의 {@code val} 값 전체를 id 순으로 돌려준다. */
  private List<String> selectAllValues(String tableName) {
    return dsl.fetch("SELECT val FROM " + DataSchema.qualify(tableName) + " ORDER BY id")
        .stream()
        .map(r -> r.get(0, String.class))
        .toList();
  }

  /** 지정한 스키마 안에 {@code tableName} 물리 테이블이 존재하는지(카탈로그 조회, 스키마 무관 접속). */
  private boolean tableExists(String schema, String tableName) {
    return dsl.fetchExists(
        dsl.selectOne()
            .from("information_schema.tables")
            .where(org.jooq.impl.DSL.field("table_schema", String.class).eq(schema))
            .and(org.jooq.impl.DSL.field("table_name", String.class).eq(tableName)));
  }
}
