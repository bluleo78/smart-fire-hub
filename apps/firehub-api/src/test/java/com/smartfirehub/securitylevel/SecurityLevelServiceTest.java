package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.AUDIT_LOG;
import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.SECURITY_LEVEL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.securitylevel.access.LevelPolicy.AiPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.ExportPolicy;
import com.smartfirehub.securitylevel.access.LevelPolicy.SharePolicy;
import com.smartfirehub.securitylevel.dto.DeleteSecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelRequest;
import com.smartfirehub.securitylevel.dto.SecurityLevelResponse;
import com.smartfirehub.securitylevel.dto.SecurityLevelUsage;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.SecurityLevelService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PausedTransactionRace;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.List;
import java.util.Set;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 등급 관리 규칙(스펙 §2.1, §4.7) — 격리 테넌트에서 검증. */
class SecurityLevelServiceTest extends IntegrationTestBase {

  @Autowired protected DSLContext dsl;
  @Autowired protected PasswordEncoder encoder;
  @Autowired protected TenantProvisioningService provisioning;
  @Autowired protected SecurityLevelService service;
  @Autowired protected DatasetAccessGuard guard;
  @Autowired protected SecurityLevelRepository levelRepository;

  protected long tenantId;
  protected long actor;

  @BeforeEach
  void setUpTenant() {
    tenantId = TenantRlsTestSupport.createActiveTenant(dsl, "slsvc" + System.nanoTime());
    provisioning.provisionDefaults(tenantId);
    String u = "slsvc" + System.nanoTime() + "@example.com";
    actor =
        TestUsers.createMember(
                dsl, fixtureTransactionTemplate, encoder, u, u, "Password123", "a", tenantId)
            .id();
  }

  @AfterEach
  void tearDownTenant() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    TenantRlsTestSupport.cleanupAll(
        () ->
            inTenantFixture(
                tenantId,
                () -> dsl.deleteFrom(DATASET).where(DATASET.TENANT_ID.eq(tenantId)).execute()),
        () -> TestUsers.cleanup(dsl, fixtureTransactionTemplate, actor, tenantId),
        () ->
            TenantRlsTestSupport.deleteProvisionedTenantCascade(
                dsl, fixtureTransactionTemplate, tenantId));
  }

  protected <T> T asTenant(java.util.function.Supplier<T> s) {
    return TenantContext.runScopedGet(tenantId, s);
  }

  protected void asTenant(Runnable r) {
    TenantContext.runScoped(tenantId, r);
  }

  protected long levelId(String name) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(SECURITY_LEVEL.ID)
                .from(SECURITY_LEVEL)
                .where(SECURITY_LEVEL.NAME.eq(name))
                .fetchSingle(SECURITY_LEVEL.ID));
  }

  protected long insertDataset(String levelName) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET)
                .set(DATASET.NAME, "sl_ds_" + System.nanoTime())
                .set(DATASET.TABLE_NAME, "sl_ds_" + System.nanoTime())
                .set(DATASET.STORAGE_TYPE, "TABLE")
                .set(DATASET.ORIGIN_TYPE, "SOURCE")
                .set(DATASET.CREATED_BY, actor)
                .set(DATASET.SECURITY_LEVEL_ID, levelId(levelName))
                .returning(DATASET.ID)
                .fetchSingle(DATASET.ID));
  }

  protected static SecurityLevelRequest req(String name) {
    return new SecurityLevelRequest(
        name, false, false, ExportPolicy.ALLOW, AiPolicy.ALL, SharePolicy.ALLOW, false, null);
  }

  @Test
  void create_appendsAsTopRank_andSyncsSystemAdminToNewTop() {
    SecurityLevelResponse created = asTenant(() -> service.create(req("극비"), actor));
    assertThat(created.rank()).isEqualTo(5);
    Long adminLevel =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                    .from(ROLE)
                    .where(ROLE.NAME.eq("ADMIN"))
                    .fetchSingle(ROLE.MAX_SECURITY_LEVEL_ID));
    assertThat(adminLevel).as("시스템 ADMIN 은 항상 최상위(판단 사항 13)").isEqualTo(created.id());
    assertThat(auditCount("SECURITY_LEVEL_CREATE")).isEqualTo(1);
  }

  @Test
  void create_duplicateName_isRejectedWith409() {
    assertThatThrownBy(() -> asTenant(() -> service.create(req("민감"), actor)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SECURITY_LEVEL_NAME_DUPLICATE");
  }

  /**
   * 후속 F5(a) — 사전 검사(existsByName)와 INSERT 사이에 같은 이름이 들어오는 경합. tx1 이 같은 이름을 넣고 커밋 전에 멈춘 사이 등급을 만들면,
   * 사전 검사는 미커밋 행을 못 봐 통과하고 INSERT 가 이름 유니크 인덱스에서 대기한 뒤 위반으로 끝난다 — 그 위반도 사전 검사와 같은 409 {@code
   * SECURITY_LEVEL_NAME_DUPLICATE} 여야 한다(일반 "Data integrity violation" 이 아니라). tx1 은 순위 충돌이 없도록
   * 동떨어진 순위로 넣는다.
   */
  @Test
  void create_racingSameName_isRejectedWith409() throws Exception {
    var out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            tenantId,
            () -> levelRepository.insert(req("경합등급"), 1_000, actor),
            () -> service.create(req("경합등급"), actor));
    assertThat(out.secondBlocked()).as("두 번째 생성은 이름 유니크 인덱스에서 대기해야 한다(경합 구간 통과 증거)").isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(((CodedApiException) out.secondError()).code())
        .isEqualTo("SECURITY_LEVEL_NAME_DUPLICATE");
  }

  /** 후속 F5(a) — 이름 변경도 같다: 경합으로 생긴 이름 유니크 위반은 409 {@code SECURITY_LEVEL_NAME_DUPLICATE}. */
  @Test
  void rename_racingSameName_isRejectedWith409() throws Exception {
    long target = levelId("공개");
    var out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            tenantId,
            () -> levelRepository.insert(req("경합이름"), 1_000, actor),
            () -> service.update(target, req("경합이름"), actor));
    assertThat(out.secondBlocked()).as("이름 변경은 이름 유니크 인덱스에서 대기해야 한다(경합 구간 통과 증거)").isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(((CodedApiException) out.secondError()).code())
        .isEqualTo("SECURITY_LEVEL_NAME_DUPLICATE");
  }

  @Test
  void setDefault_movesTheSingleDefault() {
    asTenant(() -> service.setDefault(levelId("공개"), actor));
    List<String> defaults =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(SECURITY_LEVEL.NAME)
                    .from(SECURITY_LEVEL)
                    .where(SECURITY_LEVEL.IS_DEFAULT.isTrue())
                    .fetch(SECURITY_LEVEL.NAME));
    assertThat(defaults).containsExactly("공개");
    assertThat(auditCount("SECURITY_LEVEL_DEFAULT_CHANGE")).isEqualTo(1);
  }

  @Test
  void usage_countsDatasetsAndRolesPerLevel() {
    insertDataset("민감");
    insertDataset("민감");
    long sensitive = levelId("민감");
    List<SecurityLevelUsage> usage = asTenant(() -> service.usage());
    SecurityLevelUsage row =
        usage.stream().filter(u -> u.levelId() == sensitive).findFirst().orElseThrow();
    assertThat(row.datasetCount()).isEqualTo(2);
    assertThat(row.roleCount()).isZero();
  }

  @Test
  void delete_defaultLevel_isRejected() {
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.delete(
                            levelId("내부"), new DeleteSecurityLevelRequest(null, null), actor)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SECURITY_LEVEL_DEFAULT_UNDELETABLE");
  }

  @Test
  void delete_inUseWithoutTarget_requiresReassign() {
    insertDataset("민감");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.delete(
                            levelId("민감"), new DeleteSecurityLevelRequest(null, null), actor)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SECURITY_LEVEL_REASSIGN_REQUIRED");
  }

  @Test
  void delete_downwardReassign_requiresReasonOf10Chars() {
    insertDataset("민감");
    long internal = levelId("내부");
    assertThatThrownBy(
            () ->
                asTenant(
                    () ->
                        service.delete(
                            levelId("민감"), new DeleteSecurityLevelRequest(internal, "짧음"), actor)))
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("DOWNGRADE_REASON_REQUIRED");
  }

  @Test
  void delete_movesDatasetsAndRoles_thenDeletes_andAudits() {
    long ds = insertDataset("민감");
    long sensitive = levelId("민감");
    long secret = levelId("기밀");
    inTenantFixture(
        tenantId,
        () ->
            dsl.update(ROLE)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, sensitive)
                .where(ROLE.NAME.eq("USER"))
                .execute());
    asTenant(() -> service.delete(sensitive, new DeleteSecurityLevelRequest(secret, null), actor));
    inTenantFixture(
        tenantId,
        () -> {
          assertThat(dsl.fetchExists(SECURITY_LEVEL, SECURITY_LEVEL.ID.eq(sensitive))).isFalse();
          assertThat(
                  dsl.select(DATASET.SECURITY_LEVEL_ID)
                      .from(DATASET)
                      .where(DATASET.ID.eq(ds))
                      .fetchSingle(DATASET.SECURITY_LEVEL_ID))
              .isEqualTo(secret);
          assertThat(
                  dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                      .from(ROLE)
                      .where(ROLE.NAME.eq("USER"))
                      .fetchSingle(ROLE.MAX_SECURITY_LEVEL_ID))
              .isEqualTo(secret);
        });
    assertThat(auditCount("SECURITY_LEVEL_DELETE")).isEqualTo(1);
  }

  /** 허용 목록 필요 등급(기밀)으로 이동한 데이터셋이 고아가 되지 않는지 — 최상위 자격 역할 보유자에게 보여야 한다. */
  @Test
  void delete_reassignToAllowlistLevel_seedsGrants_andDatasetIsVisibleToTopRole() {
    long ds = insertDataset("민감");
    long sensitive = levelId("민감");
    long secret = levelId("기밀");
    asTenant(() -> service.delete(sensitive, new DeleteSecurityLevelRequest(secret, null), actor));
    long adminRole = roleId("ADMIN");
    int grants =
        inTenantFixture(
            tenantId,
            () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(ds)));
    assertThat(grants).as("이동된 데이터셋은 허용 항목이 1개 이상").isGreaterThanOrEqualTo(1);
    Clearance viewer = new Clearance(actor, tenantId, 4, Set.of(adminRole), false, Set.of());
    boolean visible = asTenant(() -> guard.check(viewer, ds, DatasetAction.VIEW, null).allowed());
    assertThat(visible).as("최상위 자격 역할 보유자에게 보여야 한다(고아 아님)").isTrue();
  }

  @Test
  void delete_reassignToAllowlistLevel_doesNotReseedDatasetsThatAlreadyHaveAllowlist() {
    long kept = insertDataset("기밀");
    long moved = insertDataset("민감");
    long userRole = roleId("USER");
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, kept)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, userRole)
                .execute());
    asTenant(
        () ->
            service.delete(
                levelId("민감"), new DeleteSecurityLevelRequest(levelId("기밀"), null), actor));
    int keptCount =
        inTenantFixture(
            tenantId,
            () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(kept)));
    assertThat(keptCount).as("이미 목록이 있는 데이터셋은 건드리지 않는다").isEqualTo(1);
    int movedCount =
        inTenantFixture(
            tenantId,
            () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(moved)));
    assertThat(movedCount).isGreaterThanOrEqualTo(1);
  }

  protected long roleId(String name) {
    return inTenantFixture(
        tenantId,
        () -> dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq(name)).fetchSingle(ROLE.ID));
  }

  @Test
  void delete_topLevel_reassignsAdminToNewTop() {
    long secret = levelId("기밀");
    long sensitive = levelId("민감");
    // ADMIN 이 기밀을 쓰고 있으므로 이동 대상 필수 — 하향 이동이라 사유도 필수.
    asTenant(
        () ->
            service.delete(
                secret, new DeleteSecurityLevelRequest(sensitive, "최상위 등급 통합 정리 작업"), actor));
    Long adminLevel =
        inTenantFixture(
            tenantId,
            () ->
                dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                    .from(ROLE)
                    .where(ROLE.NAME.eq("ADMIN"))
                    .fetchSingle(ROLE.MAX_SECURITY_LEVEL_ID));
    assertThat(adminLevel).isEqualTo(sensitive);
  }

  @Test
  void update_changesNameAndPolicies_andAudits() {
    long sensitive = levelId("민감");
    SecurityLevelResponse r =
        asTenant(
            () ->
                service.update(
                    sensitive,
                    new SecurityLevelRequest(
                        "민감정보",
                        false,
                        false,
                        ExportPolicy.DENY,
                        AiPolicy.DENY,
                        SharePolicy.DENY,
                        true,
                        null),
                    actor));
    assertThat(r.name()).isEqualTo("민감정보");
    assertThat(r.exportPolicy()).isEqualTo("DENY");
    assertThat(auditCount("SECURITY_LEVEL_UPDATE")).isEqualTo(1);
  }

  protected int auditCount(String action) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.fetchCount(
                AUDIT_LOG, AUDIT_LOG.ACTION_TYPE.eq(action).and(AUDIT_LOG.USER_ID.eq(actor))));
  }
}
