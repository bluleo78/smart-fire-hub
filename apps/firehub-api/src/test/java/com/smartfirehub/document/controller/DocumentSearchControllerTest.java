package com.smartfirehub.document.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.service.DocumentSearchService;
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

/** DocumentSearchController WebMvcTest — 의미검색 엔드포인트와 권한 검증. */
@SuppressWarnings("null")
@WebMvcTest(DocumentSearchController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DocumentSearchControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DocumentSearchService searchService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    // JWT "test-token" → userId 1L. 기본은 dataset:read 권한 부여.
    when(jwtTokenProvider.validateAccessToken("test-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("test-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
  }

  /** POST /documents/search — dataset:read 권한 보유 시 200 + 서비스 결과 반환. */
  @Test
  void search_withPermission_returnsOkWithHits() throws Exception {
    var hit = new DocumentSearchHit(1L, 2L, 3L, "f.txt", 0, "내용", 0.9);
    when(searchService.search(any())).thenReturn(List.of(hit));

    mockMvc
        .perform(
            post("/api/v1/documents/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"질의\",\"datasetIds\":[3],\"topK\":5}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].content").value("내용"))
        .andExpect(jsonPath("$[0].score").value(0.9));
  }

  /** POST /documents/search — dataset:read 권한이 없으면 403(권한 없는 호출 거부). */
  @Test
  void search_withoutPermission_returnsForbidden() throws Exception {
    // 권한 집합을 비워 dataset:read 누락 상태를 만든다.
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());

    mockMvc
        .perform(
            post("/api/v1/documents/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"질의\",\"topK\":5}"))
        .andExpect(status().isForbidden());
  }

  /** POST /documents/search — 서비스가 빈 검색어로 IllegalArgumentException 시 400 매핑(웹 계층 검증). */
  @Test
  void search_blankQuery_returnsBadRequest() throws Exception {
    when(searchService.search(any()))
        .thenThrow(new IllegalArgumentException("검색어가 비어 있습니다"));

    mockMvc
        .perform(
            post("/api/v1/documents/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"  \",\"topK\":5}"))
        .andExpect(status().isBadRequest());
  }
}
