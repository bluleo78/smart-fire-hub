package com.smartfirehub.securitylevel;

import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static com.smartfirehub.jooq.Tables.ROLE;
import static com.smartfirehub.jooq.Tables.USER_ROLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.role.service.RoleService;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.securitylevel.service.RoleClearanceService;
import com.smartfirehub.support.PausedTransactionRace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 스펙 §2.3·§4.7 역할 자격 규칙 + 판단 사항 12·14. */
class RoleClearanceServiceTest extends SecurityLevelServiceTest {

  @Autowired private RoleClearanceService clearanceService;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private RoleService roleService;
  @Autowired private DatasetSecurityService datasetSecurityService;

  private long role(String level) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(ROLE)
                .set(ROLE.NAME, "rc_" + System.nanoTime())
                .set(ROLE.IS_SYSTEM, false)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, levelId(level))
                .returning(ROLE.ID)
                .fetchSingle(ROLE.ID));
  }

  private void assign(long roleId) {
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(USER_ROLE)
                .set(USER_ROLE.USER_ID, actor)
                .set(USER_ROLE.ROLE_ID, roleId)
                .execute());
  }

  private Clearance caller() {
    return asTenant(() -> clearanceResolver.resolve(actor));
  }

  private long adminRoleId() {
    return inTenantFixture(
        tenantId,
        () -> dsl.select(ROLE.ID).from(ROLE).where(ROLE.NAME.eq("ADMIN")).fetchSingle(ROLE.ID));
  }

  @Test
  void systemAdminClearance_isFixed() {
    long admin = adminRoleId();
    assertThat(asTenant(() -> clearanceService.get(admin)).fixed()).isTrue();
    assertThatThrownBy(() -> asTenant(() -> clearanceService.set(admin, levelId("공개"), caller())))
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("SYSTEM_ADMIN_CLEARANCE_FIXED");
  }

  @Test
  void setClearanceAboveOwn_isForbidden() {
    long r = role("공개");
    // actor 는 USER(내부) — 기밀 지정 불가(판단 사항 12).
    assertThatThrownBy(() -> asTenant(() -> clearanceService.set(r, levelId("기밀"), caller())))
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("CLEARANCE_ABOVE_OWN");
  }

  private long currentLevelOf(long roleId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.select(ROLE.MAX_SECURITY_LEVEL_ID)
                .from(ROLE)
                .where(ROLE.ID.eq(roleId))
                .fetchSingle(ROLE.MAX_SECURITY_LEVEL_ID));
  }

  /** CR7 — 현재 자격이 호출자보다 높은 역할은 낮추는 변경도 거부한다(역할은 그대로). */
  @Test
  void changingRoleWhoseCurrentClearanceIsAboveOwn_isForbidden() {
    long r = role("기밀");
    // actor 는 USER(내부) — 기밀 역할을 공개로 끌어내리려 한다(결과 등급은 본인 이하지만 현재 등급이 본인 초과).
    assertThatThrownBy(() -> asTenant(() -> clearanceService.set(r, levelId("공개"), caller())))
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("CLEARANCE_ABOVE_OWN");
    assertThat(currentLevelOf(r)).isEqualTo(levelId("기밀"));
    assertThat(auditCount("ROLE_CLEARANCE_CHANGE")).isZero();
  }

  /** 양성 대조 — 본인과 같은 등급의 역할은 낮출 수 있고, 최상위 자격(관리자)은 상위 역할도 바꿀 수 있다. */
  @Test
  void changingRoleAtOwnClearance_andTopCallerChangingHigherRole_areAllowed() {
    long own = role("내부");
    asTenant(() -> clearanceService.set(own, levelId("공개"), caller()));
    assertThat(currentLevelOf(own)).isEqualTo(levelId("공개"));
    long high = role("기밀");
    Clearance top = caller().withRank(4);
    asTenant(() -> clearanceService.set(high, levelId("민감"), top));
    assertThat(currentLevelOf(high)).isEqualTo(levelId("민감"));
  }

  @Test
  void lastTopLevelRole_cannotBeLowered() {
    // 시스템 ADMIN 이 최상위 고정이라 정상 상태에선 도달 불가 — 손상 상태(ADMIN 이 최상위 아님)를 만들어 방어 검사를 확인한다.
    inTenantFixture(
        tenantId,
        () ->
            dsl.update(ROLE)
                .set(ROLE.MAX_SECURITY_LEVEL_ID, levelId("공개"))
                .where(ROLE.NAME.eq("ADMIN"))
                .execute());
    long top = role("기밀"); // 이제 최상위(기밀) 열람 역할은 이것 하나뿐
    Clearance c = caller().withRank(4); // 호출자 자격 검사는 통과시키고 불변식만 본다
    assertThatThrownBy(() -> asTenant(() -> clearanceService.set(top, levelId("민감"), c)))
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("TOP_LEVEL_ROLE_REQUIRED");
  }

  @Test
  void preview_reportsCallerLoss_andSetAudits() {
    long r = role("민감");
    assign(r);
    insertDataset("민감");
    var p = asTenant(() -> clearanceService.preview(r, levelId("공개"), caller()));
    assertThat(p.callerLostDatasetCount()).isEqualTo(1);
    assertThat(p.roleUserCount()).isEqualTo(1);
    asTenant(() -> clearanceService.set(r, levelId("공개"), caller()));
    assertThat(auditCount("ROLE_CLEARANCE_CHANGE")).isEqualTo(1);
  }

  @Test
  void deleteRole_soleAllowlistEntry_isRejected() {
    long r = role("기밀");
    long ds = insertDataset("기밀");
    inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, ds)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, r)
                .execute());
    assertThatThrownBy(() -> asTenant(() -> roleService.deleteRole(r)))
        .isInstanceOf(CodedApiException.class)
        .extracting(e -> ((CodedApiException) e).code())
        .isEqualTo("ROLE_SOLE_ALLOWLIST_ENTRY");
  }

  /** 허용 항목 1개를 직접 넣고 id 를 돌려준다(사용자 항목이면 roleId=null). */
  private long grant(long datasetId, Long userId, Long roleId) {
    return inTenantFixture(
        tenantId,
        () ->
            dsl.insertInto(DATASET_ACCESS_GRANT)
                .set(DATASET_ACCESS_GRANT.DATASET_ID, datasetId)
                .set(DATASET_ACCESS_GRANT.USER_ID, userId)
                .set(DATASET_ACCESS_GRANT.ROLE_ID, roleId)
                .returning(DATASET_ACCESS_GRANT.ID)
                .fetchSingle(DATASET_ACCESS_GRANT.ID));
  }

  private int grantCount(long datasetId) {
    return inTenantFixture(
        tenantId,
        () -> dsl.fetchCount(DATASET_ACCESS_GRANT, DATASET_ACCESS_GRANT.DATASET_ID.eq(datasetId)));
  }

  private boolean roleExists(long roleId) {
    return inTenantFixture(tenantId, () -> dsl.fetchExists(ROLE, ROLE.ID.eq(roleId)));
  }

  /**
   * 후속 F4 — 허용 항목 2개(역할 R + 사용자)인 기밀 데이터셋에서 사용자 항목 제거(tx1)와 역할 R 삭제(tx2)가 동시에 진행돼도 데이터셋이 고아가 되면 안
   * 된다. tx1 이 제거를 끝내고 커밋 전에 멈춘 사이 tx2 가 시작한다 — tx2 는 데이터셋 행 잠금에서 대기해야 하고(잠금이 없으면 대기 없이 개수 2 를 보고
   * 통과해 역할과 항목이 함께 사라진다), tx1 커밋 뒤 R 이 유일한 항목이 됐음을 보고 {@code ROLE_SOLE_ALLOWLIST_ENTRY} 로 거부돼야 한다.
   */
  @Test
  void deleteRole_racingWithGrantRemoval_waitsAndRejects() throws Exception {
    long r = role("기밀");
    long ds = insertDataset("기밀");
    grant(ds, null, r);
    long userGrant = grant(ds, actor, null);

    var out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            tenantId,
            () -> datasetSecurityService.removeGrant(ds, userGrant, actor),
            () -> {
              roleService.deleteRole(r);
              return null;
            });

    assertThat(out.secondBlocked()).as("역할 삭제는 항목 제거 커밋 전까지 데이터셋 행 잠금에서 대기해야 한다").isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(((CodedApiException) out.secondError()).code())
        .isEqualTo("ROLE_SOLE_ALLOWLIST_ENTRY");
    assertThat(roleExists(r)).isTrue();
    assertThat(grantCount(ds)).isEqualTo(1);
  }

  /**
   * 후속 F4 — 반대 순서도 같다: 역할 R 삭제(tx1)가 검사를 통과하고 커밋 전에 멈춘 사이 사용자 항목 제거(tx2)가 시작하면, tx2 는 역할 삭제가 잡은
   * 데이터셋 행 잠금에서 대기한 뒤 R 의 항목이 사라진 것을 보고 {@code ALLOWLIST_LAST_ENTRY} 로 거부돼야 한다(같은 잠금 순서 — 교착 없음).
   */
  @Test
  void grantRemoval_racingWithDeleteRole_waitsAndRejects() throws Exception {
    long r = role("기밀");
    long ds = insertDataset("기밀");
    grant(ds, null, r);
    long userGrant = grant(ds, actor, null);

    var out =
        PausedTransactionRace.run(
            fixtureTransactionTemplate,
            tenantId,
            () -> roleService.deleteRole(r),
            () -> {
              datasetSecurityService.removeGrant(ds, userGrant, actor);
              return null;
            });

    assertThat(out.secondBlocked()).as("항목 제거는 역할 삭제 커밋 전까지 데이터셋 행 잠금에서 대기해야 한다").isTrue();
    assertThat(out.secondError()).isInstanceOf(CodedApiException.class);
    assertThat(((CodedApiException) out.secondError()).code()).isEqualTo("ALLOWLIST_LAST_ENTRY");
    assertThat(roleExists(r)).isFalse();
    assertThat(grantCount(ds)).isEqualTo(1);
  }
}
