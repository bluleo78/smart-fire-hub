package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.CreateDashboardRequest;
import com.smartfirehub.analytics.dto.DashboardResponse;
import com.smartfirehub.analytics.dto.UpdateDashboardRequest;
import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-824 — 같은 소유자 안 대시보드 이름 중복 409.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않아 요청마다 자기 트랜잭션으로 커밋한다.
 */
class DashboardDuplicateNameTest extends IntegrationTestBase {

  private static final String MSG = "이미 있는 대시보드 이름입니다. 다른 이름을 입력하세요.";

  @Autowired private AnalyticsDashboardService service;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long other;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd824d_owner_");
    other = TenantRlsTestSupport.insertUser(dsl, "wd824d_other_");
    for (Long uid : List.of(owner, other)) {
      TenantRlsTestSupport.insertActiveMembership(dsl, uid, DEFAULT_TEST_TENANT_ID);
      dsl.execute(
          "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
          uid);
    }
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM dashboard WHERE created_by IN (?, ?)", owner, other);
          dsl.execute("DELETE FROM audit_log WHERE user_id IN (?, ?)", owner, other);
        });
    for (Long uid : List.of(owner, other)) {
      TenantRlsTestSupport.deleteMembership(dsl, uid);
      dsl.execute("delete from user_role where user_id = ?", uid);
      TenantRlsTestSupport.deleteUser(dsl, uid);
    }
  }

  private DashboardResponse create(String name, Long userId) {
    return service.create(new CreateDashboardRequest(name, null, false, null), userId);
  }

  @Test
  void create_rejectsDuplicateName() {
    create("Board", owner);
    assertThatThrownBy(() -> create("Board", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void create_rejectsCaseAndSpaceVariant() {
    create("Board", owner);
    assertThatThrownBy(() -> create(" board  ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void create_allowedWhenOtherOwnerHasSameName() {
    create("Board", other);
    assertThat(create("Board", owner).name()).isEqualTo("Board");
  }

  @Test
  void update_rename_rejectsExistingName() {
    create("A", owner);
    DashboardResponse b = create("B", owner);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(), new UpdateDashboardRequest("a", null, null, null, null), owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void update_withoutRename_allowedOnLegacyDuplicate() {
    inTenantFixture(
        () -> {
          for (int i = 0; i < 2; i++) {
            dsl.execute(
                "INSERT INTO dashboard (name, is_shared, created_by) VALUES ('Dup', false, ?)",
                owner);
          }
        });
    Long id =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT min(id) FROM dashboard WHERE created_by = ? AND name = 'Dup'",
                        owner)
                    .get(0, Long.class));

    DashboardResponse updated =
        service.update(id, new UpdateDashboardRequest("Dup", "새 설명", null, null, null), owner);

    assertThat(updated.description()).isEqualTo("새 설명");
  }
}
