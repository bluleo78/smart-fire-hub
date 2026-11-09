package com.smartfirehub.dataset.storage;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 저장소 기능 응답: 백엔드별 값과 "인증만 요구"(권한이 하나도 없어도 조회 가능)를 검증한다(WD-80). */
@SuppressWarnings("null")
@WebMvcTest(DataStoreCapabilitiesController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class DataStoreCapabilitiesControllerTest {

  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private DataStoreResolver dataStoreResolver;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;
  @Autowired private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    when(jwtTokenProvider.parseAccessToken("test-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of());
  }

  @Test
  void postgres_supportsEverything() throws Exception {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.POSTGRESQL);
    mockMvc
        .perform(
            get("/api/v1/data-store/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.backend").value("POSTGRESQL"))
        .andExpect(jsonPath("$.geometry").value(true))
        .andExpect(jsonPath("$.rowSearch").value(true))
        // WD-636: 웹 폼 VARCHAR 길이 상한 — PG 는 10000.
        .andExpect(jsonPath("$.maxVarcharLength").value(10000))
        // WD-659: 길이 미지정 VARCHAR 의 실제 길이 — PG 는 VARCHAR(255).
        .andExpect(jsonPath("$.defaultVarcharLength").value(255));
  }

  @Test
  void tibero_supportsNeither() throws Exception {
    when(dataStoreResolver.currentBackend()).thenReturn(DataStoreBackend.TIBERO);
    mockMvc
        .perform(
            get("/api/v1/data-store/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.backend").value("TIBERO"))
        .andExpect(jsonPath("$.geometry").value(false))
        .andExpect(jsonPath("$.rowSearch").value(false))
        // WD-636: Tibero VARCHAR2 상한 4000 — 서버가 DDL 전에 거부하는 값과 같다.
        .andExpect(jsonPath("$.maxVarcharLength").value(4000))
        // WD-659: Tibero 의 길이 미지정 VARCHAR 는 VARCHAR2(4000 CHAR).
        .andExpect(jsonPath("$.defaultVarcharLength").value(4000));
  }

  @Test
  void withoutToken_isRejected() throws Exception {
    mockMvc.perform(get("/api/v1/data-store/capabilities")).andExpect(status().isUnauthorized());
  }
}
