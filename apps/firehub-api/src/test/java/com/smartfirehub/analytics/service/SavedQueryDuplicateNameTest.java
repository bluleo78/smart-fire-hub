package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.support.ConcurrentCalls;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * WD-824 — 같은 소유자 안 저장 쿼리 이름 중복 409.
 *
 * <p>동시 생성을 보려고 클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 요청마다 자기 트랜잭션으로 커밋해야 advisory 잠금이 서로를 기다린다.
 */
class SavedQueryDuplicateNameTest extends IntegrationTestBase {

  private static final String MSG = "이미 있는 쿼리 이름입니다. 다른 이름을 입력하세요.";

  @Autowired private SavedQueryService service;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long other;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd824_owner_");
    other = TenantRlsTestSupport.insertUser(dsl, "wd824_other_");
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
          dsl.execute("DELETE FROM saved_query WHERE created_by IN (?, ?)", owner, other);
          // 생성·수정 감사가 사용자를 FK 로 물고 있어 사용자 삭제 전에 지운다.
          dsl.execute("DELETE FROM audit_log WHERE user_id IN (?, ?)", owner, other);
        });
    for (Long uid : List.of(owner, other)) {
      TenantRlsTestSupport.deleteMembership(dsl, uid);
      dsl.execute("delete from user_role where user_id = ?", uid);
      TenantRlsTestSupport.deleteUser(dsl, uid);
    }
  }

  private SavedQueryResponse create(String name, boolean shared, Long userId) {
    return service.create(
        new CreateSavedQueryRequest(name, null, "SELECT 1", null, null, shared), userId);
  }

  private int countRows(String name) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "SELECT count(*) FROM saved_query WHERE created_by = ? AND name = ?",
                    owner,
                    name)
                .get(0, Integer.class));
  }

  @Test
  void create_rejectsDuplicateName() {
    create("Sales", false, owner);
    assertThatThrownBy(() -> create("Sales", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void create_rejectsCaseAndSpaceVariant() {
    create("Sales", false, owner);
    assertThatThrownBy(() -> create("  sales ", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void create_allowedWhenOtherOwnerHasSameName() {
    create("Sales", true, other);
    assertThat(create("Sales", false, owner).name()).isEqualTo("Sales");
  }

  @Test
  void update_rename_rejectsExistingName() {
    create("A", false, owner);
    SavedQueryResponse b = create("B", false, owner);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(),
                    new UpdateSavedQueryRequest("a", null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  // ── WD-1094: 앞뒤 유니코드 공백을 걷어낸 뒤 중복 검사·저장 (스마트 작업 WD-1085·WD-1108 과 같은 판정) ─────────

  private Long insertLegacy(Long userId, String name) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO saved_query (name, sql_text, is_shared, created_by)"
                        + " VALUES (?, 'SELECT 1', false, ?) RETURNING id",
                    name,
                    userId)
                .get(0, Long.class));
  }

  /** 전각 공백·NBSP 로 감싼 이름도 같은 이름으로 본다 — SQL 의 trim 은 ASCII 공백만 걷는다. */
  @Test
  void create_rejectsUnicodePaddedDuplicate() {
    create("매출", false, owner);
    assertThatThrownBy(() -> create("\u3000매출", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(() -> create("매출 ", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(() -> create("\u00A0매출\u00A0", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 안쪽 전각 공백만 다른 이름도 같은 이름이다(translate). */
  @Test
  void create_rejectsInnerUnicodeSpaceVariant() {
    create("매 출", false, owner);
    assertThatThrownBy(() -> create("매\u3000출", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 저장되는 이름은 앞뒤 공백이 걷힌 값이다. */
  @Test
  void create_storesStrippedName() {
    assertThat(create("\u3000매출 ", false, owner).name()).isEqualTo("매출");
  }

  /** 다른 쿼리의 이름에 유니코드 공백만 덧대 바꾸면 중복으로 거절된다. */
  @Test
  void update_rename_rejectsPaddedExistingName() {
    create("매출", false, owner);
    SavedQueryResponse b = create("다른 쿼리", false, owner);
    assertThatThrownBy(
            () ->
                service.update(
                    b.id(),
                    new UpdateSavedQueryRequest("\u3000매출", null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 자기 이름에 공백만 덧댄 수정은 성공하고 걷어낸 이름으로 저장된다. */
  @Test
  void update_selfNameWithPadding_succeedsAndStrips() {
    SavedQueryResponse a = create("매출", false, owner);
    SavedQueryResponse updated =
        service.update(
            a.id(),
            new UpdateSavedQueryRequest("\u3000매출\u3000", null, null, null, null, null, null),
            owner);
    assertThat(updated.name()).isEqualTo("매출");
  }

  /** 옛 행 이름이 전각 공백으로 감싸져 있어도 같은 이름 생성을 막는다. */
  @Test
  void create_rejectsWhenLegacyRowHasUnicodePadding() {
    insertLegacy(owner, "매출\u3000");
    assertThatThrownBy(() -> create("매출", false, owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 옛(패딩된) 행 자신을 걷어낸 이름으로 고치는 수정은 막히지 않는다 — 자기 자신은 비교에서 제외. */
  @Test
  void update_selfLegacyPaddedRow_notBlocked() {
    Long id = insertLegacy(owner, "매출\u3000");
    SavedQueryResponse updated =
        service.update(
            id, new UpdateSavedQueryRequest("매출", null, null, null, null, null, null), owner);
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
    SavedQueryResponse updated =
        service.update(
            id, new UpdateSavedQueryRequest("매출", "새 설명", null, null, null, null, null), owner);
    assertThat(updated.description()).isEqualTo("새 설명");
  }

  /** 다른 소유자의 패딩된 이름은 막지 않는다. */
  @Test
  void create_allowedWhenOtherOwnerHasUnicodePaddedName() {
    insertLegacy(other, "\u3000매출");
    assertThat(create("매출", false, owner).name()).isEqualTo("매출");
  }

  /** 수정 요청에서 name 이 null(바꾸지 않음)이면 strip 이 null 을 유지해 이름이 그대로다. */
  @Test
  void update_nullName_keepsName() {
    SavedQueryResponse a = create("매출", false, owner);
    SavedQueryResponse updated =
        service.update(
            a.id(), new UpdateSavedQueryRequest(null, "설명", null, null, null, null, null), owner);
    assertThat(updated.name()).isEqualTo("매출");
  }

  @Test
  void update_withoutRename_allowedOnLegacyDuplicate() {
    // 예전부터 겹친 같은 이름 행 둘을 저장소로 직접 넣는다(가드를 거치지 않는다).
    inTenantFixture(
        () -> {
          dsl.execute(
              "INSERT INTO saved_query (name, sql_text, is_shared, created_by) VALUES ('Dup', 'SELECT 1', false, ?)",
              owner);
          dsl.execute(
              "INSERT INTO saved_query (name, sql_text, is_shared, created_by) VALUES ('Dup', 'SELECT 1', false, ?)",
              owner);
        });
    Long id =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT min(id) FROM saved_query WHERE created_by = ? AND name = 'Dup'",
                        owner)
                    .get(0, Long.class));

    SavedQueryResponse updated =
        service.update(
            id, new UpdateSavedQueryRequest("Dup", "새 설명", null, null, null, null, null), owner);

    assertThat(updated.description()).isEqualTo("새 설명");
  }

  /**
   * 앞에 전각 공백이 붙은 옛 행을 복제하면 걷어낸 이름 기준으로 「 (복사본)」 을 붙이고, 이미 있으면 다음 번호를 고른다(WD-1094). 검사한 이름과 넣는 이름이
   * 같아야 한다.
   */
  @Test
  void clone_legacyPaddedRow_usesStrippedName() {
    Long legacyId = insertLegacy(owner, "\u3000매출");
    assertThat(service.clone(legacyId, owner).name()).isEqualTo("매출 (복사본)");
    assertThat(service.clone(legacyId, owner).name()).isEqualTo("매출 (복사본 2)");
  }

  /**
   * 200자 한도로 원래 이름을 잘라 맞출 때도 걷어낸 이름을 잘라야 한다 — 패딩 채로 잘라 검사하면 넣는 이름(걷은 뒤)과 검사한 이름이 어긋나 글자 수가
   * 달라진다(WD-1094).
   */
  @Test
  void clone_legacyPaddedLongName_truncatesStrippedName() {
    String body = "a".repeat(199);
    Long legacyId = insertLegacy(owner, "\u3000" + body);
    String expected = "a".repeat(194) + " (복사본)";
    assertThat(service.clone(legacyId, owner).name()).isEqualTo(expected);
  }

  @Test
  void clone_picksNextFreeCopyName() {
    SavedQueryResponse x = create("X", false, owner);
    assertThat(service.clone(x.id(), owner).name()).isEqualTo("X (복사본)");
    assertThat(service.clone(x.id(), owner).name()).isEqualTo("X (복사본 2)");
  }

  /**
   * 복제가 실제로 넣는 이름(2번째 이후 후보)도 잠그는지 — 「X (복사본)」 이 있을 때 복제와 「X (복사본 2)」 직접 생성이 겹쳐도 그 이름은 한 행만 남아야 한다.
   * 첫 후보만 잠그면 이 둘이 서로를 기다리지 않아 같은 이름이 둘 생길 수 있다(경합이라 확률적으로만 드러난다).
   */
  @Test
  void clone_locksTheCandidateItActuallyInserts() throws Exception {
    SavedQueryResponse x = create("X", false, owner);
    create("X (복사본)", false, owner);
    for (int round = 0; round < 5; round++) {
      AtomicInteger turn = new AtomicInteger();
      ConcurrentCalls.run(
          2,
          DEFAULT_TEST_TENANT_ID,
          () -> {
            if (turn.getAndIncrement() == 0) {
              service.clone(x.id(), owner);
            } else {
              create("X (복사본 2)", false, owner);
            }
          });
      assertThat(countRows("X (복사본 2)")).isEqualTo(1);
      inTenantFixture(
          () ->
              dsl.execute(
                  "DELETE FROM saved_query WHERE created_by = ? AND name LIKE 'X (복사본 %'", owner));
    }
  }

  @Test
  void concurrentCreate_onlyOneAccepted() throws Exception {
    List<Throwable> results =
        ConcurrentCalls.run(2, DEFAULT_TEST_TENANT_ID, () -> create("Race", false, owner));

    assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
    assertThat(results.stream().filter(Objects::nonNull).toList())
        .hasSize(1)
        .allMatch(DuplicateResourceNameException.class::isInstance);
    assertThat(countRows("Race")).isEqualTo(1);
  }
}
