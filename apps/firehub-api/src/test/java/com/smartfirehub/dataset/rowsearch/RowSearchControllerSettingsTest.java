package com.smartfirehub.dataset.rowsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.smartfirehub.dataset.rowsearch.dto.SearchIndexStatusResponse;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
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

/** 검색 설정 엔드포인트: 권한·검증·위임. */
@SuppressWarnings("null")
@WebMvcTest(RowSearchController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class RowSearchControllerSettingsTest {

  @Autowired private MockMvc mockMvc;
  @MockitoBean private SearchIndexSettingsService settingsService;
  @MockitoBean private RowSearchService rowSearchService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(jwtTokenProvider.parseAccessToken("reader-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(2L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of("dataset:read", "dataset:write", "data:read"));
    when(permissionService.getUserPermissions(2L)).thenReturn(Set.of("dataset:read"));
  }

  @Test
  void get_returnsStatus() throws Exception {
    when(settingsService.getStatus(1L)).thenReturn(SearchIndexStatusResponse.off());
    mockMvc.perform(get("/api/v1/datasets/1/search-index").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.status").value("OFF"));
  }

  @Test
  void put_delegatesFields() throws Exception {
    when(settingsService.update(1L, List.of("content"))).thenReturn(SearchIndexStatusResponse.off());
    mockMvc.perform(put("/api/v1/datasets/1/search-index").header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[\"content\"]}"))
        .andExpect(status().isOk());
    verify(settingsService).update(1L, List.of("content"));
  }

  @Test
  void put_missingFields_is400() throws Exception {
    mockMvc.perform(put("/api/v1/datasets/1/search-index").header("Authorization", "Bearer test-token")
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void reindex_is202() throws Exception {
    mockMvc.perform(post("/api/v1/datasets/1/search-index/reindex").header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted());
  }

  @Test
  void put_withoutWritePermission_is403() throws Exception {
    mockMvc.perform(put("/api/v1/datasets/1/search-index").header("Authorization", "Bearer reader-token")
            .contentType(MediaType.APPLICATION_JSON).content("{\"fields\":[]}"))
        .andExpect(status().isForbidden());
    verify(settingsService, never()).update(anyLong(), any());
  }
}
