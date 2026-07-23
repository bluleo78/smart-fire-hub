package com.smartfirehub.synonymreview;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.exception.ExternalServiceException;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.synonymreview.controller.SynonymDecisionController;
import com.smartfirehub.synonymreview.dto.SynonymDecisionRecord;
import com.smartfirehub.synonymreview.repository.SynonymDecisionRepository;
import com.smartfirehub.synonymreview.service.SynonymDecisionService;
import com.smartfirehub.synonymreview.service.SynonymMergeClient;
import java.time.LocalDateTime;
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

/** SynonymDecisionController 엔드포인트 테스트 — {@link com.smartfirehub.graphingest.GraphIngestControllerTest} 선례. */
@WebMvcTest(SynonymDecisionController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, SynonymDecisionService.class})
class SynonymDecisionControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SynonymDecisionRepository repository;
  @MockitoBean private SynonymMergeClient mergeClient;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read", "dataset:write"));
  }

  @Test
  void lookup_noDecision_returnsNone() throws Exception {
    when(repository.findDecision("Cause", "전기적 요인", "분전반의 누전")).thenReturn(Optional.empty());

    mockMvc
        .perform(
            get("/api/v1/graphrag/synonym-decisions/lookup")
                .param("entityType", "Cause").param("nameA", "전기적 요인").param("nameB", "분전반의 누전")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("none"));
  }

  @Test
  void approve_success_updatesStatusAndCallsMergeClient() throws Exception {
    SynonymDecisionRecord pending = new SynonymDecisionRecord(
        1L, "Cause", "전기적 요인", "분전반의 누전", "pending", 0.7, "rationale", null, null, LocalDateTime.now());
    SynonymDecisionRecord approved = new SynonymDecisionRecord(
        1L, "Cause", "전기적 요인", "분전반의 누전", "approved", 0.7, "rationale", 1L, LocalDateTime.now(), LocalDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(pending), Optional.of(approved));

    mockMvc
        .perform(post("/api/v1/graphrag/synonym-decisions/{id}/approve", 1L)
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("approved"));

    verify(mergeClient, times(1)).mergeEntities("Cause", "전기적 요인", "분전반의 누전");
    verify(repository, times(1)).updateStatus(1L, "approved", 1L);
  }

  @Test
  void approve_mergeClientFails_doesNotUpdateStatus() throws Exception {
    SynonymDecisionRecord pending = new SynonymDecisionRecord(
        1L, "Cause", "전기적 요인", "분전반의 누전", "pending", 0.7, "rationale", null, null, LocalDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(pending));
    doThrow(new ExternalServiceException("ai-agent 호출 실패"))
        .when(mergeClient).mergeEntities(anyString(), anyString(), anyString());

    mockMvc
        .perform(post("/api/v1/graphrag/synonym-decisions/{id}/approve", 1L)
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadGateway());

    verify(repository, never()).updateStatus(anyLong(), anyString(), anyLong());
  }

  @Test
  void reject_success_updatesStatusWithoutMergeClient() throws Exception {
    SynonymDecisionRecord pending = new SynonymDecisionRecord(
        1L, "Cause", "전기적 요인", "분전반의 누전", "pending", 0.7, "rationale", null, null, LocalDateTime.now());
    SynonymDecisionRecord rejected = new SynonymDecisionRecord(
        1L, "Cause", "전기적 요인", "분전반의 누전", "rejected", 0.7, "rationale", 1L, LocalDateTime.now(), LocalDateTime.now());
    when(repository.findById(1L)).thenReturn(Optional.of(pending), Optional.of(rejected));

    mockMvc
        .perform(post("/api/v1/graphrag/synonym-decisions/{id}/reject", 1L)
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("rejected"));

    verify(mergeClient, times(0)).mergeEntities(anyString(), anyString(), anyString());
  }

  @Test
  void listPending_withoutReadPermission_returnsForbidden() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());
    mockMvc
        .perform(get("/api/v1/graphrag/synonym-decisions").param("status", "pending")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }
}
