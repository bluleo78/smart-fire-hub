package com.smartfirehub.dataset.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.*;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetSearchService;
import com.smartfirehub.dataset.service.ApiImportService;
import com.smartfirehub.dataset.service.DatasetDataService;
import com.smartfirehub.dataset.service.DatasetFavoriteService;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.HashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** DatasetController 추가 엔드포인트 커버리지 보강 테스트. 컬럼 관리, 데이터 조회/수정/삭제, 태그, 즐겨찾기, 상태 변경 등을 커버한다. */
@SuppressWarnings("null")
@WebMvcTest(DatasetController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DatasetControllerExtendedTest {

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
  // 검색/임베딩 백필 서비스도 DatasetController 의존성이므로 @WebMvcTest 컨텍스트에 mock 등록 필요
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

  private DatasetDetailResponse sampleDetail() {
    return new DatasetDetailResponse(
        1L,
        "DS",
        "ds_table",
        null,
        null,
        "TABLE",
        "SOURCE",
        "user",
        List.of(),
        0L,
        LocalDateTime.now(),
        LocalDateTime.now(),
        "user",
        false,
        List.of(),
        "ACTIVE",
        null,
        null,
        null,
        List.of(),
        null);
  }

  private DatasetColumnResponse sampleColumnResponse() {
    return new DatasetColumnResponse(
        10L, "new_col", "New Col", "TEXT", null, true, false, null, 1, false);
  }

  // ── 컬럼 관리 ────────────────────────────────────────────────────────────────

  @Test
  void addColumn_returnsCreated() throws Exception {
    AddColumnRequest req =
        new AddColumnRequest("new_col", "New Col", "TEXT", null, true, false, null);
    when(datasetService.addColumn(eq(1L), any())).thenReturn(sampleColumnResponse());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/columns")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.columnName").value("new_col"));
  }

  @Test
  void updateColumn_returnsNoContent() throws Exception {
    UpdateColumnRequest req =
        new UpdateColumnRequest(null, "New Display", null, null, null, null, null);
    doNothing().when(datasetService).updateColumn(anyLong(), anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/columns/10")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void deleteColumn_returnsNoContent() throws Exception {
    doNothing().when(datasetService).deleteColumn(anyLong(), anyLong());

    mockMvc
        .perform(
            delete("/api/v1/datasets/1/columns/10").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void reorderColumns_returnsNoContent() throws Exception {
    ReorderColumnsRequest req = new ReorderColumnsRequest(List.of(2L, 1L, 3L));
    doNothing().when(datasetService).reorderColumns(anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/columns/reorder")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  // ── 데이터 조작 ───────────────────────────────────────────────────────────────

  @Test
  void getDatasetStats_returnsStatsList() throws Exception {
    ColumnStatsResponse stat =
        new ColumnStatsResponse(
            "col1", "TEXT", 100L, 5L, 0.05, 10L, "a", "z", null, List.of(), false);
    when(datasetDataService.getDatasetStats(1L)).thenReturn(List.of(stat));

    mockMvc
        .perform(get("/api/v1/datasets/1/stats").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].columnName").value("col1"));
  }

  @Test
  void deleteDataRows_validIds_returnsOk() throws Exception {
    DataDeleteRequest req = new DataDeleteRequest(List.of(1L, 2L, 3L));
    when(datasetDataService.deleteDataRows(anyLong(), any())).thenReturn(new DataDeleteResponse(3));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/delete")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(3));
  }

  @Test
  void deleteDataRows_emptyIds_returnsBadRequest() throws Exception {
    DataDeleteRequest req = new DataDeleteRequest(List.of());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/delete")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void truncateData_returnsOk() throws Exception {
    when(datasetDataService.truncateDatasetData(1L)).thenReturn(new DataDeleteResponse(50));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/truncate").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.deletedCount").value(50));
  }

  @Test
  void getRowCount_returnsCount() throws Exception {
    when(datasetDataService.getRowCount(1L)).thenReturn(new RowCountResponse(42L));

    mockMvc
        .perform(get("/api/v1/datasets/1/data/count").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rowCount").value(42));
  }

  @Test
  void getData_returnsDataQueryResponse() throws Exception {
    DataQueryResponse resp = new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(get("/api/v1/datasets/1/data").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getData_withNearbyFilter_passesFilter() throws Exception {
    DataQueryResponse resp = new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data")
                .param("spatialColumn", "location")
                .param("nearbyLon", "127.0")
                .param("nearbyLat", "37.5")
                .param("nearbyRadius", "1000")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void getData_withBboxFilter_passesFilter() throws Exception {
    DataQueryResponse resp = new DataQueryResponse(List.of(), List.of(), 0, 50, 0L, 0);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(
            get("/api/v1/datasets/1/data")
                .param("bboxMinLon", "126.0")
                .param("bboxMinLat", "37.0")
                .param("bboxMaxLon", "128.0")
                .param("bboxMaxLat", "38.0")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  @Test
  void replaceData_returnsCreated() throws Exception {
    BatchRowDataRequest req = new BatchRowDataRequest(List.of(Map.of("name", "Alice")));
    when(datasetDataService.replaceDatasetData(anyLong(), any()))
        .thenReturn(new BatchRowDataResponse(1));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/replace")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.insertedCount").value(1));
  }

  // ── 즐겨찾기 ─────────────────────────────────────────────────────────────────

  @Test
  void toggleFavorite_returnsOk() throws Exception {
    when(datasetFavoriteService.toggleFavorite(1L, 1L))
        .thenReturn(new FavoriteToggleResponse(true));

    mockMvc
        .perform(post("/api/v1/datasets/1/favorite").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.favorited").value(true));
  }

  // ── 태그 ──────────────────────────────────────────────────────────────────────

  @Test
  void getAllTags_returnsList() throws Exception {
    when(datasetTagService.getAllDistinctTags()).thenReturn(List.of("tag1", "tag2"));

    mockMvc
        .perform(get("/api/v1/datasets/tags").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").value("tag1"));
  }

  @Test
  void addTag_validName_returnsCreated() throws Exception {
    AddTagRequest req = new AddTagRequest("sales");
    doNothing().when(datasetTagService).addTag(anyLong(), anyString(), anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated());
  }

  @Test
  void addTag_invalidName_returnsBadRequest() throws Exception {
    AddTagRequest req = new AddTagRequest("invalid name with spaces!");

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addTag_blankName_returnsBadRequest() throws Exception {
    AddTagRequest req = new AddTagRequest("");

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void addTag_duplicateTag_returnsConflict() throws Exception {
    AddTagRequest req = new AddTagRequest("sales");
    org.mockito.Mockito.doThrow(new IllegalStateException("Tag already exists"))
        .when(datasetTagService)
        .addTag(anyLong(), anyString(), anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/tags")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isConflict());
  }

  @Test
  void deleteTag_returnsNoContent() throws Exception {
    doNothing().when(datasetTagService).deleteTag(anyLong(), anyString());

    mockMvc
        .perform(
            delete("/api/v1/datasets/1/tags/sales").header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  // ── 상태 변경 ─────────────────────────────────────────────────────────────────

  @Test
  void updateStatus_returnsOk() throws Exception {
    UpdateStatusRequest req = new UpdateStatusRequest("ARCHIVED", "No longer needed");
    when(datasetService.updateStatus(anyLong(), any(), anyLong())).thenReturn(sampleDetail());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/status")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  // ── 설명 전파 ─────────────────────────────────────────────────────────────────

  @Test
  void propagateDescriptions_returnsNoContent() throws Exception {
    doNothing().when(datasetDataService).propagateDescriptions(anyLong());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/propagate-descriptions")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isNoContent());
  }

  // ── SQL 쿼리 ──────────────────────────────────────────────────────────────────

  @Test
  void executeQuery_returnsOk() throws Exception {
    SqlQueryRequest req = new SqlQueryRequest("SELECT 1", 100);
    SqlQueryResponse resp = new SqlQueryResponse("SELECT", List.of(), List.of(), 0, 0L, null);
    when(datasetDataService.executeQuery(anyLong(), any(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/query")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk());
  }

  /**
   * WD-104: 데이터셋 SQL 탭 응답도 WD-101 과 같은 규칙으로 정밀도를 잃는 숫자만 문자열로 낸다. 사용자 SELECT 는 데이터셋 컬럼에 없는 타입도 만들므로
   * BigInteger(NUMERIC(p≥19,0) 식)와 배열 원소까지 확인한다.
   */
  @Test
  void executeQuery_serializesUnsafeNumbersAsStrings() throws Exception {
    Map<String, Object> row = precisionRow();
    row.put("big_integer_unsafe", new BigInteger("12345678901234567890123"));
    row.put("big_integer_negative", new BigInteger("-9007199254740992"));
    row.put("big_integer_max_safe", BigInteger.valueOf(9007199254740991L));
    row.put("big_integer_small", BigInteger.valueOf(5));
    row.put("float_value", 1.5f);
    row.put("short_value", (short) 7);
    row.put(
        "decimal_array",
        new Object[] {new BigDecimal("123456789012.123456"), new BigDecimal("1.500000"), null});
    row.put("bigint_list", List.of(9007199254740993L, 5L));
    SqlQueryRequest req = new SqlQueryRequest("SELECT 1", 100);
    SqlQueryResponse resp =
        new SqlQueryResponse("SELECT", List.of("big_decimal"), List.of(row), 1, 3L, null);
    when(datasetDataService.executeQuery(anyLong(), any(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/query")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isOk())
        // 응답 메타 필드는 그대로 숫자다
        .andExpect(jsonPath("$.affectedRows").isNumber())
        .andExpect(jsonPath("$.executionTimeMs").isNumber())
        .andExpect(jsonPath("$.rows[0].id").isNumber())
        .andExpect(jsonPath("$.rows[0].id").value(7))
        .andExpect(jsonPath("$.rows[0].big_decimal").isString())
        .andExpect(jsonPath("$.rows[0].big_decimal").value("123456789012.123456"))
        .andExpect(jsonPath("$.rows[0].unsafe_bigint").isString())
        .andExpect(jsonPath("$.rows[0].unsafe_bigint").value("9007199254740993"))
        .andExpect(jsonPath("$.rows[0].unsafe_negative").isString())
        .andExpect(jsonPath("$.rows[0].unsafe_negative").value("-9007199254740993"))
        .andExpect(jsonPath("$.rows[0].max_safe").isNumber())
        .andExpect(jsonPath("$.rows[0].min_long").isString())
        .andExpect(jsonPath("$.rows[0].min_long").value(String.valueOf(Long.MIN_VALUE)))
        .andExpect(jsonPath("$.rows[0].small_bigint").isNumber())
        .andExpect(jsonPath("$.rows[0].small_bigint").value(5))
        .andExpect(jsonPath("$.rows[0].small_int").isNumber())
        .andExpect(jsonPath("$.rows[0].trailing_half").isString())
        .andExpect(jsonPath("$.rows[0].trailing_half").value("1.5"))
        .andExpect(jsonPath("$.rows[0].trailing_int").isString())
        .andExpect(jsonPath("$.rows[0].trailing_int").value("100"))
        .andExpect(jsonPath("$.rows[0].zero_scaled").isString())
        .andExpect(jsonPath("$.rows[0].zero_scaled").value("0"))
        .andExpect(jsonPath("$.rows[0].double_value").isNumber())
        .andExpect(jsonPath("$.rows[0].float_value").isNumber())
        .andExpect(jsonPath("$.rows[0].short_value").isNumber())
        .andExpect(jsonPath("$.rows[0].text_value").value("가나다"))
        .andExpect(jsonPath("$.rows[0].big_integer_unsafe").isString())
        .andExpect(jsonPath("$.rows[0].big_integer_unsafe").value("12345678901234567890123"))
        .andExpect(jsonPath("$.rows[0].big_integer_negative").isString())
        .andExpect(jsonPath("$.rows[0].big_integer_negative").value("-9007199254740992"))
        .andExpect(jsonPath("$.rows[0].big_integer_max_safe").isNumber())
        .andExpect(jsonPath("$.rows[0].big_integer_small").isNumber())
        .andExpect(jsonPath("$.rows[0].big_integer_small").value(5))
        .andExpect(jsonPath("$.rows[0].decimal_array[0]").isString())
        .andExpect(jsonPath("$.rows[0].decimal_array[0]").value("123456789012.123456"))
        .andExpect(jsonPath("$.rows[0].decimal_array[1]").value("1.5"))
        .andExpect(jsonPath("$.rows[0].bigint_list[0]").isString())
        .andExpect(jsonPath("$.rows[0].bigint_list[0]").value("9007199254740993"))
        .andExpect(jsonPath("$.rows[0].bigint_list[1]").isNumber())
        // value(String) 는 숫자도 문자열로 바꿔 통과시키므로 원문으로 타입·null 까지 고정한다.
        .andExpect(content().string(containsString("\"big_decimal\":\"123456789012.123456\"")))
        .andExpect(content().string(containsString("\"unsafe_bigint\":\"9007199254740993\"")))
        .andExpect(content().string(containsString("\"max_safe\":9007199254740991")))
        .andExpect(content().string(containsString("\"big_integer_max_safe\":9007199254740991")))
        .andExpect(
            content()
                .string(containsString("\"decimal_array\":[\"123456789012.123456\",\"1.5\",null]")))
        .andExpect(content().string(containsString("\"empty_value\":null")));
  }

  @Test
  void getQueryHistory_returnsPageResponse() throws Exception {
    PageResponse<QueryHistoryResponse> page = new PageResponse<>(List.of(), 0, 20, 0, 0);
    when(datasetDataService.getQueryHistory(anyLong(), anyInt(), anyInt())).thenReturn(page);

    mockMvc
        .perform(get("/api/v1/datasets/1/queries").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk());
  }

  // ── 행 수동 입력 ──────────────────────────────────────────────────────────────

  @Test
  void addRow_returnsCreated() throws Exception {
    RowDataRequest req = new RowDataRequest(Map.of("name", "Alice"));
    RowDataResponse resp = new RowDataResponse(1L, Map.of("name", "Alice"), LocalDateTime.now());
    when(datasetDataService.addRow(anyLong(), any())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/rows")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(1));
  }

  @Test
  void addRowsBatch_returnsCreated() throws Exception {
    BatchRowDataRequest req = new BatchRowDataRequest(List.of(Map.of("name", "Bob")));
    when(datasetDataService.addRowsBatch(anyLong(), any())).thenReturn(new BatchRowDataResponse(1));

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/rows/batch")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.insertedCount").value(1));
  }

  @Test
  void updateRow_returnsNoContent() throws Exception {
    RowDataRequest req = new RowDataRequest(Map.of("name", "Carol"));
    doNothing().when(datasetDataService).updateRow(anyLong(), anyLong(), any());

    mockMvc
        .perform(
            put("/api/v1/datasets/1/data/rows/5")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isNoContent());
  }

  @Test
  void getRow_returnsRowData() throws Exception {
    RowDataResponse resp = new RowDataResponse(5L, Map.of("name", "Dave"), LocalDateTime.now());
    when(datasetDataService.getRow(anyLong(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(get("/api/v1/datasets/1/data/rows/5").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(5));
  }

  // ── 행 값 정밀도(WD-101) ─────────────────────────────────────────────────────────

  /**
   * WD-101 테스트용 행 — JS double 로 깎이는 값(큰 소수·2^53 밖 정수)과 깎이지 않는 값(작은 정수·Double)·소수 끝자리 0·null 을 섞는다.
   * null 이 들어가야 하므로 Map.of 대신 HashMap 을 쓴다.
   */
  private static Map<String, Object> precisionRow() {
    Map<String, Object> row = new HashMap<>();
    row.put("id", 7L);
    row.put("big_decimal", new BigDecimal("123456789012.123456"));
    row.put("unsafe_bigint", 9007199254740993L);
    row.put("unsafe_negative", -9007199254740993L);
    row.put("max_safe", 9007199254740991L);
    row.put("min_long", Long.MIN_VALUE);
    row.put("small_bigint", 5L);
    row.put("small_int", 42);
    row.put("trailing_half", new BigDecimal("1.500000"));
    row.put("trailing_int", new BigDecimal("100.000000"));
    row.put("zero_scaled", new BigDecimal("0E-6"));
    row.put("double_value", 2.5d);
    row.put("text_value", "가나다");
    row.put("empty_value", null);
    return row;
  }

  @Test
  void getData_serializesUnsafeNumbersAsStrings() throws Exception {
    // 데이터 탭 그리드 응답: 소수는 항상 문자열(끝자리 0 제거·지수 표기 없음), 정수는 ±(2^53-1) 밖에서만 문자열이어야 한다.
    DataQueryResponse resp =
        new DataQueryResponse(List.of(), List.of(precisionRow()), 0, 50, 1L, 1);
    when(datasetDataService.getDatasetData(
            anyLong(), any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any()))
        .thenReturn(resp);

    mockMvc
        .perform(get("/api/v1/datasets/1/data").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[0].id").isNumber())
        .andExpect(jsonPath("$.rows[0].id").value(7))
        .andExpect(jsonPath("$.rows[0].big_decimal").isString())
        .andExpect(jsonPath("$.rows[0].big_decimal").value("123456789012.123456"))
        .andExpect(jsonPath("$.rows[0].unsafe_bigint").isString())
        .andExpect(jsonPath("$.rows[0].unsafe_bigint").value("9007199254740993"))
        .andExpect(jsonPath("$.rows[0].unsafe_negative").value("-9007199254740993"))
        .andExpect(jsonPath("$.rows[0].max_safe").isNumber())
        .andExpect(jsonPath("$.rows[0].min_long").value(String.valueOf(Long.MIN_VALUE)))
        .andExpect(jsonPath("$.rows[0].small_bigint").isNumber())
        .andExpect(jsonPath("$.rows[0].small_bigint").value(5))
        .andExpect(jsonPath("$.rows[0].small_int").isNumber())
        .andExpect(jsonPath("$.rows[0].trailing_half").value("1.5"))
        .andExpect(jsonPath("$.rows[0].trailing_int").value("100"))
        .andExpect(jsonPath("$.rows[0].zero_scaled").value("0"))
        .andExpect(jsonPath("$.rows[0].double_value").isNumber())
        .andExpect(jsonPath("$.rows[0].text_value").value("가나다"))
        // jsonPath value(String) 는 숫자도 문자열로 바꿔 비교해 통과시키므로 isString 과 원문으로 타입까지 확인한다.
        .andExpect(content().string(containsString("\"big_decimal\":\"123456789012.123456\"")))
        .andExpect(content().string(containsString("\"unsafe_bigint\":\"9007199254740993\"")))
        .andExpect(content().string(containsString("\"max_safe\":9007199254740991")))
        .andExpect(content().string(containsString("\"empty_value\":null")));
  }

  @Test
  void getRow_serializesUnsafeNumbersAsStrings() throws Exception {
    // 단건 조회(행 편집 다이얼로그)도 같은 규칙 — 최상위 id 는 Long 필드라 숫자 그대로다.
    RowDataResponse resp = new RowDataResponse(5L, precisionRow(), LocalDateTime.now());
    when(datasetDataService.getRow(anyLong(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(get("/api/v1/datasets/1/data/rows/5").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNumber())
        .andExpect(jsonPath("$.data.big_decimal").isString())
        .andExpect(jsonPath("$.data.big_decimal").value("123456789012.123456"))
        .andExpect(jsonPath("$.data.unsafe_bigint").isString())
        .andExpect(jsonPath("$.data.unsafe_bigint").value("9007199254740993"))
        .andExpect(jsonPath("$.data.small_bigint").isNumber())
        .andExpect(jsonPath("$.data.trailing_half").value("1.5"))
        .andExpect(jsonPath("$.data.zero_scaled").value("0"))
        .andExpect(content().string(containsString("\"empty_value\":null")));
  }

  @Test
  void addRow_serializesUnsafeNumbersAsStrings() throws Exception {
    // 행 추가 응답도 RowDataResponse 라 같은 규칙을 따른다.
    RowDataRequest req = new RowDataRequest(Map.of("big_decimal", "123456789012.123456"));
    RowDataResponse resp = new RowDataResponse(8L, precisionRow(), LocalDateTime.now());
    when(datasetDataService.addRow(anyLong(), any())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/data/rows")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.big_decimal").isString())
        .andExpect(jsonPath("$.data.big_decimal").value("123456789012.123456"))
        .andExpect(jsonPath("$.data.unsafe_bigint").isString())
        .andExpect(jsonPath("$.data.unsafe_bigint").value("9007199254740993"));
  }

  // ── API 임포트 / 클론 ──────────────────────────────────────────────────────────

  @Test
  void createApiImport_returnsCreated() throws Exception {
    ApiImportRequest req =
        new ApiImportRequest("api-pipeline", null, null, null, "APPEND", true, null);
    ApiImportResponse resp = new ApiImportResponse(10L, 20L, 30L);
    when(apiImportService.createApiImport(anyLong(), any(), anyLong())).thenReturn(resp);

    mockMvc
        .perform(
            post("/api/v1/datasets/1/api-import")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.pipelineId").value(10));
  }

  @Test
  void cloneDataset_returnsCreated() throws Exception {
    CloneDatasetRequest req = new CloneDatasetRequest("Clone DS", "clone_ds", null, false, false);
    when(datasetService.cloneDataset(anyLong(), any(), anyLong())).thenReturn(sampleDetail());

    mockMvc
        .perform(
            post("/api/v1/datasets/1/clone")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
        .andExpect(status().isCreated());
  }
}
