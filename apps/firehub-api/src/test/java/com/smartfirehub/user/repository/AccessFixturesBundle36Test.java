package com.smartfirehub.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 묶음 ③·⑥ 이 쓰는 새 픽스처(보안 등급·접근 허용·온톨로지·파이프라인·트리거, WD-711)가 PG 에서 행을 만들고 정리까지 되는지 확인한다. 실제 리포지토리 계약은
 * 이후 태스크의 계약 테스트가 맡고, 여기서는 픽스처 자체의 배선(필수 열·FK·정리 순서)만 본다. 끝에서 {@link AccessContractBase} 가 정리하므로 FK
 * 순서가 틀리면 이 시험이 깨진다.
 */
class AccessFixturesBundle36Test extends AccessContractBase {

  @Test
  @DisplayName("새 픽스처가 보안 등급·접근 허용·온톨로지·파이프라인·트리거를 만든다")
  void createsBundle36Rows() {
    long tenant = fx.createTenant();
    fx.trackTenant(tenant);
    long user = fx.createUser(prefix + "_u", prefix + "@x.test");
    fx.trackUser(user);
    fx.createMembership(user, tenant, "ACTIVE");
    long role = fx.createRole(tenant, prefix + "_r", "d");
    long dataset = fx.createDataset(tenant, prefix + "_ds");

    int levelsBefore = fx.countTenantRows("security_level", tenant);
    long level = fx.createSecurityLevel(tenant, 77, prefix + "_lv");
    assertThat(level).isPositive();
    assertThat(fx.countTenantRows("security_level", tenant)).isEqualTo(levelsBefore + 1);

    fx.grantDatasetAccess(tenant, dataset, role, null);
    fx.grantDatasetAccess(tenant, dataset, null, user);

    assertThat(fx.createOntology(tenant, prefix + "_onto")).isPositive();

    long pipeline = fx.createPipeline(tenant, prefix + "_p", true);
    assertThat(pipeline).isPositive();
    assertThat(fx.createTrigger(pipeline, prefix + "_t1", "SCHEDULE", "{\"cron\":\"0 0 * * *\"}"))
        .isPositive();
    assertThat(
            fx.createTrigger(pipeline, prefix + "_t2", "API", "{\"tokenHash\":\"" + prefix + "\"}"))
        .isPositive();
  }
}
