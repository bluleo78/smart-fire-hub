package com.smartfirehub.graphreview;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import com.smartfirehub.graphreview.controller.ReviewItemController;
import com.smartfirehub.graphreview.dto.ReviewItemResponse;
import com.smartfirehub.graphreview.service.ReviewItemService;
import com.smartfirehub.permission.service.PermissionService;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ReviewItemController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class ReviewItemControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private ReviewItemService service;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read", "dataset:write"));
  }

  private static ReviewItemResponse resp(String status) {
    return new ReviewItemResponse(1L, "property_normalization", status, 12L, "normalization_failure",
        null, "reason", com.fasterxml.jackson.databind.node.NullNode.getInstance(), 1L, null, null);
  }

  @Test
  void lookup_returnsStatus() throws Exception {
    when(service.lookupSynonym("Cause", "a", "b")).thenReturn("none");
    mockMvc.perform(get("/api/v1/graphrag/review-items/synonym/lookup")
            .param("entityType", "Cause").param("nameA", "a").param("nameB", "b")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("none"));
  }

  @Test
  void approve_property_passesCorrectedValue() throws Exception {
    when(service.approve(eq(1L), eq("30000000"), eq(1L))).thenReturn(resp("approved"));
    mockMvc.perform(post("/api/v1/graphrag/review-items/{id}/approve", 1L)
            .contentType("application/json").content("{\"correctedValue\":\"30000000\"}")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("approved"));
    verify(service).approve(1L, "30000000", 1L);
  }

  @Test
  void approve_mutationFails_returns502() throws Exception {
    doThrow(new ExternalServiceException("fail")).when(service).approve(anyLong(), org.mockito.ArgumentMatchers.any(), anyLong());
    mockMvc.perform(post("/api/v1/graphrag/review-items/{id}/approve", 1L)
            .contentType("application/json").content("{}")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadGateway());
  }

  /**
   * 대상 노드 부재로 그래프에 아무 것도 반영되지 않은 승인은 409 + 구체적 사유로 나가야 한다 (#310).
   * 사유가 응답 body의 message로 살아 나오지 않으면 검수 UI가 일반 폴백 문구만 띄워 원인을 알 수 없다.
   */
  @Test
  void approve_graphTargetMissing_returns409WithReason() throws Exception {
    String reason = "주어/목적어 엔티티가 그래프에 없어 관계를 적재할 수 없습니다.";
    doThrow(new IllegalStateException(reason))
        .when(service).approve(anyLong(), org.mockito.ArgumentMatchers.any(), anyLong());
    mockMvc.perform(post("/api/v1/graphrag/review-items/{id}/approve", 1L)
            .contentType("application/json").content("{}")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(reason));
  }

  @Test
  void reject_returnsRejected() throws Exception {
    when(service.reject(1L, 1L)).thenReturn(resp("rejected"));
    mockMvc.perform(post("/api/v1/graphrag/review-items/{id}/reject", 1L)
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("rejected"));
  }

  @Test
  void list_withoutReadPermission_forbidden() throws Exception {
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());
    mockMvc.perform(get("/api/v1/graphrag/review-items").param("status", "pending")
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void evidence_returnsChunks() throws Exception {
    when(service.evidence(1L)).thenReturn(List.of(
        new com.smartfirehub.graphreview.dto.EvidenceChunk(5L, "약 3천만원의 재산피해")));
    mockMvc.perform(get("/api/v1/graphrag/review-items/{id}/evidence", 1L)
            .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].chunkId").value(5))
        .andExpect(jsonPath("$[0].content").value("약 3천만원의 재산피해"));
  }
}
