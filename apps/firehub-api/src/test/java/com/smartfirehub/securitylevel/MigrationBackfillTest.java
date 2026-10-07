package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.PERMISSION;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.ROLE_PERMISSION;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * V133 백필·시드·프로비저닝 계약을 고정한다.
 *
 * <p>스펙 §7.7 "배포 직후 접근 상실" 방지가 핵심이다: 기존 데이터셋·역할 = 기본(내부), 시스템 ADMIN = 최상위. 이 값이 어긋나면 배포 순간 일부
 * 사용자가 데이터셋을 잃는다. 또한 신규 테넌트가 같은 4등급을 받는지(프로비저닝)와, 새 행이 DEFAULT 함수로 기본 등급을 받는지 확인한다.
 */
class MigrationBackfillTest extends IntegrationTestBase {

  @Autowired private DSLContext dsl;
  @Autowired private TenantProvisioningService provisioningService;

  @Test
  void defaultTenant_hasFourSeededLevels_withInternalDefault() {
    List<String> names =
        inTenantFixture(
            () ->
                dsl.select(SECURITY_LEVEL.NAME)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                    .orderBy(SECURITY_LEVEL.RANK.asc())
                    .fetch(SECURITY_LEVEL.NAME));
    assertThat(names).containsExactly("공개", "내부", "민감", "기밀");
    String defaultName =
        inTenantFixture(
            () ->
                dsl.select(SECURITY_LEVEL.NAME)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                    .and(SECURITY_LEVEL.IS_DEFAULT.isTrue())
                    .fetchOne(SECURITY_LEVEL.NAME));
    assertThat(defaultName).isEqualTo("내부");
  }

  @Test
  void seededPolicies_matchSpecTable() {
    // 스펙 §2.2 표 그대로 — 기밀만 허용 목록 필요, 민감·기밀 감사, 기밀 공유 차단.
    var rows =
        inTenantFixture(
            () ->
                dsl.selectFrom(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                    .orderBy(SECURITY_LEVEL.RANK.asc())
                    .fetch());
    assertThat(rows).extracting(r -> r.getAllowlistRequired()).containsExactly(false, false, false, true);
    assertThat(rows).extracting(r -> r.getAdminBypass()).containsExactly(false, false, false, false);
    assertThat(rows)
        .extracting(r -> r.getExportPolicy())
        .containsExactly("ALLOW", "ALLOW", "PERMISSION", "DENY");
    assertThat(rows)
        .extracting(r -> r.getAiPolicy())
        .containsExactly("ALL", "ALL", "SELF_HOSTED_ONLY", "SELF_HOSTED_ONLY");
    assertThat(rows)
        .extracting(r -> r.getSharePolicy())
        .containsExactly("ALLOW", "ALLOW", "ALLOW", "DENY");
    assertThat(rows).extracting(r -> r.getAuditAccess()).containsExactly(false, false, true, true);
  }

  @Test
  void systemAdminRole_isTopLevel_otherRolesDefault() {
    inTenantFixture(
        () -> {
          long top =
              dsl.select(SECURITY_LEVEL.ID)
                  .from(SECURITY_LEVEL)
                  .where(SECURITY_LEVEL.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                  .orderBy(SECURITY_LEVEL.RANK.desc())
                  .limit(1)
                  .fetchOne(SECURITY_LEVEL.ID);
          long def =
              dsl.select(SECURITY_LEVEL.ID)
                  .from(SECURITY_LEVEL)
                  .where(SECURITY_LEVEL.TENANT_ID.eq(DEFAULT_TEST_TENANT_ID))
                  .and(SECURITY_LEVEL.IS_DEFAULT.isTrue())
                  .fetchOne(SECURITY_LEVEL.ID);
          Long adminLevel =
              dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                  .from(ROLE)
                  .where(ROLE.NAME.eq("ADMIN"))
                  .and(ROLE.IS_SYSTEM.isTrue())
                  .fetchOne(ROLE.MAX_SECURITY_LEVEL_ID);
          Long userLevel =
              dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                  .from(ROLE)
                  .where(ROLE.NAME.eq("USER"))
                  .fetchOne(ROLE.MAX_SECURITY_LEVEL_ID);
          assertThat(adminLevel).isEqualTo(top);
          assertThat(userLevel).isEqualTo(def);
          // 새 데이터셋 메타 행도 DEFAULT 함수로 기본 등급을 받는다(백필과 같은 값).
          // created_by 는 "user" FK 라 실제 사용자가 필요하다(시드 사용자 없음).
          Long creator = TenantRlsTestSupport.insertUser(dsl, "slprobe");
          long dsId =
              dsl.insertInto(DATASET)
                  .set(DATASET.NAME, "sl_probe_" + System.nanoTime())
                  .set(DATASET.TABLE_NAME, "sl_probe_" + System.nanoTime())
                  .set(DATASET.STORAGE_TYPE, "TABLE")
                  .set(DATASET.ORIGIN_TYPE, "SOURCE")
                  .set(DATASET.CREATED_BY, creator)
                  .returning(DATASET.ID)
                  .fetchSingle(DATASET.ID);
          assertThat(
                  dsl.select(DATASET.SECURITY_LEVEL_ID)
                      .from(DATASET)
                      .where(DATASET.ID.eq(dsId))
                      .fetchSingle(DATASET.SECURITY_LEVEL_ID))
              .isEqualTo(def);
          dsl.deleteFrom(DATASET).where(DATASET.ID.eq(dsId)).execute();
          TenantRlsTestSupport.deleteUser(dsl, creator);
        });
  }

  @Test
  void newRole_getsDefaultLevelViaColumnDefault() {
    inTenantFixture(
        () -> {
          long id =
              dsl.insertInto(ROLE)
                  .set(ROLE.NAME, "sl_default_probe_" + System.nanoTime())
                  .set(ROLE.IS_SYSTEM, false)
                  .returning(ROLE.ID)
                  .fetchOne(ROLE.ID);
          Long level =
              dsl.select(ROLE.MAX_SECURITY_LEVEL_ID).from(ROLE).where(ROLE.ID.eq(id)).fetchOne(ROLE.MAX_SECURITY_LEVEL_ID);
          Long def =
              dsl.select(SECURITY_LEVEL.ID)
                  .from(SECURITY_LEVEL)
                  .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
                  .fetchOne(SECURITY_LEVEL.ID);
          assertThat(level).isEqualTo(def);
          dsl.deleteFrom(ROLE).where(ROLE.ID.eq(id)).execute();
        });
  }

  @Test
  void newPermissions_seededAndGrantedToSystemAdmin() {
    List<String> codes =
        List.of("security:settings", "dataset:classify", "dataset:grant", "data:export_restricted");
    int catalog = dsl.fetchCount(PERMISSION, PERMISSION.CODE.in(codes).and(PERMISSION.CATEGORY.eq("security")));
    assertThat(catalog).isEqualTo(4);
    int granted =
        inTenantFixture(
            () ->
                dsl.fetchCount(
                    dsl.select()
                        .from(ROLE_PERMISSION)
                        .join(ROLE)
                        .on(ROLE.ID.eq(ROLE_PERMISSION.ROLE_ID))
                        .join(PERMISSION)
                        .on(PERMISSION.ID.eq(ROLE_PERMISSION.PERMISSION_ID))
                        .where(ROLE.NAME.eq("ADMIN"))
                        .and(PERMISSION.CODE.in(codes))));
    assertThat(granted).isEqualTo(4);
  }

  @Test
  void provisionedTenant_getsLevels_andAdminTop() {
    long tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "sl" + System.nanoTime());
    try {
      provisioningService.provisionDefaults(tenantId);
      inTenantFixture(
          tenantId,
          () -> {
            List<String> names =
                dsl.select(SECURITY_LEVEL.NAME)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.TENANT_ID.eq(tenantId))
                    .orderBy(SECURITY_LEVEL.RANK.asc())
                    .fetch(SECURITY_LEVEL.NAME);
            assertThat(names).containsExactly("공개", "내부", "민감", "기밀");
            Long adminLevelRank =
                dsl.select(SECURITY_LEVEL.RANK)
                    .from(ROLE)
                    .join(SECURITY_LEVEL)
                    .on(SECURITY_LEVEL.ID.eq(ROLE.MAX_SECURITY_LEVEL_ID))
                    .where(ROLE.TENANT_ID.eq(tenantId).and(ROLE.NAME.eq("ADMIN")))
                    .fetchOne(r -> r.get(SECURITY_LEVEL.RANK).longValue());
            assertThat(adminLevelRank).isEqualTo(4L);
          });
      // 멱등 — 두 번 불러도 등급이 늘지 않는다(운영자 재실행 안전).
      provisioningService.provisionDefaults(tenantId);
      int count =
          inTenantFixture(
              tenantId, () -> dsl.fetchCount(SECURITY_LEVEL, SECURITY_LEVEL.TENANT_ID.eq(tenantId)));
      assertThat(count).isEqualTo(4);
    } finally {
      TenantRlsTestSupport.deleteProvisionedTenantCascade(dsl, fixtureTransactionTemplate, tenantId);
    }
  }
}
