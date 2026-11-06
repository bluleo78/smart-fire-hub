package com.smartfirehub.proactive.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.dto.CreateReportTemplateRequest;
import com.smartfirehub.proactive.dto.ReportTemplateResponse;
import com.smartfirehub.proactive.dto.ReportTemplateSummaryResponse;
import com.smartfirehub.proactive.dto.UpdateReportTemplateRequest;
import com.smartfirehub.proactive.service.ReportTemplateGenerationService;
import com.smartfirehub.proactive.service.ReportTemplateService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** ReportTemplateController WebMvcTest — JaCoCo LINE 커버리지 보강용. 템플릿 CRUD 엔드포인트를 커버한다. */
@WebMvcTest(ReportTemplateController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ReportTemplateControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ReportTemplateService reportTemplateService;
  // 컨트롤러가 섹션 생성 서비스(WD-455)도 주입받으므로 슬라이스에 빈이 있어야 한다.
  @MockitoBean private ReportTemplateGenerationService reportTemplateGenerationService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  private ReportTemplateResponse sampleTemplate() {
    return new ReportTemplateResponse(
        5L,
        "Daily Summary",
        "A daily summary template",
        List.of(Map.of("title", "Overview", "type", "text")),
        "default",
        1L,
        false,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  /** 목록 조회 응답(요약, #632) 샘플 — sections/style 없이 sectionCount만 포함. */
  private ReportTemplateSummaryResponse sampleTemplateSummary() {
    return new ReportTemplateSummaryResponse(
        5L,
        "Daily Summary",
        "A daily summary template",
        1,
        1L,
        false,
        LocalDateTime.now(),
        LocalDateTime.now());
  }

  @Test
  void getTemplates_returnsList() throws Exception {
    mockAuth("proactive:read");
    when(reportTemplateService.getTemplates(anyLong(), anyInt(), anyInt()))
        .thenReturn(List.of(sampleTemplateSummary()));

    mockMvc
        .perform(get("/api/v1/proactive/templates").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(5))
        .andExpect(jsonPath("$[0].name").value("Daily Summary"))
        .andExpect(jsonPath("$[0].sectionCount").value(1))
        .andExpect(jsonPath("$[0].sections").doesNotExist());
  }

  /** #632 — page/size 쿼리 파라미터가 서비스로 그대로 전달되는지 확인. */
  @Test
  void getTemplates_withPageSizeParams_passesThemToService() throws Exception {
    mockAuth("proactive:read");
    when(reportTemplateService.getTemplates(eq(1L), eq(1), eq(5))).thenReturn(List.of());

    mockMvc
        .perform(
            get("/api/v1/proactive/templates?page=1&size=5")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());
  }

  @Test
  void getTemplate_returnsTemplate() throws Exception {
    mockAuth("proactive:read");
    when(reportTemplateService.getTemplate(5L, 1L)).thenReturn(sampleTemplate());

    mockMvc
        .perform(get("/api/v1/proactive/templates/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5))
        .andExpect(jsonPath("$.name").value("Daily Summary"));
  }

  @Test
  void createTemplate_validBody_returnsCreated() throws Exception {
    mockAuth("proactive:write");
    CreateReportTemplateRequest req =
        new CreateReportTemplateRequest(
            "New Template",
            "Template description",
            List.of(Map.of("title", "Section 1", "type", "text")),
            "default");
    when(reportTemplateService.createTemplate(any(), eq(1L))).thenReturn(sampleTemplate());

    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(5));
  }

  /** 수정은 200 + 새 수정 시각 — 웹이 다음 저장의 낙관적 잠금 기준으로 쓴다(WD-364, 예전에는 204). */
  @Test
  void updateTemplate_returnsSavedUpdatedAt() throws Exception {
    mockAuth("proactive:write");
    UpdateReportTemplateRequest req =
        new UpdateReportTemplateRequest("Updated Name", null, null, null);
    when(reportTemplateService.updateTemplate(eq(5L), any(), eq(1L)))
        .thenReturn(LocalDateTime.of(2026, 10, 30, 10, 0, 0, 123_456_000));

    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.updatedAt").value("2026-10-30T10:00:00.123456"));
  }

  /**
   * 이슈 #200 회귀 테스트 — PUT 엔드포인트에 빈 이름 전송 시 400 반환 검증. UpdateReportTemplateRequest.name 에 @NotBlank
   * 검증이 없으면 성공(200)을 반환하여 빈 이름으로 저장된다.
   */
  @Test
  void updateTemplate_emptyName_returns400() throws Exception {
    mockAuth("proactive:write");
    // name이 빈 문자열인 요청 — @NotBlank 위반으로 400이어야 한다
    UpdateReportTemplateRequest req = new UpdateReportTemplateRequest("", null, null, null);

    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  /**
   * WD-501: 공백만 있는 이름으로 생성하면 영어 기본 문구("must not be blank")가 화면에 그대로 보였다 — 수정과 같은 한국어 문구를
   * errors.name 으로 돌려주고, 서비스는 부르지 않는다.
   */
  @Test
  void createTemplate_blankName_returns400WithKoreanFieldError() throws Exception {
    mockAuth("proactive:write");
    CreateReportTemplateRequest req = new CreateReportTemplateRequest("   ", null, List.of(), null);

    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.name").value("템플릿 이름은 필수입니다."));

    verify(reportTemplateService, never()).createTemplate(any(), anyLong());
  }

  /** WD-430: 200자를 넘는 이름은 DB 길이 오류로 500 이 났다 — 생성·수정 모두 400 으로 이유를 알린다. */
  @Test
  void createAndUpdateTemplate_nameOver200_returns400WithReason() throws Exception {
    mockAuth("proactive:write");
    String tooLong = "a".repeat(201);
    CreateReportTemplateRequest create =
        new CreateReportTemplateRequest(tooLong, null, List.of(), null);
    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(create)))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("이름은 200자 이내여야 합니다.")));

    UpdateReportTemplateRequest update = new UpdateReportTemplateRequest(tooLong, null, null, null);
    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("이름은 200자 이내여야 합니다.")));
  }

  @Test
  void deleteTemplate_returnsNoContent() throws Exception {
    mockAuth("proactive:write");
    doNothing().when(reportTemplateService).deleteTemplate(eq(5L), eq(1L));

    mockMvc
        .perform(
            delete("/api/v1/proactive/templates/5").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  // ── WD-425: 섹션 수·글자 항목 길이 상한 ──────────────────────────────────────

  private static final String TOO_MANY_SECTIONS = "리포트 섹션은 하위 섹션을 포함해 최대 100개까지 만들 수 있습니다.";
  private static final String TEXT_TOO_LONG =
      "리포트 섹션의 지침·내용 등 글자 항목은 항목마다 최대 10,000자까지 입력할 수 있습니다.";

  /** 텍스트 섹션 {@code count} 개. */
  private static List<Map<String, Object>> sections(int count) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(Map.of("key", "s" + i, "label", "섹션 " + i, "type", "text"));
    }
    return list;
  }

  private void expectCreate400(CreateReportTemplateRequest req, String message) throws Exception {
    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.sections").value(message));
  }

  /** WD-425: 섹션 100개 · 지침 10,000자 정확히 상한이면 생성된다. */
  @Test
  void createTemplate_sectionsAtLimit_returnsCreated() throws Exception {
    mockAuth("proactive:write");
    when(reportTemplateService.createTemplate(any(), eq(1L))).thenReturn(sampleTemplate());
    List<Map<String, Object>> list = sections(99);
    list.add(
        Map.of("key", "last", "label", "마지막", "type", "text", "instruction", "가".repeat(10_000)));

    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateReportTemplateRequest("양식", null, list, null))))
        .andExpect(status().isCreated());
  }

  /** WD-425: 섹션이 101개면 400 + 한국어 사유, 서비스는 호출되지 않는다. */
  @Test
  void createTemplate_tooManySections_returns400() throws Exception {
    mockAuth("proactive:write");
    expectCreate400(
        new CreateReportTemplateRequest("양식", null, sections(101), null), TOO_MANY_SECTIONS);
    verify(reportTemplateService, never()).createTemplate(any(), anyLong());
  }

  /** WD-425: group 하나 아래에 하위 섹션을 몰아 넣어도(최상위 1개 + 하위 100개) 전체 개수로 막는다. */
  @Test
  void createTemplate_tooManyNestedSections_returns400() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> group =
        Map.of("key", "g", "label", "그룹", "type", "group", "children", sections(100));
    expectCreate400(
        new CreateReportTemplateRequest("양식", null, List.of(group), null), TOO_MANY_SECTIONS);
    verify(reportTemplateService, never()).createTemplate(any(), anyLong());
  }

  /** WD-425: 섹션의 글자 항목(지침)이 10,000자를 넘으면 — 하위 섹션이어도 — 400. */
  @Test
  void createTemplate_sectionTextTooLong_returns400() throws Exception {
    mockAuth("proactive:write");
    Map<String, Object> child =
        Map.of("key", "c", "label", "하위", "type", "text", "instruction", "가".repeat(10_001));
    Map<String, Object> group =
        Map.of("key", "g", "label", "그룹", "type", "group", "children", List.of(child));
    expectCreate400(
        new CreateReportTemplateRequest("양식", null, List.of(group), null), TEXT_TOO_LONG);
    verify(reportTemplateService, never()).createTemplate(any(), anyLong());
  }

  /** WD-425: 정해진 글자 필드가 아닌 곳(배열)에 큰 값을 넣어도 섹션 전체 크기(512KB) 상한으로 400. */
  @Test
  void createTemplate_sectionsJsonTooLarge_returns400() throws Exception {
    mockAuth("proactive:write");
    // 원소 하나하나는 짧아(글자 항목 검사 통과) 섹션 1개지만, 합치면 512KB 를 넘는다
    List<String> junk = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      junk.add("x".repeat(1_000));
    }
    Map<String, Object> section = Map.of("key", "a", "label", "b", "type", "text", "junk", junk);
    expectCreate400(
        new CreateReportTemplateRequest("양식", null, List.of(section), null),
        "리포트 섹션 전체가 너무 큽니다(최대 512KB).");
    verify(reportTemplateService, never()).createTemplate(any(), anyLong());
  }

  /** WD-425: 수정도 같은 상한 — 섹션 101개면 400. */
  @Test
  void updateTemplate_tooManySections_returns400() throws Exception {
    mockAuth("proactive:write");
    UpdateReportTemplateRequest req =
        new UpdateReportTemplateRequest("양식", null, sections(101), null);

    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.sections").value(TOO_MANY_SECTIONS));
    verify(reportTemplateService, never()).updateTemplate(anyLong(), any(), anyLong());
  }

  // ── WD-520: 리포트 양식 설명 길이 상한 ─────────────────────────────────────────
  // 화면은 500자까지만 받지만, AI 도구·직접 호출에도 목록이 부풀지 않도록 서버는 공용 상한(2,000자)으로 막는다.

  /** 설명 상한 초과 400 응답의 한국어 사유. */
  private static final String TEMPLATE_DESCRIPTION_TOO_LONG = "리포트 양식 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** WD-520: 생성 — 설명이 정확히 2,000자면 201, 2,001자면 400 + 한국어 사유로 서비스까지 가지 않는다. */
  @Test
  void createTemplate_descriptionLimit_atLimitCreated_overLimit400() throws Exception {
    mockAuth("proactive:write");
    when(reportTemplateService.createTemplate(any(), eq(1L))).thenReturn(sampleTemplate());
    List<Map<String, Object>> sections = List.of(Map.of("title", "Section 1", "type", "text"));

    CreateReportTemplateRequest over =
        new CreateReportTemplateRequest(
            "양식", "가".repeat(DescriptionLimits.MAX_LENGTH + 1), sections, "default");
    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value(TEMPLATE_DESCRIPTION_TOO_LONG));
    verify(reportTemplateService, never()).createTemplate(any(), anyLong());

    CreateReportTemplateRequest atLimit =
        new CreateReportTemplateRequest(
            "양식", "가".repeat(DescriptionLimits.MAX_LENGTH), sections, "default");
    mockMvc
        .perform(
            post("/api/v1/proactive/templates")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(atLimit)))
        .andExpect(status().isCreated());
  }

  /** WD-520: 수정 — 2,001자는 400, 2,000자와 생략(null, 변경 없음)은 200. */
  @Test
  void updateTemplate_descriptionLimit_atLimitOk_overLimit400_nullOk() throws Exception {
    mockAuth("proactive:write");
    when(reportTemplateService.updateTemplate(eq(5L), any(), eq(1L)))
        .thenReturn(LocalDateTime.of(2026, 10, 30, 10, 0));

    UpdateReportTemplateRequest over =
        new UpdateReportTemplateRequest(
            "양식", "가".repeat(DescriptionLimits.MAX_LENGTH + 1), null, null);
    mockMvc
        .perform(
            put("/api/v1/proactive/templates/5")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value(TEMPLATE_DESCRIPTION_TOO_LONG));
    verify(reportTemplateService, never()).updateTemplate(anyLong(), any(), anyLong());

    for (String description : new String[] {"가".repeat(DescriptionLimits.MAX_LENGTH), null}) {
      UpdateReportTemplateRequest ok =
          new UpdateReportTemplateRequest("양식", description, null, null);
      mockMvc
          .perform(
              put("/api/v1/proactive/templates/5")
                  .header("Authorization", "Bearer valid-token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(ok)))
          .andExpect(status().isOk());
    }
  }
}
