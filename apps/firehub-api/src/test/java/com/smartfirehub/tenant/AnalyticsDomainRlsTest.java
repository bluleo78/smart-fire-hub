package com.smartfirehub.tenant;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V92 analytics 도메인 RLS 격리를 양방향으로 검증한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 붙이지 않는다 — 테넌트를 바꿔 가며 여러 트랜잭션을
 * 열어야 하고, 하나의 테스트 트랜잭션에 묶이면 GUC 가 처음 값으로 고정된다.
 */
class AnalyticsDomainRlsTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private PlatformTransactionManager transactionManager;

  private TransactionTemplate tx;
  private long tenantA;
  private long tenantB;
  private Long createdUserId;

  @BeforeEach
  void createTenants() {
    tx = new TransactionTemplate(transactionManager);
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "rls-anl-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "rls-anl-b");
    createdUserId = TenantRlsTestSupport.insertUser(dsl, "anluser");
  }

  @AfterEach
  void cleanup() {
    deleteOwnRows(tenantA);
    deleteOwnRows(tenantB);
    TenantRlsTestSupport.deleteUser(dsl, createdUserId);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantContext.clear();
  }

  @Test
  void savedQueryIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "saved_query", () -> insertSavedQuery("쿼리"));
  }

  @Test
  void dashboardIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx, dsl, tenantA, tenantB, "dashboard", () -> insertDashboard("대시보드"));
  }

  @Test
  void chartFollowsParentSavedQueryIsolation() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "chart",
        () -> insertChart(insertSavedQuery("차트부모")));
  }

  @Test
  void dashboardWidgetIsIsolated() {
    TenantRlsTestSupport.assertTwoSidedIsolation(
        tx,
        dsl,
        tenantA,
        tenantB,
        "dashboard_widget",
        () -> insertWidget(insertDashboard("위젯모함"), insertChart(insertSavedQuery("위젯쿼리"))));
  }

  // ── 픽스처 ────────────────────────────────────────────────────────────


  /** tenant_id 는 명시하지 않는다 — DEFAULT 가 GUC 에서 채우는 것을 함께 검증한다. */
  private Long insertSavedQuery(String namePrefix) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("saved_query")))
        .set(field(name("name"), String.class), namePrefix + "-" + suffix)
        .set(field(name("sql_text"), String.class), "SELECT 1")
        .set(field(name("is_shared"), Boolean.class), false)
        .set(field(name("created_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertDashboard(String namePrefix) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("dashboard")))
        .set(field(name("name"), String.class), namePrefix + "-" + suffix)
        .set(field(name("is_shared"), Boolean.class), false)
        .set(field(name("created_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertChart(Long savedQueryId) {
    long suffix = TenantRlsTestSupport.nextTenantId();
    return dsl.insertInto(table(name("chart")))
        .set(field(name("name"), String.class), "차트-" + suffix)
        .set(field(name("saved_query_id"), Long.class), savedQueryId)
        .set(field(name("chart_type"), String.class), "BAR")
        .set(field(name("config"), JSONB.class), JSONB.valueOf("{}"))
        .set(field(name("is_shared"), Boolean.class), false)
        .set(field(name("created_by"), Long.class), createdUserId)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  private Long insertWidget(Long dashboardId, Long chartId) {
    return dsl.insertInto(table(name("dashboard_widget")))
        .set(field(name("dashboard_id"), Long.class), dashboardId)
        .set(field(name("chart_id"), Long.class), chartId)
        .set(field(name("position_x"), Integer.class), 0)
        .set(field(name("position_y"), Integer.class), 0)
        .set(field(name("width"), Integer.class), 4)
        .set(field(name("height"), Integer.class), 4)
        .returning(field(name("id"), Long.class))
        .fetchOne()
        .get(field(name("id"), Long.class));
  }

  /** 자기 테넌트 행만 지운다(RLS 스코프). 자식 → 부모 순서. */
  private void deleteOwnRows(long tenantId) {
    TenantRlsTestSupport.runInTenantTransaction(
        tx,
        tenantId,
        () -> {
          dsl.deleteFrom(table(name("dashboard_widget"))).execute();
          dsl.deleteFrom(table(name("chart"))).execute();
          dsl.deleteFrom(table(name("dashboard"))).execute();
          dsl.deleteFrom(table(name("saved_query"))).execute();
        });
  }
}
