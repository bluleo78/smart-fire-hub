package com.smartfirehub.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.support.tibero.TiberoLive;
import com.smartfirehub.support.tibero.TiberoPlatformSchema;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 묶음 ④·⑤ 새 Tibero 픽스처 4개(슬랙 워크스페이스·선제 작업·리포트 템플릿·실행 이력, WD-709·710)가 실서버에서 필수 열·FK 를 맞춰 행을 만들고(템플릿의
 * userId=null 바인드 포함) 정리까지 되는지 확인한다.
 */
@TiberoLive
class TiberoAccessFixturesBundle45LiveTest {

  @Test
  @DisplayName("새 Tibero 픽스처 4개가 행을 만들고 정리된다(userId null 템플릿 포함)")
  void createsAndCleansUp() {
    TiberoPlatformSchema poc = TiberoPlatformSchema.shared();
    TiberoAccessFixtures fx = new TiberoAccessFixtures(poc);
    String prefix = "b45" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    try {
      long tenant = fx.createTenant();
      long user = fx.createUser(prefix + "_u", prefix + "@x.test");
      assertThat(fx.createSlackWorkspace(tenant, prefix + "_T1", false)).isPositive();
      long revoked = fx.createSlackWorkspace(tenant, prefix + "_T2", true);
      assertThat(
              poc.jdbc()
                  .queryForObject(
                      "SELECT COUNT(*) FROM slack_workspace WHERE id = ? AND revoked_at IS NOT NULL",
                      Integer.class,
                      revoked))
          .isEqualTo(1);
      assertThat(fx.createReportTemplate(tenant, user, prefix + "_tpl")).isPositive();
      assertThat(fx.createReportTemplate(tenant, null, prefix + "_sys")).isPositive();
      long job = fx.createProactiveJob(tenant, user, prefix + "_job");
      assertThat(fx.createProactiveJobExecution(tenant, job, "COMPLETED")).isPositive();
      // 묶음 ⑤(WD-710): 저장 쿼리(데이터셋 null 바인드 포함)·차트·대시보드·위젯 — 정리 순서도 함께 확인한다.
      long query = fx.createSavedQuery(tenant, user, prefix + "_q", true, null);
      long chart = fx.createChart(tenant, user, query, false);
      long dashboard = fx.createDashboard(tenant, user, true);
      assertThat(fx.addWidget(tenant, dashboard, chart)).isPositive();
      assertThat(
              poc.jdbc()
                  .queryForObject(
                      "SELECT is_shared FROM saved_query WHERE id = ?", Integer.class, query))
          .isEqualTo(1);
    } finally {
      fx.cleanup();
    }
  }
}
