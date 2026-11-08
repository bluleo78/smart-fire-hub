package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Objects;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-616 — 같은 분석 자산(대시보드·차트·저장 쿼리)을 관리자가 동시에 여러 번 지우면 모두 204 가 되고 「관리자 삭제」 감사가 요청 수만큼 남던 문제(WD-584
 * 후속).
 *
 * <p>존재 확인 뒤 지우는 흐름이라, 먼저 지운 쪽이 커밋하기 전에 확인을 통과한 요청은 DELETE 가 0 행이어도 성공으로 끝났다. 진 쪽은 순차 두 번째 삭제와 같은
 * 404(종류별 NotFound)여야 하고 감사는 실제로 지운 1건만 남아야 한다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 각 요청이 실제처럼 자기 트랜잭션으로 커밋해야 서로를 기다린다.
 */
class AnalyticsAdminConcurrentDeleteTest extends IntegrationTestBase {

  @Autowired private AnalyticsAdminService adminService;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long admin;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd616_owner_");
    admin = TenantRlsTestSupport.insertUser(dsl, "wd616_admin_");
  }

  @AfterEach
  void tearDown() {
    // 대시보드를 지우면 위젯이, 쿼리를 지우면 차트가 CASCADE 로 함께 사라진다(테스트가 실패해 남은 행 정리).
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM dashboard WHERE created_by = ?", owner);
          dsl.execute("DELETE FROM chart WHERE created_by = ?", owner);
          dsl.execute("DELETE FROM saved_query WHERE created_by = ?", owner);
          dsl.execute("DELETE FROM audit_log WHERE user_id = ?", admin);
        });
    TenantRlsTestSupport.deleteUser(dsl, owner);
    TenantRlsTestSupport.deleteUser(dsl, admin);
  }

  /** 종류별 자산 하나를 작성자 소유로 심는다(차트는 자기 저장 쿼리를 함께 만든다). */
  private Long create(AnalyticsAssetKind kind) {
    return inTenantFixture(
        () -> {
          Long queryId =
              dsl.fetchOne(
                      "INSERT INTO saved_query (name, sql_text, is_shared, created_by)"
                          + " VALUES ('wd616-q', 'SELECT 1', true, ?) RETURNING id",
                      owner)
                  .get(0, Long.class);
          return switch (kind) {
            case QUERY -> queryId;
            case CHART ->
                dsl.fetchOne(
                        "INSERT INTO chart (name, saved_query_id, chart_type, is_shared, created_by)"
                            + " VALUES ('wd616-c', ?, 'BAR', true, ?) RETURNING id",
                        queryId,
                        owner)
                    .get(0, Long.class);
            case DASHBOARD ->
                dsl.fetchOne(
                        "INSERT INTO dashboard (name, is_shared, created_by)"
                            + " VALUES ('wd616-d', true, ?) RETURNING id",
                        owner)
                    .get(0, Long.class);
          };
        });
  }

  private int deleteAudits(AnalyticsAssetKind kind, Long id) {
    return audits(kind, kind.deleteAction(), id);
  }

  /** 이 자산에 남은 특정 감사 행동의 감사 행 수. */
  private int audits(AnalyticsAssetKind kind, String action, Long id) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM audit_log WHERE action_type = ? AND resource = ?"
                        + " AND resource_id = ?",
                    action,
                    kind.table(),
                    String.valueOf(id))
                .get(0, Integer.class));
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  void 같은_자산을_관리자가_6건_동시에_지우면_1건만_성공하고_나머지는_404다(AnalyticsAssetKind kind) throws Exception {
    Long id = create(kind);
    Class<? extends Throwable> notFound = kind.notFound().getClass();

    List<Throwable> results =
        ConcurrentCalls.run(6, DEFAULT_TEST_TENANT_ID, () -> adminService.delete(kind, id, admin));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(5)
        .allMatch(notFound::isInstance);
    assertThat(deleteAudits(kind, id)).isEqualTo(1);
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  void 지운_트랜잭션이_커밋되기_전_같은_관리자_삭제는_기다렸다가_404이고_감사는_1건이다(AnalyticsAssetKind kind) throws Exception {
    Long id = create(kind);

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> adminService.delete(kind, id, admin),
            () -> {
              adminService.delete(kind, id, admin);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(kind.notFound().getClass());
    assertThat(deleteAudits(kind, id)).isEqualTo(1);
  }

  /**
   * WD-616 — 공유 해제도 같은 경합을 가진다. 존재 확인 뒤 UPDATE 하는 흐름이라, 먼저 지운 쪽이 커밋하기 전에 확인을 통과한 공유 해제는 UPDATE 가 0
   * 행이어도 200 이고 이미 사라진 자산에 「관리자 공유 해제」 감사를 남겼다. 순차라면 404 이므로 경합에서도 404·감사 없음이어야 한다.
   */
  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  void 지운_트랜잭션이_커밋되기_전_관리자_공유_해제는_기다렸다가_404이고_공유_해제_감사는_없다(AnalyticsAssetKind kind)
      throws Exception {
    Long id = create(kind);

    PausedTransactionRace.Outcome<Void> out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () -> adminService.delete(kind, id, admin),
            () -> {
              adminService.unshare(kind, id, admin);
              return null;
            });

    assertThat(out.secondBlocked()).isTrue();
    assertThat(out.secondError()).isInstanceOf(kind.notFound().getClass());
    assertThat(audits(kind, kind.unshareAction(), id)).isZero();
    assertThat(deleteAudits(kind, id)).isEqualTo(1);
  }

  /** 이미 비공개인 자산의 공유 해제는 여전히 성공(멱등)이고 감사를 남긴다 — 일치 행 판정이 이를 404 로 바꾸면 안 된다(WD-616). */
  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  void 이미_비공개인_자산을_다시_공유_해제해도_성공한다(AnalyticsAssetKind kind) {
    Long id = create(kind);

    adminService.unshare(kind, id, admin);
    adminService.unshare(kind, id, admin);

    assertThat(audits(kind, kind.unshareAction(), id)).isEqualTo(2);
  }

  @ParameterizedTest
  @EnumSource(AnalyticsAssetKind.class)
  void 순차로_두_번_지우면_두_번째는_404다(AnalyticsAssetKind kind) {
    Long id = create(kind);

    adminService.delete(kind, id, admin);

    assertThatThrownBy(() -> adminService.delete(kind, id, admin))
        .isInstanceOf(kind.notFound().getClass())
        .hasMessage(kind.notFound().getMessage());
    assertThat(deleteAudits(kind, id)).isEqualTo(1);
  }
}
