package com.smartfirehub.admin.embedding;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.document.service.DocumentChunkReembedService;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
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
}
