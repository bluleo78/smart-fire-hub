package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.CreateProactiveJobRequest;
import com.smartfirehub.proactive.dto.ProactiveJobResponse;
import com.smartfirehub.proactive.dto.UpdateProactiveJobRequest;
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
 * WD-824 — 같은 소유자 안 스마트 작업 이름 중복 409.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않아 요청마다 자기 트랜잭션으로 커밋한다. 작업은 cron 없는 이상 탐지(ANOMALY) 작업으로 만들어
 * 스케줄러에 등록되지 않게 한다.
 */
class ProactiveJobDuplicateNameTest extends IntegrationTestBase {

  private static final String MSG = "이미 있는 스마트 작업 이름입니다. 다른 이름을 입력하세요.";

  @Autowired private ProactiveJobService service;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long other;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd824j_owner_");
    other = TenantRlsTestSupport.insertUser(dsl, "wd824j_other_");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    inTenantFixture(
        () -> {
          for (Long u : List.of(owner, other)) {
            dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u);
            dsl.execute("DELETE FROM proactive_job WHERE user_id = ?", u);
          }
        });
    TenantRlsTestSupport.deleteUser(dsl, owner);
    TenantRlsTestSupport.deleteUser(dsl, other);
  }

  private ProactiveJobResponse create(String name, Long userId) {
    return service.createJob(
        new CreateProactiveJobRequest(name, "프롬프트", null, null, null, false, "ANOMALY", Map.of()),
        userId);
  }

  @Test
  void create_rejectsDuplicateName() {
    create("일일 점검", owner);
    assertThatThrownBy(() -> create("일일 점검", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void create_rejectsCaseAndSpaceVariant() {
    create("Daily Check", owner);
    assertThatThrownBy(() -> create("  daily check ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void create_allowedWhenOtherOwnerHasSameName() {
    create("일일 점검", other);
    assertThat(create("일일 점검", owner).name()).isEqualTo("일일 점검");
  }

  @Test
  void update_rename_rejectsExistingName() {
    create("A", owner);
    ProactiveJobResponse b = create("B", owner);
    assertThatThrownBy(
            () ->
                service.updateJob(
                    b.id(),
                    new UpdateProactiveJobRequest("a", null, null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void update_withoutRename_allowedOnLegacyDuplicate() {
    // 예전부터 겹친 같은 이름 행 둘을 가드를 거치지 않고 직접 넣는다.
    inTenantFixture(
        () -> {
          for (int i = 0; i < 2; i++) {
            dsl.execute(
                "INSERT INTO proactive_job (user_id, name, prompt, enabled, trigger_type, config)"
                    + " VALUES (?, 'Dup', 'p', false, 'ANOMALY', '{}'::jsonb)",
                owner);
          }
        });
    Long id =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT min(id) FROM proactive_job WHERE user_id = ? AND name = 'Dup'",
                        owner)
                    .get(0, Long.class));

    service.updateJob(
        id,
        new UpdateProactiveJobRequest("Dup", "새 프롬프트", null, null, null, null, null, null),
        owner);

    assertThat(service.getJob(id, owner).prompt()).isEqualTo("새 프롬프트");
  }

  // ── WD-1085: 앞뒤 유니코드 공백을 걷어낸 뒤 중복 검사·저장 ─────────────────────────────────

  /** 전각 공백(U+3000)·NBSP 로 감싼 이름도 같은 이름으로 본다 — SQL 의 trim 은 ASCII 공백만 걷는다. */
  @Test
  void create_rejectsUnicodePaddedDuplicate() {
    create("주간 보고", owner);
    assertThatThrownBy(() -> create("\u3000주간 보고 ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(() -> create("\u00A0주간 보고\u00A0", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 안쪽 전각 공백만 다른 이름도 같은 이름이다 — SQL translate 와 잠금 키가 같은 기준(WD-1108, WD-1129 와 같음). */
  @Test
  void create_rejectsInnerUnicodeSpaceVariant() {
    create("주간 보고", owner);
    assertThatThrownBy(() -> create("주간\u3000보고", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 저장되는 이름은 앞뒤 공백이 걷힌 값이다. */
  @Test
  void create_storesStrippedName() {
    assertThat(create("  새 작업  ", owner).name()).isEqualTo("새 작업");
    assertThat(create("\u3000전각 작업\u3000", owner).name()).isEqualTo("전각 작업");
  }

  /** 다른 작업의 이름에 NBSP 만 덧대 바꾸면 중복으로 거절된다(WD-1085). */
  @Test
  void update_rename_rejectsPaddedExistingName() {
    create("주간 보고", owner);
    ProactiveJobResponse b = create("다른 작업", owner);
    assertThatThrownBy(
            () ->
                service.updateJob(
                    b.id(),
                    new UpdateProactiveJobRequest(
                        "\u3000주간 보고", null, null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    assertThatThrownBy(
            () ->
                service.updateJob(
                    b.id(),
                    new UpdateProactiveJobRequest(
                        " 주간 보고", null, null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 자기 이름에 공백만 덧댄 수정은 성공하고 걷어낸 이름으로 저장된다. */
  @Test
  void update_selfNameWithPadding_succeedsAndStrips() {
    ProactiveJobResponse a = create("주간 보고", owner);
    service.updateJob(
        a.id(),
        new UpdateProactiveJobRequest(
            "\u3000주간 보고\u3000", null, null, null, null, null, null, null),
        owner);
    assertThat(service.getJob(a.id(), owner).name()).isEqualTo("주간 보고");
  }

  // ── WD-1108: 옛 행의 앞뒤 유니코드 공백 — SQL 비교가 ASCII 공백만 걷어 놓치던 것 ─────────────────

  private Long insertLegacy(Long userId, String name) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO proactive_job (user_id, name, prompt, enabled, trigger_type, config)"
                        + " VALUES (?, ?, 'p', false, 'ANOMALY', '{}'::jsonb) RETURNING id",
                    userId,
                    name)
                .get(0, Long.class));
  }

  /** 옛 행 이름이 전각·NBSP 로 감싸져 있어도 같은 이름 생성을 막는다(WD-1108). */
  @Test
  void create_rejectsWhenLegacyRowHasUnicodePadding() {
    insertLegacy(owner, "\u3000주간 보고");
    assertThatThrownBy(() -> create("주간 보고", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
    insertLegacy(owner, "NBSP 작업\u00A0");
    assertThatThrownBy(() -> create("NBSP 작업", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 이름을 안 바꾼 수정은 앞뒤 유니코드 공백이 붙은 옛 행이어도 막지 않는다(WD-1108). */
  @Test
  void update_promptOnlyOnUnicodePaddedLegacyRow_notBlocked() {
    Long id1 = insertLegacy(owner, "\u3000주간 보고");
    insertLegacy(owner, "주간 보고");

    service.updateJob(
        id1,
        new UpdateProactiveJobRequest("\u3000주간 보고", "새 프롬프트", null, null, null, null, null, null),
        owner);

    assertThat(service.getJob(id1, owner).prompt()).isEqualTo("새 프롬프트");
  }

  /** 다른 작업의 옛(전각 패딩) 이름으로 개명하면 중복으로 거절한다(WD-1108). */
  @Test
  void update_renameToUnicodeVariantOfOtherJob_rejected() {
    Long id1 = insertLegacy(owner, "보고 A");
    insertLegacy(owner, "\u3000주간 보고");

    assertThatThrownBy(
            () ->
                service.updateJob(
                    id1,
                    new UpdateProactiveJobRequest(
                        "주간 보고", null, null, null, null, null, null, null),
                    owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 다른 사용자의 패딩된 이름은 막지 않는다(WD-1108). */
  @Test
  void create_allowedWhenOtherOwnerHasUnicodePaddedName() {
    insertLegacy(other, "\u3000주간 보고");
    assertThat(create("주간 보고", owner).name()).isEqualTo("주간 보고");
  }
}
