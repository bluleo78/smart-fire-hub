package com.smartfirehub.dataset.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchService;
import com.smartfirehub.dataset.service.ApiImportService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetFavoriteService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.exception.GlobalExceptionHandler;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.security.PermissionInterceptor;
import com.smartfirehub.global.validation.DescriptionLimits;
import com.smartfirehub.global.validation.SearchQueryLimits;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SuppressWarnings("null")
@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DatasetControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  // 컨트롤러가 검색 가시성 자격을 위해 ClearanceResolver 를 주입받는다 — 슬라이스에선 목(서비스도 목이라 값은 쓰이지 않는다).
  @MockitoBean private ClearanceResolver clearanceResolver;

  // 행 조회 감사(스펙 §4.6) — DatasetController 의존성이라 슬라이스 컨텍스트에 mock 등록
  @MockitoBean
  private com.smartfirehub.securitylevel.service.SecurityAuditRecorder securityAuditRecorder;

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private DatasetService datasetService;

  @MockitoBean private DatasetDataService datasetDataService;

  @MockitoBean private DatasetFavoriteService datasetFavoriteService;

  @MockitoBean private DatasetTagService datasetTagService;

  @MockitoBean private ApiImportService apiImportService;

  @MockitoBean private DatasetSearchService datasetSearchService;

  @MockitoBean private DatasetEmbeddingBackfillService datasetEmbeddingBackfillService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L))
        .thenReturn(
            Set.of(
                "dataset:read",
                "dataset:write",
                "dataset:delete",
                "data:read",
                "data:import",
                "data:delete",
                "pipeline:write"));
  }

  @Test
  void getDatasets_withPermission_returnsPageResponse() throws Exception {
    CategoryResponse category = new CategoryResponse(1L, "Sales", "Sales data");
    DatasetResponse dataset =
        new DatasetResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            category,
            "TABLE",
            "SOURCE",
            LocalDateTime.now(),
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            null);
    PageResponse<DatasetResponse> page = new PageResponse<>(List.of(dataset), 0, 20, 1, 1);

    when(datasetService.getDatasets(
            any(), any(), any(), any(), anyInt(), anyInt(), anyLong(), any(), anyBoolean(), any()))
        .thenReturn(page);

    mockMvc
        .perform(get("/api/v1/datasets").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content[0].name").value("Orders"))
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void createDataset_withPermission_returnsCreated() throws Exception {
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Orders", "orders", "Order data", 1L, "TABLE", "SOURCE", List.of(), null);
    DatasetDetailResponse detail =
        new DatasetDetailResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            new CategoryResponse(1L, "Sales", "Sales data"),
            "TABLE",
            "SOURCE",
            "testuser",
            List.of(),
            0L,
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser",
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            List.of(),
            null);

    when(datasetService.createDataset(any(CreateDatasetRequest.class), anyLong()))
        .thenReturn(detail);

    mockMvc
        .perform(
            post("/api/v1/datasets")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Orders"))
        .andExpect(jsonPath("$.tableName").value("orders"));
  }

  @Test
  void getDatasetById_withPermission_returnsDetail() throws Exception {
    DatasetDetailResponse detail =
        new DatasetDetailResponse(
            1L,
            "Orders",
            "orders",
            "Order data",
            new CategoryResponse(1L, "Sales", "Sales data"),
            "TABLE",
            "SOURCE",
            "testuser",
            List.of(),
            100L,
            LocalDateTime.now(),
            LocalDateTime.now(),
            "testuser",
            false,
            List.of(),
            "ACTIVE",
            null,
            null,
            null,
            List.of(),
            null);

    when(datasetService.getDatasetById(1L, 1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/v1/datasets/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.rowCount").value(100));
  }

  @Test
  void updateDataset_withPermission_returnsNoContent() throws Exception {
    UpdateDatasetRequest request =
        new UpdateDatasetRequest("Orders Updated", "Updated description", 1L);

    mockMvc
        .perform(
            put("/api/v1/datasets/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    verify(datasetService).updateDataset(eq(1L), any(UpdateDatasetRequest.class), eq(1L));
  }

  @Test
  void deleteDataset_withPermission_returnsNoContent() throws Exception {
    mockMvc
        .perform(delete("/api/v1/datasets/1").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());

    verify(datasetService).deleteDataset(1L);
  }

  @Test
  void getDatasets_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/datasets")).andExpect(status().isUnauthorized());
  }

  @Test
  void searchDatasets_withPermission_returnsHits() throws Exception {
    // 검색 서비스는 스텁 — 컨트롤러가 요청 body 를 받아 결과 배열을 그대로 반환하는지 검증
    when(datasetSearchService.search(any(), any()))
        .thenReturn(
            List.of(
                new DatasetSearchHit(42L, "화재 출동", "설명", "TABLE", "SOURCE", "fire", "안전", 0.9)));

    mockMvc
        .perform(
            post("/api/v1/datasets/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"화재\",\"mode\":\"HYBRID\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].datasetId").value(42))
        .andExpect(jsonPath("$[0].storageType").value("TABLE"));
  }

  @Test
  void searchDatasets_queryOverMaxLength_returns400WithoutCallingService() throws Exception {
    // WD-600: 1,001자 질의는 임베딩 provider·키워드 SQL 로 가기 전에 400(한국어 문구) — 서비스 미호출.
    String query = "가".repeat(SearchQueryLimits.MAX_QUERY_LENGTH + 1);
    mockMvc
        .perform(
            post("/api/v1/datasets/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.query").value(SearchQueryLimits.QUERY_TOO_LONG));
    verify(datasetSearchService, never()).search(any(), any());
  }

  @Test
  void searchDatasets_queryAtMaxLength_passesToService() throws Exception {
    // WD-600 경계값: 정확히 1,000자는 통과해 서비스로 전달된다.
    when(datasetSearchService.search(any(), any())).thenReturn(List.of());
    String query = "가".repeat(SearchQueryLimits.MAX_QUERY_LENGTH);
    mockMvc
        .perform(
            post("/api/v1/datasets/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("query", query))))
        .andExpect(status().isOk());
    verify(datasetSearchService).search(any(), any());
  }

  @Test
  void backfillEmbeddings_withPermission_returnsAcceptedWithScheduledCount() throws Exception {
    // dataset:write 권한으로 백필 트리거 시 202 Accepted + {"scheduled": N} 반환, 서비스 호출 검증
    when(datasetEmbeddingBackfillService.backfillAll()).thenReturn(3);

    mockMvc
        .perform(
            post("/api/v1/datasets/embedding/backfill")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.scheduled").value(3));

    verify(datasetEmbeddingBackfillService).backfillAll();
  }

  @Test
  void getReferences_returnsAggregatedCounts() throws Exception {
    DatasetReferencesResponse response =
        new DatasetReferencesResponse(
            1L,
            List.of(new DatasetReferencesResponse.ReferenceItem(10L, "Pipeline A")),
            List.of(new DatasetReferencesResponse.ReferenceItem(10L, "Pipeline A")),
            List.of(new DatasetReferencesResponse.ReferenceItem(20L, "Dashboard B")),
            1,
            List.of(),
            List.of(
                new DatasetReferencesResponse.TriggerReferenceItem(
                    30L, "Trigger C", 10L, "Pipeline A")),
            4);

    when(datasetService.getReferences(eq(1L), anyLong())).thenReturn(response);

    mockMvc
        .perform(get("/api/v1/datasets/1/references").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.datasetId").value(1))
        .andExpect(jsonPath("$.pipelines[0].id").value(10))
        .andExpect(jsonPath("$.pipelines[0].name").value("Pipeline A"))
        .andExpect(jsonPath("$.mergeOutputPipelines[0].name").value("Pipeline A"))
        .andExpect(jsonPath("$.dashboards[0].id").value(20))
        .andExpect(jsonPath("$.dashboards[0].name").value("Dashboard B"))
        .andExpect(jsonPath("$.hiddenDashboardCount").value(1))
        .andExpect(jsonPath("$.proactiveJobs").isArray())
        .andExpect(jsonPath("$.triggers[0].id").value(30))
        .andExpect(jsonPath("$.triggers[0].name").value("Trigger C"))
        .andExpect(jsonPath("$.triggers[0].pipelineId").value(10))
        .andExpect(jsonPath("$.triggers[0].pipelineName").value("Pipeline A"))
        .andExpect(jsonPath("$.totalCount").value(4));
  }

  @Test
  void getReferences_nonexistentDataset_returns404() throws Exception {
    when(datasetService.getReferences(eq(999L), anyLong()))
        .thenThrow(new DatasetNotFoundException());

    mockMvc
        .perform(
            get("/api/v1/datasets/999/references").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNotFound())
        // WD-240: 영어 「Dataset not found: N」 대신 한국어·번호 없는 문구가 응답 message 로 나간다
        .andExpect(jsonPath("$.message").value("데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."));
  }

  /**
   * WD-251: pipeline:write 만 가진 사용자가 즉시 실행을 요청하면 서비스가 던진 권한 거부가 인터셉터와 같은 403·한국어 문구로 나간다(빈 본문·500 이
   * 아니다). 권한 판정 자체는 ApiImportServiceTest 가 실제 DB 로 본다.
   */
  @Test
  void createApiImport_optionWithoutPermission_returnsForbiddenWithKoreanMessage()
      throws Exception {
    String message = PermissionInterceptor.missingPermissionMessage("pipeline:execute");
    when(apiImportService.createApiImport(eq(1L), any(ApiImportRequest.class), eq(1L)))
        .thenThrow(new AccessDeniedException(message));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/api-import")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"apiConfig\":{\"url\":\"https://api.example.com\"},\"executeImmediately\":true}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.message").value(message));
  }

  // ── WD-520: 데이터셋 설명 길이 상한 ──────────────────────────────────────────
  // 서비스 목은 기본값(null)을 돌려준다 — 여기서는 검증 통과 여부(상태 코드)만 본다.

  /** 설명 상한 초과 400 응답의 한국어 사유. */
  private static final String DATASET_DESCRIPTION_TOO_LONG = "데이터셋 설명은 최대 2,000자까지 입력할 수 있습니다.";

  /** WD-520: 생성 — 설명이 정확히 2,000자면 201, 2,001자면 400 + 한국어 사유로 서비스까지 가지 않는다. */
  @Test
  void createDataset_descriptionLimit_atLimitCreated_overLimit400() throws Exception {
    CreateDatasetRequest over =
        new CreateDatasetRequest(
            "Orders",
            "orders",
            "가".repeat(DescriptionLimits.MAX_LENGTH + 1),
            null,
            "TABLE",
            "SOURCE",
            List.of(),
            null);
    mockMvc
        .perform(
            post("/api/v1/datasets")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value(DATASET_DESCRIPTION_TOO_LONG));
    verify(datasetService, never()).createDataset(any(), anyLong());

    CreateDatasetRequest atLimit =
        new CreateDatasetRequest(
            "Orders",
            "orders",
            "가".repeat(DescriptionLimits.MAX_LENGTH),
            null,
            "TABLE",
            "SOURCE",
            List.of(),
            null);
    mockMvc
        .perform(
            post("/api/v1/datasets")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(atLimit)))
        .andExpect(status().isCreated());
  }

  /** WD-520: 수정 — 2,001자는 400, 2,000자와 빈 설명(null)은 204. */
  @Test
  void updateDataset_descriptionLimit_atLimitOk_overLimit400_nullOk() throws Exception {
    UpdateDatasetRequest over =
        new UpdateDatasetRequest("Orders", "가".repeat(DescriptionLimits.MAX_LENGTH + 1), null);
    mockMvc
        .perform(
            put("/api/v1/datasets/1")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value(DATASET_DESCRIPTION_TOO_LONG));
    verify(datasetService, never()).updateDataset(anyLong(), any(), anyLong());

    for (String description : new String[] {"가".repeat(DescriptionLimits.MAX_LENGTH), null}) {
      UpdateDatasetRequest ok = new UpdateDatasetRequest("Orders", description, null);
      mockMvc
          .perform(
              put("/api/v1/datasets/1")
                  .header("Authorization", "Bearer test-token")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(ok)))
          .andExpect(status().isNoContent());
    }
  }

  /** WD-520: 복제 — 새 데이터셋에 붙일 설명도 같은 상한. 2,001자는 400, 2,000자는 201. */
  @Test
  void cloneDataset_descriptionLimit_atLimitCreated_overLimit400() throws Exception {
    CloneDatasetRequest over =
        new CloneDatasetRequest(
            "Clone", "clone_ds", "가".repeat(DescriptionLimits.MAX_LENGTH + 1), false, false);
    mockMvc
        .perform(
            post("/api/v1/datasets/1/clone")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(over)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.description").value(DATASET_DESCRIPTION_TOO_LONG));
    verify(datasetService, never()).cloneDataset(anyLong(), any(), anyLong());

    CloneDatasetRequest atLimit =
        new CloneDatasetRequest(
            "Clone", "clone_ds", "가".repeat(DescriptionLimits.MAX_LENGTH), false, false);
    mockMvc
        .perform(
            post("/api/v1/datasets/1/clone")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(atLimit)))
        .andExpect(status().isCreated());
  }

  /** 복제 요청 본문을 그대로 보낸다 — 앞뒤 공백·이모지가 든 이름을 원문 JSON 으로 싣기 위해. */
  private org.springframework.test.web.servlet.ResultActions postClone(String name)
      throws Exception {
    Map<String, Object> body = new java.util.HashMap<>();
    body.put("name", name);
    body.put("tableName", "clone_ds");
    body.put("includeData", false);
    body.put("includeTags", false);
    return mockMvc.perform(
        post("/api/v1/datasets/1/clone")
            .header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  /**
   * WD-699: 복제 이름도 화면 규칙(datasetNameSchema)과 같게 — 공백만·null 은 「데이터셋 이름을 입력하세요」, 앞뒤 공백을 뺀 길이가 100자를
   * 넘으면 「이름은 100자 이하여야 합니다」로 400 이고 서비스까지 가지 않는다.
   */
  @Test
  void cloneDataset_blankOrTooLongName_returns400WithKoreanMessage() throws Exception {
    for (String blank : new String[] {"   ", "", null}) {
      postClone(blank)
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.errors.name").value("데이터셋 이름을 입력하세요"));
    }
    postClone("a".repeat(101))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.name").value("이름은 100자 이하여야 합니다"));
    verify(datasetService, never()).cloneDataset(anyLong(), any(), anyLong());
  }

  /**
   * WD-699: 길이는 앞뒤 공백을 자른 뒤 UTF-16 단위로 센다 — 공백을 둘러싼 100자 이름·이모지 50개(UTF-16 100단위)는 통과하고, 서비스에는 잘린
   * 이름이 간다.
   */
  @Test
  void cloneDataset_nameIsStrippedBeforeLengthCheck_andSavedStripped() throws Exception {
    String hundred = "가".repeat(100);
    String emoji = "\uD83D\uDE00".repeat(50);
    for (String expected : new String[] {"매출 사본", hundred, emoji}) {
      org.mockito.Mockito.clearInvocations(datasetService);
      postClone("  " + expected + "  ").andExpect(status().isCreated());
      org.mockito.ArgumentCaptor<CloneDatasetRequest> captor =
          org.mockito.ArgumentCaptor.forClass(CloneDatasetRequest.class);
      verify(datasetService).cloneDataset(eq(1L), captor.capture(), anyLong());
      org.assertj.core.api.Assertions.assertThat(captor.getValue().name()).isEqualTo(expected);
    }
  }

  /**
   * GET /datasets/%20 — 공백만 있는 id 는 변환 결과가 null 이라 MissingPathVariableException 이 난다. 500 이 아니라 400
   * + 변수명 안내이고 서비스까지 가지 않는다 (WD-567).
   */
  @Test
  void getDataset_blankId_returns400NotServerError() throws Exception {
    mockMvc
        .perform(
            get(java.net.URI.create("/api/v1/datasets/%20"))
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.VALIDATION_FAILED_MESSAGE))
        .andExpect(jsonPath("$.errors.id").value("'id' 값이 비어 있습니다."));
    verify(datasetService, never()).getDatasetById(any(), any());
  }
}
