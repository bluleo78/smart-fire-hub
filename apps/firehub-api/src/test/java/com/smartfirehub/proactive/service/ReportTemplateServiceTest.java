package com.smartfirehub.proactive.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.exception.ProactiveJobException;
import com.smartfirehub.proactive.exception.ProactiveJobNotFoundException;
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
    List<ReportTemplateSummaryResponse> templates =
        reportTemplateService.getTemplates(testUserId, 0, 50);

    long builtinCount = templates.stream().filter(ReportTemplateSummaryResponse::builtin).count();
    assertThat(builtinCount).isGreaterThanOrEqualTo(3);
  }

  /**
   * #632 회귀 테스트 — 목록 응답이 sections/style 전체 JSONB 대신 섹션 개수(sectionCount)만 포함하는지, 그리고 size 파라미터가 실제로
   * 결과 건수를 제한하는지 확인한다. 공유 test DB에 누적된 다른 워크트리의 템플릿까지 셀 수 있으므로(#394와 동일한 함정) 이 테스트가 직접 생성한 템플릿의
   * sectionCount 값 자체를 단언하고, size 제한은 "요청한 size 이하로 반환되는가"로 검증한다(전역 카운트에 의존하지 않음).
   */
  @Test
  void getTemplates_summaryExcludesSectionsAndRespectsSize() {
    // given: 섹션 3개짜리 커스텀 템플릿 생성
    List<Map<String, Object>> sections =
        List.of(
            Map.of("key", "a", "label", "A"),
            Map.of("key", "b", "label", "B"),
            Map.of("key", "c", "label", "C"));
    CreateReportTemplateRequest createReq =
        new CreateReportTemplateRequest("#632 sectionCount 검증용", null, sections, null);
    ReportTemplateResponse created = reportTemplateService.createTemplate(createReq, testUserId);

    // when
    List<ReportTemplateSummaryResponse> templates =
        reportTemplateService.getTemplates(testUserId, 0, 50);
    ReportTemplateSummaryResponse summary =
        templates.stream().filter(t -> t.id().equals(created.id())).findFirst().orElseThrow();

    // then: sectionCount가 DB에서 정확히 계산됨 (전체 sections JSONB는 응답에 없음 — 레코드에 필드 자체가 없음)
    assertThat(summary.sectionCount()).isEqualTo(3);
    assertThat(summary.name()).isEqualTo("#632 sectionCount 검증용");

    // when: size=1로 재조회 — 실제 데이터가 2건 이상 존재하는 상태에서 결과가 1건으로 제한되는지 확인
    List<ReportTemplateSummaryResponse> limited =
        reportTemplateService.getTemplates(testUserId, 0, 1);
    assertThat(limited).hasSize(1);
  }

  @Test
  void createTemplate_thenFindById_success() {
    // given
    List<Map<String, Object>> sections =
        List.of(
            Map.of("key", "summary", "label", "요약", "required", true),
            Map.of("key", "details", "label", "상세"));
    CreateReportTemplateRequest request =
        new CreateReportTemplateRequest("테스트 템플릿", "테스트 설명", sections, null);

    // when
    ReportTemplateResponse created = reportTemplateService.createTemplate(request, testUserId);

    // then
    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("테스트 템플릿");
    assertThat(created.description()).isEqualTo("테스트 설명");
    assertThat(created.builtin()).isFalse();

    // findById
    ReportTemplateResponse found = reportTemplateService.getTemplate(created.id(), testUserId);
    assertThat(found.name()).isEqualTo("테스트 템플릿");
  }

  @Test
  void updateTemplate_customTemplate_success() {
    // given: create custom template
    CreateReportTemplateRequest createReq =
        new CreateReportTemplateRequest(
            "수정 전 이름", null, List.of(Map.of("key", "summary", "label", "요약")), null);
    ReportTemplateResponse created = reportTemplateService.createTemplate(createReq, testUserId);

    // when: update
    UpdateReportTemplateRequest updateReq =
        new UpdateReportTemplateRequest(
            "수정 후 이름",
            "새 설명",
            List.of(
                Map.of("key", "summary", "label", "요약"), Map.of("key", "detail", "label", "상세")),
            null);
    reportTemplateService.updateTemplate(created.id(), updateReq, testUserId);

    // then
    ReportTemplateResponse updated = reportTemplateService.getTemplate(created.id(), testUserId);
    assertThat(updated.name()).isEqualTo("수정 후 이름");
    assertThat(updated.description()).isEqualTo("새 설명");
  }

  /**
   * [WD-135] 설명·작성 스타일을 빈 값으로 보내면 지워지고(NULL), 빠뜨리면(null) 그대로 둔다.
   *
   * <p>화면은 비운 값을 빈 문자열로 보내고, AI 도구는 바꿀 필드만 보낸다 — 두 뜻을 모두 지켜야 한다.
   */
  @Test
  void updateTemplate_blankClearsDescriptionAndStyle_nullKeeps() {
    List<Map<String, Object>> sections = List.of(Map.of("key", "summary", "label", "요약"));
    ReportTemplateResponse created =
        reportTemplateService.createTemplate(
            new CreateReportTemplateRequest("비우기 양식", "테스트 설명", sections, "간결하게"), testUserId);

    // 빠뜨리면(null) 그대로
    reportTemplateService.updateTemplate(
        created.id(), new UpdateReportTemplateRequest("이름만 변경", null, null, null), testUserId);
    ReportTemplateResponse kept = reportTemplateService.getTemplate(created.id(), testUserId);
    assertThat(kept.name()).isEqualTo("이름만 변경");
    assertThat(kept.description()).isEqualTo("테스트 설명");
    assertThat(kept.style()).isEqualTo("간결하게");

    // 빈 값·공백이면 지워진다
    reportTemplateService.updateTemplate(
        created.id(), new UpdateReportTemplateRequest("이름만 변경", "", sections, "  "), testUserId);
    ReportTemplateResponse cleared = reportTemplateService.getTemplate(created.id(), testUserId);
    assertThat(cleared.description()).isNull();
    assertThat(cleared.style()).isNull();
  }

  /** [WD-135] 새 양식도 빈 설명·작성 스타일은 NULL 로 저장한다. */
  @Test
  void createTemplate_blankDescriptionAndStyle_storedAsNull() {
    ReportTemplateResponse created =
        reportTemplateService.createTemplate(
            new CreateReportTemplateRequest(
                "빈 값 양식", "", List.of(Map.of("key", "summary", "label", "요약")), ""),
            testUserId);
    assertThat(created.description()).isNull();
    assertThat(created.style()).isNull();
  }

  @Test
  void deleteTemplate_customTemplate_success() {
    // given: create custom template
    CreateReportTemplateRequest createReq =
        new CreateReportTemplateRequest(
            "삭제될 템플릿", null, List.of(Map.of("key", "s", "label", "S")), null);
    ReportTemplateResponse created = reportTemplateService.createTemplate(createReq, testUserId);
    Long id = created.id();

    // when: delete
    reportTemplateService.deleteTemplate(id, testUserId);

    // then: not found
    assertThatThrownBy(() -> reportTemplateService.getTemplate(id, testUserId))
        .isInstanceOf(ProactiveJobNotFoundException.class)
        .hasMessageContaining("템플릿을 찾을 수 없습니다");
  }

  @Test
  void deleteTemplate_builtinTemplate_throwsProactiveJobException() {
    List<ReportTemplateSummaryResponse> templates =
        reportTemplateService.getTemplates(testUserId, 0, 50);
    ReportTemplateSummaryResponse builtin =
        templates.stream().filter(ReportTemplateSummaryResponse::builtin).findFirst().orElseThrow();

    assertThatThrownBy(() -> reportTemplateService.deleteTemplate(builtin.id(), testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("빌트인 템플릿은 삭제할 수 없습니다");
  }

  @Test
  void updateTemplate_builtinTemplate_throwsProactiveJobException() {
    List<ReportTemplateSummaryResponse> templates =
        reportTemplateService.getTemplates(testUserId, 0, 50);
    ReportTemplateSummaryResponse builtin =
        templates.stream().filter(ReportTemplateSummaryResponse::builtin).findFirst().orElseThrow();

    UpdateReportTemplateRequest updateReq =
        new UpdateReportTemplateRequest("수정 시도", null, null, null);

    assertThatThrownBy(
            () -> reportTemplateService.updateTemplate(builtin.id(), updateReq, testUserId))
        .isInstanceOf(ProactiveJobException.class)
        .hasMessageContaining("빌트인 템플릿은 수정할 수 없습니다");
  }
}
