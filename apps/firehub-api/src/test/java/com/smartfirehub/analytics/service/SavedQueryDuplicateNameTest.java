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
            dsl.fetchOne("SELECT count(*) FROM saved_query WHERE created_by = ? AND name = ?", owner, name)
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
            id,
            new UpdateSavedQueryRequest("Dup", "새 설명", null, null, null, null, null),
            owner);

    assertThat(updated.description()).isEqualTo("새 설명");
  }

  @Test
  void clone_picksNextFreeCopyName() {
    SavedQueryResponse x = create("X", false, owner);
    assertThat(service.clone(x.id(), owner).name()).isEqualTo("X (복사본)");
    assertThat(service.clone(x.id(), owner).name()).isEqualTo("X (복사본 2)");
  }

  /**
   * 복제가 실제로 넣는 이름(2번째 이후 후보)도 잠그는지 — 「X (복사본)」 이 있을 때 복제와 「X (복사본 2)」 직접 생성이 겹쳐도
   * 그 이름은 한 행만 남아야 한다. 첫 후보만 잠그면 이 둘이 서로를 기다리지 않아 같은 이름이 둘 생길 수 있다(경합이라 확률적으로만 드러난다).
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
