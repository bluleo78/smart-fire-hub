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
import com.smartfirehub.securitylevel.service.RoleClearanceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 스펙 §2.3·§4.7 역할 자격 규칙 + 판단 사항 12·14. */
class RoleClearanceServiceTest extends SecurityLevelServiceTest {

  @Autowired private RoleClearanceService clearanceService;
  @Autowired private ClearanceResolver clearanceResolver;
  @Autowired private RoleService roleService;

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
}
