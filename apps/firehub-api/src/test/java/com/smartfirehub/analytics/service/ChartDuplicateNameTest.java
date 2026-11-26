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
            () ->
                service.update(b.id(), new UpdateChartRequest("a", null, null, null, null), owner))
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

  // ── WD-1094: 앞뒤 유니코드 공백을 걷어낸 뒤 중복 검사·저장 (스마트 작업 WD-1085·WD-1108 과 같은 판정) ─────────

  private Long insertLegacy(Long userId, String name) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO chart (name, saved_query_id, chart_type, config, is_shared, created_by)"
                        + " VALUES (?, ?, 'BAR', '{}'::jsonb, false, ?) RETURNING id",
                    name,
                    queryId,
                    userId)
                .get(0, Long.class));
  }

  /** 전각 공백·NBSP 로 감싼 이름도 같은 이름으로 본다 — SQL 의 trim 은 ASCII 공백만 걷는다. */
  @Test
  void create_rejectsUnicodePaddedDuplicate() {
    create("매출", owner);
    assertThatThrownBy(() -> create("\u3000매출", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(() -> create("매출 ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(() -> create("\u00A0매출\u00A0", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 안쪽 전각 공백만 다른 이름도 같은 이름이다(translate). */
  @Test
  void create_rejectsInnerUnicodeSpaceVariant() {
    create("매 출", owner);
    assertThatThrownBy(() -> create("매\u3000출", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 저장되는 이름은 앞뒤 공백이 걷힌 값이다. */
  @Test
  void create_storesStrippedName() {
    assertThat(create("\u3000매출 ", owner).name()).isEqualTo("매출");
  }

  /** 다른 차트 이름에 공백만 덧대 바꾸면 중복으로 거절된다. */
  @Test
  void update_rename_rejectsPaddedExistingName() {
    create("매출", owner);
    ChartResponse b = create("다른 차트", owner);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(), new UpdateChartRequest("\u3000매출", null, null, null, null), owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 자기 이름에 공백만 덧댄 수정은 성공하고 걷어낸 이름으로 저장된다. */
  @Test
  void update_selfNameWithPadding_succeedsAndStrips() {
    ChartResponse a = create("매출", owner);
    ChartResponse updated =
        service.update(
            a.id(), new UpdateChartRequest("\u3000매출\u3000", null, null, null, null), owner);
    assertThat(updated.name()).isEqualTo("매출");
  }

  /** 옛 행 이름이 전각 공백으로 감싸져 있어도 같은 이름 생성을 막는다. */
  @Test
  void create_rejectsWhenLegacyRowHasUnicodePadding() {
    insertLegacy(owner, "매출\u3000");
    assertThatThrownBy(() -> create("매출", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 옛(패딩된) 행 자신을 걷어낸 이름으로 고치는 수정은 막히지 않는다 — 자기 자신은 비교에서 제외. */
  @Test
  void update_selfLegacyPaddedRow_notBlocked() {
    Long id = insertLegacy(owner, "매출\u3000");
    ChartResponse updated =
        service.update(id, new UpdateChartRequest("매출", null, null, null, null), owner);
    assertThat(updated.name()).isEqualTo("매출");
  }

  /**
   * 옛(패딩된) 행 옆에 같은 이름의 정상 행이 있어도, 그 옛 행의 설명만 고치는 저장은 막히지 않는다(WD-1094).
   *
   * <p>서버가 이름을 걷어내므로 편집 화면의 「매출　」 저장은 「매출」로 들어온다. 개명 판정이 ASCII 비교면 개명으로 보고 409 가 난다. 유니코드
   * 비교(nameComparison) 여야 개명이 아니라서 통과한다.
   */
  @Test
  void update_legacyPaddedRowNextToSameName_descriptionOnly_succeeds() {
    insertLegacy(owner, "매출");
    Long id = insertLegacy(owner, "매출\u3000");
    ChartResponse updated =
        service.update(id, new UpdateChartRequest("매출", "새 설명", null, null, null), owner);
    assertThat(updated.description()).isEqualTo("새 설명");
  }

  /** 다른 소유자의 패딩된 이름은 막지 않는다. */
  @Test
  void create_allowedWhenOtherOwnerHasUnicodePaddedName() {
    insertLegacy(other, "\u3000매출");
    assertThat(create("매출", owner).name()).isEqualTo("매출");
  }

  /** 수정 요청에서 name 이 null(바꾸지 않음)이면 strip 이 null 을 유지해 이름이 그대로다. */
  @Test
  void update_nullName_keepsName() {
    ChartResponse a = create("매출", owner);
    ChartResponse updated =
        service.update(a.id(), new UpdateChartRequest(null, "설명", null, null, null), owner);
    assertThat(updated.name()).isEqualTo("매출");
  }
}
