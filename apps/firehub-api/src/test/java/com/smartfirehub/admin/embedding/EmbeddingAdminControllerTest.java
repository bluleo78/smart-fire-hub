package com.smartfirehub.admin.embedding;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.document.service.DocumentChunkReembedService;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
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

  @Autowired private MockMvc mockMvc;

  // 컨트롤러가 의존하는 3개 서비스 — 모두 mock 으로 대체해 컨트롤러 매핑/권한만 검증
  @MockitoBean private EmbeddingStatusService embeddingStatusService;

  @MockitoBean private DatasetEmbeddingBackfillService datasetEmbeddingBackfillService;

  @MockitoBean private DocumentChunkReembedService documentChunkReembedService;

  @MockitoBean private EmbeddingProviderFactory embeddingProviderFactory;

  // SecurityConfig/JwtAuthenticationFilter 가 요구하는 인증·권한 빈
  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "dataset:write"));
  }

  @Test
  void status_withPermission_returnsAggregatedCounts() throws Exception {
    // dataset:read 권한으로 상태 조회 시 200 + 집계 결과 반환
    when(embeddingStatusService.status())
        .thenReturn(
            new EmbeddingStatusResponse(
                "bge-m3",
                new EmbeddingStatusResponse.Counts(28, 20),
                new EmbeddingStatusResponse.Counts(500, 340)));

    mockMvc
        .perform(get("/api/v1/admin/embedding/status").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.model").value("bge-m3"))
        .andExpect(jsonPath("$.datasets.total").value(28))
        .andExpect(jsonPath("$.documentChunks.embedded").value(340));
  }

  @Test
  void reindexAll_withPermission_returnsAcceptedWithScheduledCounts() throws Exception {
    // dataset:write 권한으로 전체 재색인 트리거 시 202 + 예약 데이터셋 수 반환, 두 서비스 호출 검증
    when(datasetEmbeddingBackfillService.backfillAll()).thenReturn(28);
    when(documentChunkReembedService.reembedAll()).thenReturn(4);

    mockMvc
        .perform(
            post("/api/v1/admin/embedding/reindex-all")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.datasets").value(28))
        .andExpect(jsonPath("$.documentDatasets").value(4));

    verify(datasetEmbeddingBackfillService).backfillAll();
    verify(documentChunkReembedService).reembedAll();
  }

  @Test
  void embed_withPermission_delegatesToActiveProviderAndReturnsVectors() throws Exception {
    // dataset:read 권한으로 내부 임베딩 실행 시, 활성 provider 에 위임한 결과를 model/dimension 과 함께 반환한다.
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
}
