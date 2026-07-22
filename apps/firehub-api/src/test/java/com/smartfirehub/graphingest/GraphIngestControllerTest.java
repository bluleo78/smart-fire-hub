package com.smartfirehub.graphingest;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.graphingest.controller.GraphIngestController;
import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import com.smartfirehub.graphingest.dto.RecordGraphIngestRequest;
import com.smartfirehub.graphingest.repository.GraphIngestRepository;
import com.smartfirehub.graphingest.service.GraphIngestService;
import com.smartfirehub.ontology.repository.OntologyRepository;
import com.smartfirehub.permission.service.PermissionService;
import java.time.LocalDateTime;
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

/**
 * GraphIngestController 엔드포인트 테스트.
 *
 * <p>{@link com.smartfirehub.document.controller.DocumentChunkControllerTest} 선례를 따라
 * {@code @WebMvcTest} + 인증/권한 mock 패턴을 사용한다. 단, GraphIngestService는 실제 빈을 사용하고
 * 그 하위 리포지토리(GraphIngestRepository, OntologyRepository)만 mock하여, 컨트롤러→서비스의
 * 매핑 로직(예: LocalDateTime→문자열 변환, stale 필터링)까지 포함한 입력=출력 왕복을 검증한다.
 */
@WebMvcTest(GraphIngestController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GraphIngestService.class})
class GraphIngestControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private GraphIngestRepository graphIngestRepository;

  @MockitoBean private OntologyRepository ontologyRepository;

  @MockitoBean private PermissionService permissionService;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;

  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    // 인증 mock — 유효 토큰 + dataset:read/write 권한을 PermissionInterceptor가 허용하도록 세팅한다.
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "dataset:write"));
  }

  @Test
  void record_and_history_roundTripsSameFieldValues() throws Exception {
    RecordGraphIngestRequest request = new RecordGraphIngestRequest(1, 10, 20, 15, 2, "SUCCESS");
    when(graphIngestRepository.save(1L, 1, 10, 20, 15, 2, "SUCCESS")).thenReturn(100L);

    // POST → 201 + id
    mockMvc
        .perform(
            post("/api/v1/datasets/{datasetId}/graph-ingests", 1L)
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(100));

    // GET history → 방금 기록한 값과 동일한 필드로 반환(입력=출력 왕복)
    LocalDateTime ingestedAt = LocalDateTime.of(2026, 7, 20, 12, 0, 0);
    when(graphIngestRepository.findByDataset(1L))
        .thenReturn(
            List.of(new GraphIngestRecord(100L, 1L, ingestedAt, 1, 10, 20, 15, 2, "SUCCESS")));

    mockMvc
        .perform(
            get("/api/v1/datasets/{datasetId}/graph-ingests", 1L)
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(100))
        .andExpect(jsonPath("$[0].datasetId").value(1))
        .andExpect(jsonPath("$[0].ingestedAt").value(ingestedAt.toString()))
        .andExpect(jsonPath("$[0].schemaVersionAtIngest").value(1))
        .andExpect(jsonPath("$[0].chunkCount").value(10))
        .andExpect(jsonPath("$[0].nodeCount").value(20))
        .andExpect(jsonPath("$[0].edgeCount").value(15))
        .andExpect(jsonPath("$[0].extractionFailures").value(2))
        .andExpect(jsonPath("$[0].status").value("SUCCESS"));
  }

  @Test
  void record_withoutWritePermission_returnsForbidden() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
    RecordGraphIngestRequest request = new RecordGraphIngestRequest(1, 10, 20, 15, 2, "SUCCESS");

    mockMvc
        .perform(
            post("/api/v1/datasets/{datasetId}/graph-ingests", 1L)
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isForbidden());
  }

  @Test
  void stale_currentVersionRow_excluded_lowerVersionRow_included() throws Exception {
    // 현재 온톨로지 버전 = 3(V71 시드=1을 흉내낸 임의값). v1 적재행은 stale, v3 적재행은 경계에서 제외.
    when(ontologyRepository.currentSchemaVersion()).thenReturn(3);
    LocalDateTime latestAt = LocalDateTime.of(2026, 7, 20, 9, 0, 0);
    // findStale은 repo 레벨에서 이미 lt(currentVersion) 필터링을 수행하므로,
    // 현재버전(3)과 동일한 데이터셋(9102L)은 결과에 포함되지 않고 v1로 낡은 데이터셋(9101L)만 반환된다.
    when(graphIngestRepository.findStale(3))
        .thenReturn(List.of(new StaleRow(9101L, latestAt, 1)));

    mockMvc
        .perform(
            get("/api/v1/graph-ingests/stale").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].datasetId").value(9101))
        .andExpect(jsonPath("$[0].schemaVersionAtIngest").value(1))
        .andExpect(jsonPath("$[0].currentSchemaVersion").value(3))
        .andExpect(jsonPath("$[0].latestIngestedAt").value(latestAt.toString()));
  }

  @Test
  void stale_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc.perform(get("/api/v1/graph-ingests/stale")).andExpect(status().isUnauthorized());
  }
}
