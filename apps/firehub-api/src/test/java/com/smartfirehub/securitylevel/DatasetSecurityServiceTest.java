package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantProvisioningService;
import com.smartfirehub.jooq.Tables;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.dto.AddAccessGrantRequest;
import com.smartfirehub.securitylevel.dto.ChangeDatasetLevelRequest;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import com.smartfirehub.support.TestUsers;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 데이터셋 단위 보안 규칙(스펙 §4.5 clone 행, §4.7 데이터셋 하향·허용 목록). */
class DatasetSecurityServiceTest extends IntegrationTestBase {

  @Autowired protected DSLContext dsl;
  @Autowired protected PasswordEncoder encoder;
  @Autowired protected DatasetSecurityService service;

  protected SecurityFixture fx;
  protected final List<Long> datasets = new ArrayList<>();
  protected final List<Long> users = new ArrayList<>();
  protected final List<Long> roles = new ArrayList<>();
  protected long creatorId;

  @BeforeEach
  void baseSetUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    creatorId = fx.createUser("dss_creator");
    users.add(creatorId);
  }

  @AfterEach
  void baseTearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  protected long dataset(String level) {
    long id = fx.createDatasetRow("dss_" + System.nanoTime(), fx.levelId(level), creatorId);
    datasets.add(id);
    return id;
  }

  @Test
  void clone_inheritsSourceLevel() {
    long src = dataset("민감");
    long copy = dataset("내부");
    service.inheritFromSource(src, copy, creatorId);
    Long level =
        inTenantFixture(
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_ID)
                    .from(DATASET)
                    .where(DATASET.ID.eq(copy))
                    .fetchSingle(DATASET.SECURITY_LEVEL_ID));
    assertThat(level).isEqualTo(fx.levelId("민감"));
  }

  @Test
  void clone_copiesAllowlist_soCopyIsNotOrphaned() {
    long src = dataset("기밀");
    long viewer = fx.createUser("dss_viewer");
    users.add(viewer);
    fx.grantUser(src, viewer);
    long copy = dataset("내부");
    service.inheritFromSource(src, copy, creatorId);
    List<Long> grantedUsers =
        inTenantFixture(
            () ->
                dsl.select(DATASET_ACCESS_GRANT.USER_ID)
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(copy))
                    .fetch(DATASET_ACCESS_GRANT.USER_ID));
    assertThat(grantedUsers).containsExactly(viewer);
  }

  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private TenantProvisioningService provisioning;

  /** 지정 등급 자격 + 등급 변경·허용 목록 권한을 가진 사용자의 열람 자격. */
  private Clearance userWithLevel(String level) {
    long uid = fx.createUser("dss_u");
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(
            "dss_r_" + System.nanoTime(),
            fx.levelId(level),
            "dataset:read",
            "dataset:classify",
            "dataset:grant");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return clearanceResolver.resolve(uid);
  }

  private String codeOf(Throwable e) {
    return ((CodedApiException) e).code();
  }

  private int auditCount(long userId, String... actions) {
    return inTenantFixture(
        () ->
            dsl.fetchCount(
                Tables.AUDIT_LOG,
                Tables.AUDIT_LOG.USER_ID.eq(userId).and(Tables.AUDIT_LOG.ACTION_TYPE.in(actions))));
  }

  @Test
  void classifyAboveOwnClearance_isForbidden() {
    var caller = userWithLevel("민감");
    long ds = dataset("내부");
    assertThatThrownBy(
            () ->
                service.changeLevel(
                    ds, new ChangeDatasetLevelRequest(fx.levelId("기밀"), null), caller))
        .isInstanceOf(CodedApiException.class)
        .extracting(this::codeOf)
        .isEqualTo("CLASSIFY_ABOVE_CLEARANCE");
  }

  @Test
  void downgrade_requiresReasonOf10Chars() {
    var caller = userWithLevel("민감");
    long ds = dataset("민감");
    assertThatThrownBy(
            () ->
                service.changeLevel(
                    ds, new ChangeDatasetLevelRequest(fx.levelId("내부"), "   짧은사유   "), caller))
        .extracting(this::codeOf)
        .isEqualTo("DOWNGRADE_REASON_REQUIRED");
    service.changeLevel(
        ds, new ChangeDatasetLevelRequest(fx.levelId("내부"), "공개 보고서 반영으로 등급 하향"), caller);
    Long level =
        inTenantFixture(
            () ->
                dsl.select(DATASET.SECURITY_LEVEL_ID)
                    .from(DATASET)
                    .where(DATASET.ID.eq(ds))
                    .fetchSingle(DATASET.SECURITY_LEVEL_ID));
    assertThat(level).isEqualTo(fx.levelId("내부"));
  }

  @Test
  void changeToAllowlistLevel_seedsCaller() {
    var caller = userWithLevel("기밀");
    long ds = dataset("내부");
    service.changeLevel(ds, new ChangeDatasetLevelRequest(fx.levelId("기밀"), null), caller);
    List<Long> grantedUsers =
        inTenantFixture(
            () ->
                dsl.select(DATASET_ACCESS_GRANT.USER_ID)
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(ds))
                    .fetch(DATASET_ACCESS_GRANT.USER_ID));
    assertThat(grantedUsers).containsExactly(caller.userId());
    assertThat(auditCount(caller.userId(), "DATASET_SECURITY_LEVEL_CHANGE")).isEqualTo(1);
  }

  @Test
  void removeLastGrant_isRejected_onAllowlistLevel() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    fx.grantUser(ds, caller.userId());
    long grantId = service.listGrants(ds).get(0).id();
    assertThatThrownBy(() -> service.removeGrant(ds, grantId, caller.userId()))
        .extracting(this::codeOf)
        .isEqualTo("ALLOWLIST_LAST_ENTRY");
  }

  @Test
  void addGrant_nonMemberOrBothSubjects_isRejected() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    assertThatThrownBy(
            () ->
                service.addGrant(
                    ds, new AddAccessGrantRequest(9_000_000_002L, null), caller.userId()))
        .extracting(this::codeOf)
        .isEqualTo("GRANT_SUBJECT_INVALID");
    assertThatThrownBy(
            () ->
                service.addGrant(
                    ds, new AddAccessGrantRequest(caller.userId(), 1L), caller.userId()))
        .extracting(this::codeOf)
        .isEqualTo("GRANT_SUBJECT_INVALID");
    assertThatThrownBy(
            () -> service.addGrant(ds, new AddAccessGrantRequest(null, null), caller.userId()))
        .extracting(this::codeOf)
        .isEqualTo("GRANT_SUBJECT_INVALID");
  }

  @Test
  void addAndRemoveGrant_areAudited() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    long other = fx.createUser("dss_other");
    users.add(other);
    fx.grantUser(ds, caller.userId());
    var added = service.addGrant(ds, new AddAccessGrantRequest(other, null), caller.userId());
    assertThat(added.type()).isEqualTo("USER");
    service.removeGrant(ds, added.id(), caller.userId());
    assertThat(
            auditCount(caller.userId(), "DATASET_ACCESS_GRANT_ADD", "DATASET_ACCESS_GRANT_REMOVE"))
        .isEqualTo(2);
  }

  @Test
  void addGrant_sameSubjectTwice_isIdempotent() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    long rid = roles.get(0);
    var first = service.addGrant(ds, new AddAccessGrantRequest(null, rid), caller.userId());
    var second = service.addGrant(ds, new AddAccessGrantRequest(null, rid), caller.userId());
    assertThat(first.type()).isEqualTo("ROLE");
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(service.listGrants(ds)).hasSize(1);
    assertThat(auditCount(caller.userId(), "DATASET_ACCESS_GRANT_ADD")).isEqualTo(1);
  }

  /** 정지(SUSPENDED) 멤버는 허용 목록에 넣을 수 없다 — 활성 멤버만. */
  @Test
  void addGrant_suspendedMember_isRejected() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    long other = fx.createUser("dss_susp");
    users.add(other);
    dsl.execute("update membership set status = 'SUSPENDED' where user_id = ?", other);
    assertThatThrownBy(
            () -> service.addGrant(ds, new AddAccessGrantRequest(other, null), caller.userId()))
        .extracting(this::codeOf)
        .isEqualTo("GRANT_SUBJECT_INVALID");
  }

  /**
   * dataset_access_grant 의 user/role FK 는 테넌트 복합 FK 가 아니다 — 서비스가 막지 않으면 DB 는 다른 테넌트의 사용자·역할 id 를
   * 그대로 받는다. 그래서 서비스 검증 자체를 고정한다.
   */
  @Test
  void addGrant_otherTenantUserOrRole_isRejected() {
    long otherTenant = TenantRlsTestSupport.createActiveTenant(dsl, "dssx" + System.nanoTime());
    provisioning.provisionDefaults(otherTenant);
    long foreignUser = 0;
    try {
      String u = "dssx" + System.nanoTime() + "@example.com";
      foreignUser =
          TestUsers.createMember(
                  dsl, fixtureTransactionTemplate, encoder, u, u, "Password123", "x", otherTenant)
              .id();
      long foreignRole =
          inTenantFixture(
              otherTenant,
              () ->
                  dsl.select(Tables.ROLE.ID)
                      .from(Tables.ROLE)
                      .limit(1)
                      .fetchSingle(Tables.ROLE.ID));
      var caller = userWithLevel("기밀");
      long ds = dataset("기밀");
      final long fu = foreignUser;
      assertThatThrownBy(
              () -> service.addGrant(ds, new AddAccessGrantRequest(fu, null), caller.userId()))
          .extracting(this::codeOf)
          .isEqualTo("GRANT_SUBJECT_INVALID");
      assertThatThrownBy(
              () ->
                  service.addGrant(
                      ds, new AddAccessGrantRequest(null, foreignRole), caller.userId()))
          .extracting(this::codeOf)
          .isEqualTo("GRANT_SUBJECT_INVALID");
      assertThat(service.listGrants(ds)).isEmpty();
    } finally {
      TenantContext.set(DEFAULT_TEST_TENANT_ID);
      if (foreignUser != 0) {
        TestUsers.cleanup(dsl, fixtureTransactionTemplate, foreignUser, otherTenant);
      }
      TenantRlsTestSupport.deleteProvisionedTenantCascade(
          dsl, fixtureTransactionTemplate, otherTenant);
    }
  }

  /** 후보 조회는 이 테넌트 활성 멤버·역할만 — 정지 멤버는 나오지 않는다. */
  @Test
  void candidates_listsOnlyActiveMembersOfThisTenant() {
    long active = fx.createUser("dss_cand");
    users.add(active);
    long suspended = fx.createUser("dss_candx");
    users.add(suspended);
    dsl.execute("update membership set status = 'SUSPENDED' where user_id = ?", suspended);
    var res = inTenantFixture(() -> service.candidates());
    assertThat(res.users()).extracting(u -> u.id()).contains(active).doesNotContain(suspended);
    assertThat(res.roles()).isNotEmpty();
  }

  /**
   * 감사 행은 호출자 트랜잭션에 합류한다 — 바깥 트랜잭션이 롤백되면 허용 항목 추가와 감사가 함께 사라져야 한다(AuditLogService.log 는 자체 트랜잭션을 열지
   * 않고 AuditLogRepository 는 기본 REQUIRED 전파).
   */
  @Test
  void auditRowRollsBackWithBusinessChange() {
    var caller = userWithLevel("기밀");
    long ds = dataset("기밀");
    long other = fx.createUser("dss_rb");
    users.add(other);
    assertThatThrownBy(
            () ->
                TenantRlsTestSupport.runInTenantTransaction(
                    fixtureTransactionTemplate,
                    DEFAULT_TEST_TENANT_ID,
                    () -> {
                      service.addGrant(ds, new AddAccessGrantRequest(other, null), caller.userId());
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.listGrants(ds)).isEmpty();
    assertThat(auditCount(caller.userId(), "DATASET_ACCESS_GRANT_ADD")).isZero();
  }
}
