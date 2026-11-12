package com.smartfirehub.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 묶음 ④·⑤ 가 쓰는 새 픽스처(슬랙 워크스페이스·선제 작업·리포트 템플릿·실행 이력, WD-709·710)가 PG 에서 필수 열·FK 를 맞춰 행을 만들고 정리까지 되는지
 * 확인한다. 끝에서 {@link AccessContractBase} 가 정리하므로 정리 순서가 틀리면 이 시험이 깨진다. 실제 리포지토리 계약은 이후 태스크가 맡는다.
 */
class AccessFixturesBundle45Test extends AccessContractBase {

  @Test
  @DisplayName("새 픽스처가 슬랙 워크스페이스·선제 작업·템플릿·실행 이력을 만든다")
  void createsBundle45Rows() {
    long tenant = fx.createTenant();
    fx.trackTenant(tenant);
    long user = fx.createUser(prefix + "_u", prefix + "@x.test");
    fx.trackUser(user);
    fx.createMembership(user, tenant, "ACTIVE");

    assertThat(fx.createSlackWorkspace(tenant, prefix + "_T1", false)).isPositive();
    assertThat(fx.createSlackWorkspace(tenant, prefix + "_T2", true)).isPositive();

    assertThat(fx.createReportTemplate(tenant, user, prefix + "_tpl")).isPositive();
    assertThat(fx.createReportTemplate(tenant, null, prefix + "_sys")).isPositive();
    assertThat(fx.countTenantRows("report_template", tenant)).isGreaterThanOrEqualTo(2);

    long job = fx.createProactiveJob(tenant, user, prefix + "_job");
    assertThat(job).isPositive();
    assertThat(fx.createProactiveJobExecution(tenant, job, "COMPLETED")).isPositive();
    assertThat(fx.createProactiveJobExecution(tenant, job, "RUNNING")).isPositive();
  }
}
