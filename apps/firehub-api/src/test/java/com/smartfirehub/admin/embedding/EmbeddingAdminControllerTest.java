package com.smartfirehub.admin.embedding;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.exception.GlobalExceptionHandler;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.List;
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

@SuppressWarnings("null")
@WebMvcTest(EmbeddingAdminController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class EmbeddingAdminControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;

  // 컨트롤러가 의존하는 3개 빈 — 모두 mock 으로 대체해 컨트롤러 매핑/권한만 검증
  @MockitoBean private EmbeddingStatusService embeddingStatusService;

  @MockitoBean private TenantReembedJob tenantReembedJob;

  @MockitoBean private EmbeddingProviderFactory embeddingProviderFactory;

  // SecurityConfig/JwtAuthenticationFilter 가 요구하는 인증·권한 빈
  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    // 기본은 관리자 상당 권한 — 각 권한 축소 시나리오는 개별 테스트가 덮어쓴다(WD-600).
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "dataset:write", "ai:settings"));
  }

  @Test
  void status_withPermission_returnsAggregatedCounts() throws Exception {
    // ai:settings 권한으로 상태 조회 시 200 + 집계 결과 반환(WD-600: 유일한 화면이 ai:settings 탭)
    when(embeddingStatusService.status())
        .thenReturn(
            new EmbeddingStatusResponse(
                true,
                "bge-m3",
                1024,
                new EmbeddingStatusResponse.Counts(28, 20),
                new EmbeddingStatusResponse.Counts(500, 340),
                null));

    mockMvc
        .perform(get("/api/v1/admin/embedding/status").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("bge-m3"))
        .andExpect(jsonPath("$.datasets.total").value(28))
        .andExpect(jsonPath("$.documentChunks.embedded").value(340));
  }

  @Test
  void reindexAll_withPermission_returnsAcceptedWithImpact() throws Exception {
    // dataset:write 권한으로 전체 재임베딩 요청 시 202 + 요청 시점 대상 수(판정식) 반환
    when(tenantReembedJob.requestReindexAll()).thenReturn(new EmbeddingImpact(340, 28, 2));
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/reindex-all")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.chunks").value(340))
        .andExpect(jsonPath("$.datasets").value(28))
        .andExpect(jsonPath("$.rowSearchIndexes").value(2));
    verify(tenantReembedJob).requestReindexAll();
  }

  @Test
  void embed_withPermission_delegatesToActiveProviderAndReturnsVectors() throws Exception {
    // dataset:write 권한으로 내부 임베딩 실행 시, 활성 provider 에 위임한 결과를 model/dimension 과 함께 반환한다.
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.dimension()).thenReturn(1024);
    when(provider.embed(List.of("스프링클러", "스프링클러 설비")))
        .thenReturn(List.of(new float[] {0.1f, 0.2f}, new float[] {0.3f, 0.4f}));
    when(embeddingProviderFactory.current()).thenReturn(provider);

    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texts\":[\"스프링클러\",\"스프링클러 설비\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("bge-m3"))
        .andExpect(jsonPath("$.dimension").value(1024))
        .andExpect(jsonPath("$.embeddings[0][0]").value(0.1))
        .andExpect(jsonPath("$.embeddings[1][1]").value(0.4));
  }

  @Test
  void embed_emptyTexts_returnsEmptyWithoutCallingProvider() throws Exception {
    // 빈 입력은 provider.embed 호출 없이 조기 반환한다(불필요한 외부 호출·과금 방지).
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.dimension()).thenReturn(1024);
    when(embeddingProviderFactory.current()).thenReturn(provider);

    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texts\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.embeddings").isEmpty());

    verify(provider, never()).embed(org.mockito.ArgumentMatchers.anyList());
  }

  @Test
  void embed_whenNotConfigured_returns409WithMessage() throws Exception {
    when(embeddingProviderFactory.current()).thenThrow(new EmbeddingNotConfiguredException());
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texts\":[\"a\"]}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("임베딩이 설정되지 않았습니다 (설정 > 임베딩)"));
  }

  // ── WD-600: 권한 상향·요청 크기 상한 ─────────────────────────────

  /** 읽기 권한만 가진 일반 사용자 — 외부 provider 비용이 드는 embed·관리 화면용 status 모두 403. */
  private void onlyDatasetRead() {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
  }

  /** texts 배열 JSON 을 만든다 — 원소는 그대로 JSON 문자열로 감싼다(null 은 리터럴 null). */
  private static String textsJson(List<String> texts) {
    StringBuilder sb = new StringBuilder("{\"texts\":[");
    for (int i = 0; i < texts.size(); i++) {
      if (i > 0) sb.append(',');
      String t = texts.get(i);
      sb.append(t == null ? "null" : "\"" + t + "\"");
    }
    return sb.append("]}").toString();
  }

  /** 활성 provider 목 — 입력 개수만큼 1차원 벡터를 돌려준다. */
  private EmbeddingProvider stubProvider() {
    EmbeddingProvider provider = mock(EmbeddingProvider.class);
    when(provider.modelId()).thenReturn("bge-m3");
    when(provider.dimension()).thenReturn(1);
    when(provider.embed(anyList()))
        .thenAnswer(
            inv -> {
              List<?> in = inv.getArgument(0);
              return in.stream().map(x -> new float[] {0.5f}).toList();
            });
    when(embeddingProviderFactory.current()).thenReturn(provider);
    return provider;
  }

  @Test
  void embed_withOnlyDatasetRead_returns403AndDoesNotCallProvider() throws Exception {
    // 읽기 권한만으로는 임의 텍스트를 외부 provider 로 임베딩시킬 수 없다(WD-600).
    onlyDatasetRead();
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texts\":[\"a\"]}"))
        .andExpect(status().isForbidden());
    verify(embeddingProviderFactory, never()).current();
  }

  @Test
  void embed_withOnlyDatasetRead_oversizedBody_returns403BeforeValidation() throws Exception {
    // 권한 검사가 본문 검증보다 먼저 — 권한 없는 사용자의 상한 초과 본문은 400 이 아니라 403(Review Focus).
    onlyDatasetRead();
    List<String> texts = java.util.Collections.nCopies(EmbedRequest.MAX_TEXTS + 1, "x");
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(textsJson(texts)))
        .andExpect(status().isForbidden());
  }

  @Test
  void status_withOnlyDatasetRead_returns403() throws Exception {
    // status 는 관리 설정 임베딩 탭 전용 — ai:settings 없으면 403(WD-600).
    onlyDatasetRead();
    mockMvc
        .perform(get("/api/v1/admin/embedding/status").header("Authorization", "Bearer test-token"))
        .andExpect(status().isForbidden());
    verify(embeddingStatusService, never()).status();
  }

  @Test
  void status_withOnlyAiSettings_returns200() throws Exception {
    // ai:settings 만 가진 사용자도 상태를 조회한다 — dataset 권한과 무관.
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ai:settings"));
    when(embeddingStatusService.status())
        .thenReturn(
            new EmbeddingStatusResponse(
                true,
                "bge-m3",
                1024,
                new EmbeddingStatusResponse.Counts(1, 1),
                new EmbeddingStatusResponse.Counts(2, 2),
                null));
    mockMvc
        .perform(get("/api/v1/admin/embedding/status").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("bge-m3"));
  }

  @Test
  void embed_withOnlyDatasetWrite_returns200() throws Exception {
    // graphrag_ingest 경로가 가진 dataset:write 만으로 호출 가능 — 기존 ingest 동작 유지.
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:write"));
    stubProvider();
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texts\":[\"a\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.embeddings.length()").value(1));
  }

  @Test
  void embed_atMaxTextsAndMaxLength_returns200() throws Exception {
    // 경계값(256개, 항목 2,000자)은 통과한다.
    EmbeddingProvider provider = stubProvider();
    List<String> texts =
        new java.util.ArrayList<>(java.util.Collections.nCopies(EmbedRequest.MAX_TEXTS - 1, "x"));
    texts.add("가".repeat(EmbedRequest.MAX_TEXT_LENGTH));
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(textsJson(texts)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.embeddings.length()").value(EmbedRequest.MAX_TEXTS));
    verify(provider).embed(anyList());
  }

  @Test
  void embed_tooManyTexts_returns400WithKoreanMessage() throws Exception {
    // 257개 → 400, provider 호출 없음(외부 비용 차단).
    stubProvider();
    List<String> texts = java.util.Collections.nCopies(EmbedRequest.MAX_TEXTS + 1, "x");
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(textsJson(texts)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(jsonPath("$.errors.texts").value(EmbedRequest.TOO_MANY_TEXTS_MESSAGE));
    verify(embeddingProviderFactory, never()).current();
  }

  @Test
  void embed_textTooLong_returns400WithKoreanMessage() throws Exception {
    // 항목 하나가 2,001자 → 400(잘라서 숨기지 않는다), provider 호출 없음.
    stubProvider();
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(textsJson(List.of("ok", "가".repeat(EmbedRequest.MAX_TEXT_LENGTH + 1)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(GlobalExceptionHandler.VALIDATION_FAILED_CODE))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString(EmbedRequest.TEXT_TOO_LONG_MESSAGE)));
    verify(embeddingProviderFactory, never()).current();
  }

  @Test
  void embed_nullElement_returns400() throws Exception {
    // null 원소는 provider 에서 NPE(500)가 되기 전에 400 으로 막는다.
    stubProvider();
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(textsJson(java.util.Arrays.asList("a", null))))
        .andExpect(status().isBadRequest())
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString(EmbedRequest.NULL_TEXT_MESSAGE)));
    verify(embeddingProviderFactory, never()).current();
  }

  @Test
  void embed_nullTexts_returns400() throws Exception {
    // texts 누락(null)은 기존대로 @NotNull 400.
    stubProvider();
    mockMvc
        .perform(
            post("/api/v1/admin/embedding/embed")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    verify(embeddingProviderFactory, never()).current();
  }
}
