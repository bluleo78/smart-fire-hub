package com.smartfirehub.proactive.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.global.exception.DuplicateResourceNameException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
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
 * WD-824 — 같은 소유자(+기본 양식) 안 리포트 양식 이름 중복 409.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않아 요청마다 자기 트랜잭션으로 커밋한다.
 */
class ReportTemplateDuplicateNameTest extends IntegrationTestBase {

  private static final String MSG = "이미 있는 리포트 양식 이름입니다. 다른 이름을 입력하세요.";

  @Autowired private ReportTemplateService service;
  @Autowired private DSLContext dsl;

  private Long owner;
  private Long other;

  @BeforeEach
  void setUp() {
    owner = TenantRlsTestSupport.insertUser(dsl, "wd824t_owner_");
    other = TenantRlsTestSupport.insertUser(dsl, "wd824t_other_");
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    inTenantFixture(
        () -> {
          for (Long u : List.of(owner, other)) {
            dsl.execute("DELETE FROM audit_log WHERE user_id = ?", u);
            dsl.execute("DELETE FROM report_template WHERE user_id = ?", u);
          }
        });
    TenantRlsTestSupport.deleteUser(dsl, owner);
    TenantRlsTestSupport.deleteUser(dsl, other);
  }

  private static List<Map<String, Object>> sections() {
    return List.of(Map.of("key", "summary", "label", "요약", "type", "text"));
  }

  private ReportTemplateResponse create(String name, Long userId) {
    return service.createTemplate(
        new CreateReportTemplateRequest(name, null, sections(), null), userId);
  }

  @Test
  void create_rejectsDuplicateName() {
    create("주간 양식", owner);
    assertThatThrownBy(() -> create("주간 양식", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void create_rejectsCaseAndSpaceVariant() {
    create("Weekly", owner);
    assertThatThrownBy(() -> create("  weekly ", owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  @Test
  void create_allowedWhenOtherOwnerHasSameName() {
    create("주간 양식", other);
    assertThat(create("주간 양식", owner).name()).isEqualTo("주간 양식");
  }

  @Test
  void create_rejectsBuiltinTemplateName() {
    // 기본 양식(user_id NULL)은 모두에게 보이므로 내 양식이 같은 이름을 가질 수 없다.
    assertThatThrownBy(() -> create("일간 요약 리포트", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
  }

  @Test
  void update_rename_rejectsExistingName() {
    create("A", owner);
    ReportTemplateResponse b = create("B", owner);
    assertThatThrownBy(
            () ->
                service.updateTemplate(
                    b.id(), new UpdateReportTemplateRequest("a", null, null, null), owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  // ── WD-1094: 앞뒤 유니코드 공백을 걷어낸 뒤 중복 검사·저장 (스마트 작업 WD-1085 과 같은 판정) ─────────

  private Long insertLegacy(Long userId, String name) {
    return inTenantFixture(
        () ->
            dsl.fetchOne(
                    "INSERT INTO report_template (user_id, name, sections)"
                        + " VALUES (?, ?, '[]'::jsonb) RETURNING id",
                    userId,
                    name)
                .get(0, Long.class));
  }

  /** 전각 공백·NBSP 로 감싼 이름도 같은 이름으로 본다 — SQL 의 trim 은 ASCII 공백만 걷는다. */
  /** 199자 이름 뒤에 NBSP 둘이 붙어도 걷은 길이(199자)로 @Size 를 보고 걷은 이름으로 저장된다(WD-1094). */
  @Test
  void request_stripsNameBeforeSizeValidationAndStoresStripped() {
    String body = "a".repeat(199);
    var request = new CreateReportTemplateRequest(body + "\u00A0\u00A0", null, sections(), null);
    assertThat(request.name()).isEqualTo(body);
    try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request)).isEmpty();
    }
    assertThat(create(body + "\u00A0\u00A0", owner).name()).isEqualTo(body);
  }

  /** 수정 요청은 보조 생성자로 만들어도 이름을 걷는다. */
  @Test
  void updateRequest_secondaryConstructorStripsName() {
    var request = new UpdateReportTemplateRequest("\u3000매출\u00A0", null, null, null);
    assertThat(request.name()).isEqualTo("매출");
  }

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

  /** 내장 양식(user_id NULL) 이름도 유니코드 공백으로 감싸 우회할 수 없다 — 「내 양식 OR 내장」 판정 유지. */
  @Test
  void create_rejectsUnicodePaddedBuiltinName() {
    assertThatThrownBy(() -> create("\u3000일간 요약 리포트\u3000", owner))
        .isInstanceOf(DuplicateResourceNameException.class)
        .hasMessage(MSG);
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

  /** 다른 양식의 이름에 유니코드 공백만 덧대 바꾸면 중복으로 거절된다. */
  @Test
  void update_rename_rejectsPaddedExistingName() {
    create("매출", owner);
    ReportTemplateResponse b = create("다른 양식", owner);
    assertThatThrownBy(
            () ->
                service.updateTemplate(
                    b.id(), new UpdateReportTemplateRequest("\u3000매출", null, null, null), owner))
        .isInstanceOf(DuplicateResourceNameException.class);
  }

  /** 자기 이름에 공백만 덧댄 수정은 성공하고 걷어낸 이름으로 저장된다. */
  @Test
  void update_selfNameWithPadding_succeedsAndStrips() {
    ReportTemplateResponse a = create("매출", owner);
    service.updateTemplate(
        a.id(), new UpdateReportTemplateRequest("\u3000매출\u3000", null, null, null), owner);
    assertThat(service.getTemplate(a.id(), owner).name()).isEqualTo("매출");
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
    service.updateTemplate(id, new UpdateReportTemplateRequest("매출", null, null, null), owner);
    assertThat(service.getTemplate(id, owner).name()).isEqualTo("매출");
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
    service.updateTemplate(id, new UpdateReportTemplateRequest("매출", "새 설명", null, null), owner);
    assertThat(service.getTemplate(id, owner).description()).isEqualTo("새 설명");
  }

  /** 다른 소유자의 패딩된 이름은 막지 않는다. */
  @Test
  void create_allowedWhenOtherOwnerHasUnicodePaddedName() {
    insertLegacy(other, "\u3000매출");
    assertThat(create("매출", owner).name()).isEqualTo("매출");
  }

  @Test
  void update_withoutRename_allowedOnLegacyDuplicate() {
    // 예전부터 겹친 같은 이름 행 둘을 가드를 거치지 않고 직접 넣는다.
    inTenantFixture(
        () -> {
          for (int i = 0; i < 2; i++) {
            dsl.execute(
                "INSERT INTO report_template (user_id, name, sections)"
                    + " VALUES (?, 'Dup', '[]'::jsonb)",
                owner);
          }
        });
    Long id =
        inTenantFixture(
            () ->
                dsl.fetchOne(
                        "SELECT min(id) FROM report_template WHERE user_id = ? AND name = 'Dup'",
                        owner)
                    .get(0, Long.class));

    service.updateTemplate(id, new UpdateReportTemplateRequest("Dup", "새 설명", null, null), owner);

    assertThat(service.getTemplate(id, owner).description()).isEqualTo("새 설명");
  }
}
