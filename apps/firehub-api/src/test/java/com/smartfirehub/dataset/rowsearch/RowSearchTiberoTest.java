package com.smartfirehub.dataset.rowsearch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dataset.rowsearch.dto.SearchIndexStatusResponse;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.DataStoreResolver;
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
import org.springframework.test.web.servlet.ResultActions;

/** Tibero 테넌트는 행 검색 네 엔드포인트 모두 판정·DB 접근 전에 422 로 막고, PostgreSQL 은 기존대로 위임한다(WD-80). */
@SuppressWarnings("null")
@WebMvcTest(RowSearchController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RowSearchBackendGuard.class})
class RowSearchTiberoTest {

  private static final String MESSAGE = "행 검색: Tibero 저장소에서 아직 지원하지 않습니다";

  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private DataStoreResolver dataStoreResolver;
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
    when(permissionService.getUserPermissions(1L))
        .thenReturn(Set.of("dataset:read", "dataset:write", "data:read"));
  }

  private void asTibero() {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
  }

  private static void expectUnsupported(ResultActions r) throws Exception {
    r.andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("DATA_STORE_FEATURE_UNSUPPORTED"))
        .andExpect(jsonPath("$.message").value(MESSAGE));
  }

  private void verifyNoDelegation() {
    verifyNoInteractions(settingsService, rowSearchService);
  }

  @Test
  void tibero_getSearchIndex_is422() throws Exception {
    asTibero();
    expectUnsupported(
        mockMvc.perform(
            get("/api/v1/datasets/1/search-index").header("Authorization", "Bearer test-token")));
    verifyNoDelegation();
  }

  @Test
  void tibero_putSearchIndex_is422() throws Exception {
    asTibero();
    expectUnsupported(
        mockMvc.perform(
            put("/api/v1/datasets/1/search-index")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fields\":[\"content\"]}")));
    verifyNoDelegation();
  }

  @Test
  void tibero_reindex_is422() throws Exception {
    asTibero();
    expectUnsupported(
        mockMvc.perform(
            post("/api/v1/datasets/1/search-index/reindex")
                .header("Authorization", "Bearer test-token")));
    verifyNoDelegation();
  }

  @Test
  void tibero_rowsSearch_is422() throws Exception {
    asTibero();
    expectUnsupported(
        mockMvc.perform(
            post("/api/v1/datasets/1/rows/search")
                .header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"x\"}")));
    verifyNoDelegation();
  }

  @Test
  void postgres_keepsExistingBehaviour() throws Exception {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    when(settingsService.getStatus(1L)).thenReturn(SearchIndexStatusResponse.off());
    mockMvc
        .perform(
            get("/api/v1/datasets/1/search-index").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("OFF"));
    verify(settingsService).getStatus(1L);
    mockMvc
        .perform(
            post("/api/v1/datasets/1/search-index/reindex")
                .header("Authorization", "Bearer test-token"))
        .andExpect(status().isAccepted());
    verify(settingsService).reindex(1L);
    verifyNoInteractions(rowSearchService);
    org.mockito.Mockito.verify(settingsService, org.mockito.Mockito.never())
        .update(anyLong(), any());
  }
}
