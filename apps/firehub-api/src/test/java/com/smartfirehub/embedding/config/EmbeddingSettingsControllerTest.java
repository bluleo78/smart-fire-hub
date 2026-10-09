package com.smartfirehub.embedding.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.embedding.config.dto.EmbeddingConfigRequest;
import com.smartfirehub.embedding.config.dto.EmbeddingConfigView;
import com.smartfirehub.embedding.config.dto.EmbeddingProbeResponse;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

/** 매핑·권한(ai:settings)·400 번역만 본다. 흐름은 EmbeddingSettingsServiceTest 가 본다. */
@SuppressWarnings("null")
@WebMvcTest(EmbeddingSettingsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class EmbeddingSettingsControllerTest {

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  // 목은 아무것도 던지지 않아 숨김 판정은 통과 처리된다(실제 판정은 DatasetRouteHidingTest 가 검증).
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;

  @Autowired private MockMvc mockMvc;
  @MockitoBean private EmbeddingSettingsService settingsService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  private static final String BODY =
      "{\"provider\":\"OLLAMA\",\"model\":\"bge-m3\",\"baseUrl\":\"http://host.docker.internal:11434\"}";

  @BeforeEach
  void auth() {
    when(jwtTokenProvider.parseAccessToken("admin"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("ai:settings"));
    when(jwtTokenProvider.parseAccessToken("viewer"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(2L, null, false)));
    when(permissionService.getUserPermissions(2L)).thenReturn(Set.of("dataset:read"));
  }

  @Test
  void getReturnsView() throws Exception {
    when(settingsService.view())
        .thenReturn(
            new EmbeddingConfigView(
                true, "OLLAMA", "bge-m3", "http://h:11434", 1024, "", "EXTERNAL"));
    mockMvc
        .perform(get("/api/v1/settings/embedding").header("Authorization", "Bearer admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.dimension").value(1024));
  }

  @Test
  void testReturnsMeasuredDimension() throws Exception {
    when(settingsService.test(any())).thenReturn(new EmbeddingProbeResponse(1536));
    mockMvc
        .perform(
            post("/api/v1/settings/embedding/test")
                .header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dimension").value(1536));
  }

  @Test
  void putDelegatesWithUserId() throws Exception {
    when(settingsService.save(any(), eq(1L)))
        .thenReturn(
            new EmbeddingConfigView(
                true, "OLLAMA", "bge-m3", "http://h:11434", 1024, "", "EXTERNAL"));
    mockMvc
        .perform(
            put("/api/v1/settings/embedding")
                .header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk());
    verify(settingsService)
        .save(
            eq(
                new EmbeddingConfigRequest(
                    "OLLAMA", "bge-m3", "http://host.docker.internal:11434", null, null)),
            eq(1L));
  }

  @Test
  void validationFailureIs400WithMessage() throws Exception {
    when(settingsService.test(any()))
        .thenThrow(new IllegalArgumentException("지원하지 않는 차원 768 (지원: 1024, 1536)"));
    mockMvc
        .perform(
            post("/api/v1/settings/embedding/test")
                .header("Authorization", "Bearer admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("지원하지 않는 차원 768 (지원: 1024, 1536)"));
  }

  @Test
  void withoutAiSettingsPermissionIs403() throws Exception {
    mockMvc
        .perform(get("/api/v1/settings/embedding").header("Authorization", "Bearer viewer"))
        .andExpect(status().isForbidden());
  }

  @Test
  void impactPassesQueryParams() throws Exception {
    when(settingsService.impact("bge-m3", 1536))
        .thenReturn(new com.smartfirehub.embedding.config.dto.EmbeddingImpact(10, 2, 1));
    mockMvc
        .perform(
            get("/api/v1/settings/embedding/impact")
                .param("model", "bge-m3")
                .param("dimension", "1536")
                .header("Authorization", "Bearer admin"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.chunks").value(10))
        .andExpect(jsonPath("$.rowSearchIndexes").value(1));
  }
}
