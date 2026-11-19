package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.ChartResponse;
import com.smartfirehub.analytics.dto.CreateChartRequest;
import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.UpdateChartRequest;
import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-824 — 같은 소유자 안 차트 이름 중복 409.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않아 요청마다 자기 트랜잭션으로 커밋한다.
 */
class ChartDuplicateNameTest extends IntegrationTestBase {

  private static final String MSG = "이미 있는 차트 이름입니다. 다른 이름을 입력하세요.";

  @Autowired private ChartService service;
  @Autowired private SavedQueryService savedQueryService;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long other;
  private Long queryId;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd824c_owner_");
    other = TenantRlsTestSupport.insertUser(dsl, "wd824c_other_");
    for (Long uid : List.of(owner, other)) {
      TenantRlsTestSupport.insertActiveMembership(dsl, uid, DEFAULT_TEST_TENANT_ID);
      dsl.execute(
          "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
          uid);
    }
    // 차트는 저장 쿼리가 필요하다 — 두 사용자 모두 볼 수 있게 공유 쿼리로 만든다.
    queryId =
        savedQueryService
            .create(new CreateSavedQueryRequest("q824c", null, "SELECT 1", null, null, true), owner)
            .id();
  }

  @AfterEach
  void tearDown() {
    inTenantFixture(
        () -> {
          dsl.execute("DELETE FROM chart WHERE created_by IN (?, ?)", owner, other);
          dsl.execute("DELETE FROM saved_query WHERE created_by IN (?, ?)", owner, other);
          dsl.execute("DELETE FROM audit_log WHERE user_id IN (?, ?)", owner, other);
        });
    for (Long uid : List.of(owner, other)) {
      TenantRlsTestSupport.deleteMembership(dsl, uid);
      dsl.execute("delete from user_role where user_id = ?", uid);
      TenantRlsTestSupport.deleteUser(dsl, uid);
    }
  }

  private ChartResponse create(String name, Long userId) {
    return service.create(
        new CreateChartRequest(name, null, queryId, "BAR", Map.of(), false), userId);
  }

  @Test
  void create_rejectsDuplicateName() {
    create("Sales", owner);
    assertThatThrownBy(() -> create("Sales", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void create_rejectsCaseAndSpaceVariant() {
    create("Sales", owner);
    assertThatThrownBy(() -> create("  sales ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void create_allowedWhenOtherOwnerHasSameName() {
    create("Sales", other);
    assertThat(create("Sales", owner).name()).isEqualTo("Sales");
  }

  @Test
  void update_rename_rejectsExistingName() {
    create("A", owner);
    ChartResponse b = create("B", owner);
    assertThatThrownBy(
            () -> service.update(b.id(), new UpdateChartRequest("a", null, null, null, null), owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void update_withoutRename_allowedOnLegacyDuplicate() {
    // 예전부터 겹친 같은 이름 행 둘을 가드를 거치지 않고 직접 넣는다.
    inTenantFixture(
        () -> {
          for (int i = 0; i < 2; i++) {
            dsl.execute(
                "INSERT INTO chart (name, saved_query_id, chart_type, config, is_shared, created_by)"
                    + " VALUES ('Dup', ?, 'BAR', '{}'::jsonb, false, ?)",
                queryId,
                owner);
          }
        });
    Long id =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT min(id) FROM chart WHERE created_by = ? AND name = 'Dup'", owner)
                    .get(0, Long.class));

    ChartResponse updated =
        service.update(id, new UpdateChartRequest("Dup", "새 설명", null, null, null), owner);

    assertThat(updated.description()).isEqualTo("새 설명");
  }
}
