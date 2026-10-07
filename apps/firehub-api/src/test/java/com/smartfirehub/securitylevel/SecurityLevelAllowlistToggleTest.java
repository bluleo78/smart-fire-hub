package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 스펙 §4.7 "allowlist_required 켜기: 영향 데이터셋 수 표시, 현재 열람 가능한 역할로 허용 목록 채우기(기본 체크)". */
class SecurityLevelAllowlistToggleTest extends SecurityLevelServiceTest {

  private SecurityLevelRequest sensitiveWithAllowlist(Boolean seed) {
    return new SecurityLevelRequest(
        "민감",
        true,
        false,
        ExportPolicy.PERMISSION,
        AiPolicy.SELF_HOSTED_ONLY,
        SharePolicy.ALLOW,
        true,
        seed);
  }

  private long customRole(String levelName) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(ROLE)
                .set(ROLE.NAME, "viewer_" + System.nanoTime())
                .set(ROLE.IS_SYSTEM, false)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, levelId(levelName))
                .returning(ROLE.ID)
                .fetchSingle(ROLE.ID));
  }

  @Test
  void impact_countsDatasetsWithEmptyAllowlist() {
    insertDataset("민감");
    insertDataset("민감");
    assertThat(asTenant(() -> service.allowlistImpact(levelId("민감"))).datasetsWithoutAllowlist())
        .isEqualTo(2);
  }

  @Test
  void allowlistToggleOn_seedsCurrentViewerRoles() {
    long ds = insertDataset("민감");
    long sensitiveViewer = customRole("민감");
    long internalOnly = customRole("내부");
    asTenant(() -> service.update(levelId("민감"), sensitiveWithAllowlist(true), actor));
    List<Long> grantedRoles =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(DATASET_ACCESS_GRANT.ROLE_ID)
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(ds))
                    .fetch(DATASET_ACCESS_GRANT.ROLE_ID));
    long adminRole =
        inTenantFixture(
            tenantId,
            () -> dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchSingle(ROLE.ID));
    // 민감 이상 자격 역할(ADMIN=기밀, 사용자 정의 민감)만 — 내부 역할은 원래 못 봤으므로 넣지 않는다(확대 금지).
    assertThat(grantedRoles)
        .containsExactlyInAnyOrder(adminRole, sensitiveViewer)
        .doesNotContain(internalOnly);
  }

  @Test
  void toggleOn_withoutSeed_leavesAllowlistEmpty() {
    long ds = insertDataset("민감");
    asTenant(() -> service.update(levelId("민감"), sensitiveWithAllowlist(false), actor));
    int count =
        inTenantFixture(
            tenantId,
            () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(ds)));
    assertThat(count).isZero();
  }

  @Test
  void seed_skipsDatasetsThatAlreadyHaveAllowlist() {
    long ds = insertDataset("민감");
    long existingRole = customRole("민감");
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, ds)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, existingRole)
                .execute());
    asTenant(() -> service.update(levelId("민감"), sensitiveWithAllowlist(true), actor));
    int count =
        inTenantFixture(
            tenantId,
            () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(ds)));
    assertThat(count).as("이미 목록이 있는 데이터셋은 건드리지 않는다").isEqualTo(1);
  }
}
