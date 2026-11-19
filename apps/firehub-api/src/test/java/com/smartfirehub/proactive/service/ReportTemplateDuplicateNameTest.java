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

    service.updateTemplate(
        id, new UpdateReportTemplateRequest("Dup", "새 설명", null, null), owner);

    assertThat(service.getTemplate(id, owner).description()).isEqualTo("새 설명");
  }
}
