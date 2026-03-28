package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ReportTemplateServiceTest extends IntegrationTestBase {

  @Autowired private ReportTemplateService reportTemplateService;
  @Autowired private DSLContext dsl;

  private Long testUserId;

  @BeforeEach
  void setUp() {
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "template_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Template Test User")
            .set(USER.EMAIL, "template_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void getTemplates_returnsBuiltinThree() {
    List<ReportTemplateResponse> templates = reportTemplateService.getTemplates(testUserId);

    long builtinCount = templates.stream().filter(ReportTemplateResponse::builtin).count();
    assertThat(builtinCount).isGreaterThanOrEqualTo(3);
  }

  @Test
  void createTemplate_thenFindById_success() {
    // given
    List<Map<String, Object>> sections =
        List.of(
            Map.of("key", "summary", "label", "요약", "required", true),
            Map.of("key", "details", "label", "상세"));
    CreateReportTemplateRequest request =
        new CreateReportTemplateRequest("테스트 템플릿", "테스트 설명", sections);

    // when
    ReportTemplateResponse created = reportTemplateService.createTemplate(request, testUserId);

    // then
    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("테스트 템플릿");
    assertThat(created.description()).isEqualTo("테스트 설명");
    assertThat(created.builtin()).isFalse();

    // findById
    ReportTemplateResponse found = reportTemplateService.getTemplate(created.id());
    assertThat(found.name()).isEqualTo("테스트 템플릿");
  }

  @Test
  void updateTemplate_customTemplate_success() {
    // given: create custom template
    CreateReportTemplateRequest createReq =
        new CreateReportTemplateRequest(
            "수정 전 이름", null, List.of(Map.of("key", "summary", "label", "요약")));
    ReportTemplateResponse created = reportTemplateService.createTemplate(createReq, testUserId);

    // when: update
    UpdateReportTemplateRequest updateReq =
        new UpdateReportTemplateRequest(
            "수정 후 이름",
            "새 설명",
            List.of(
                Map.of("key", "summary", "label", "요약"), Map.of("key", "detail", "label", "상세")));
    reportTemplateService.updateTemplate(created.id(), updateReq, testUserId);

    // then
    ReportTemplateResponse updated = reportTemplateService.getTemplate(created.id());
    assertThat(updated.name()).isEqualTo("수정 후 이름");
    assertThat(updated.description()).isEqualTo("새 설명");
  }

  @Test
  void deleteTemplate_customTemplate_success() {
    // given: create custom template
    CreateReportTemplateRequest createReq =
        new CreateReportTemplateRequest("삭제될 템플릿", null, List.of(Map.of("key", "s", "label", "S")));
    ReportTemplateResponse created = reportTemplateService.createTemplate(createReq, testUserId);
    Long id = created.id();

    // when: delete
    reportTemplateService.deleteTemplate(id, testUserId);

    // then: not found
    assertThatThrownBy(() -> reportTemplateService.getTemplate(id))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("템플릿을 찾을 수 없습니다");
  }

  @Test
  void deleteTemplate_builtinTemplate_throwsProactiveJobException() {
    List<ReportTemplateResponse> templates = reportTemplateService.getTemplates(testUserId);
    ReportTemplateResponse builtin =
        templates.stream().filter(ReportTemplateResponse::builtin).findFirst().orElseThrow();

    assertThatThrownBy(() -> reportTemplateService.deleteTemplate(builtin.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("빌트인 템플릿은 삭제할 수 없습니다");
  }

  @Test
  void updateTemplate_builtinTemplate_throwsProactiveJobException() {
    List<ReportTemplateResponse> templates = reportTemplateService.getTemplates(testUserId);
    ReportTemplateResponse builtin =
        templates.stream().filter(ReportTemplateResponse::builtin).findFirst().orElseThrow();

    UpdateReportTemplateRequest updateReq = new UpdateReportTemplateRequest("수정 시도", null, null);

    assertThatThrownBy(
            () -> reportTemplateService.updateTemplate(builtin.id(), updateReq, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("빌트인 템플릿은 수정할 수 없습니다");
  }
}
