package com.smartfirehub.document.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.document.repository.DocumentChunkRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 청크 bulk-read 엔드포인트의 인증·200 응답을 검증한다. */
@WebMvcTest(DocumentChunkController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DocumentChunkControllerTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private DocumentChunkRepository chunkRepository;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @BeforeEach
  void setUp() {
    // 인증 mock — 유효 토큰 + dataset:read 권한을 PermissionInterceptor가 허용하도록 세팅한다.
    when(jwtTokenProvider.validateAccessToken("valid-token")).thenReturn(true);
    when(jwtTokenProvider.getUserIdFromToken("valid-token")).thenReturn(1L);
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read"));
  }

  @Test
  void 청크목록_엔드포인트는_인증된_요청에_200과_청크목록을_반환한다() throws Exception {
    when(chunkRepository.findChunkContentsByDataset(1L))
        .thenReturn(List.of(new DocumentChunkRepository.ChunkContent(10L, "본문")));

    mockMvc
        .perform(
            get("/api/v1/datasets/{datasetId}/document-chunks", 1L)
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].chunkId").value(10))
        .andExpect(jsonPath("$[0].content").value("본문"));
  }

  @Test
  void 청크목록_엔드포인트는_dataset_read_권한이_없으면_403을_반환한다() throws Exception {
    // dataset:read 권한이 없는 사용자로 재정의 — PermissionInterceptor가 요청을 차단해야 한다.
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());

    mockMvc
        .perform(
            get("/api/v1/datasets/{datasetId}/document-chunks", 1L)
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }
}
