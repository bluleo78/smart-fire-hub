package com.smartfirehub.global.platformdb;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformDbFeatures;
import com.smartfirehub.global.platformdb.tibero.TiberoPlatformDbFeatures;
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

/** 플랫폼 DB 기능 응답: 백엔드별 값과 "인증만 요구"(권한이 하나도 없어도 조회 가능)를 검증한다(WD-495). */
@SuppressWarnings("null")
@WebMvcTest(PlatformDbCapabilitiesController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class PlatformDbCapabilitiesControllerTest {

  @MockitoBean private PlatformDbFeatures features;
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
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
  void postgres_구현이면_두_기능_모두_true() throws Exception {
    var pg = new PostgresPlatformDbFeatures();
    when(features.supports(any())).thenAnswer(i -> pg.supports(i.getArgument(0)));
    mockMvc
        .perform(
            get("/api/v1/platform-db/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vectorSearch").value(true))
        .andExpect(jsonPath("$.trigramSearch").value(true));
  }

  @Test
  void tibero_구현이면_두_기능_모두_false() throws Exception {
    var tibero = new TiberoPlatformDbFeatures();
    when(features.supports(any())).thenAnswer(i -> tibero.supports(i.getArgument(0)));
    mockMvc
        .perform(
            get("/api/v1/platform-db/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vectorSearch").value(false))
        .andExpect(jsonPath("$.trigramSearch").value(false));
  }

  // 두 기능을 서로 바꿔 매핑하는 실수를 잡는다 — 하나만 켜진 경우 각 필드가 제 기능만 따라가야 한다.
  @Test
  void 벡터만_켜져_있으면_vectorSearch_만_true() throws Exception {
    when(features.supports(PlatformDbFeature.VECTOR_SEARCH)).thenReturn(true);
    when(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).thenReturn(false);
    mockMvc
        .perform(
            get("/api/v1/platform-db/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vectorSearch").value(true))
        .andExpect(jsonPath("$.trigramSearch").value(false));
  }

  @Test
  void 트라이그램만_켜져_있으면_trigramSearch_만_true() throws Exception {
    when(features.supports(PlatformDbFeature.VECTOR_SEARCH)).thenReturn(false);
    when(features.supports(PlatformDbFeature.TRIGRAM_SEARCH)).thenReturn(true);
    mockMvc
        .perform(
            get("/api/v1/platform-db/capabilities").header("Authorization", "Bearer test-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.vectorSearch").value(false))
        .andExpect(jsonPath("$.trigramSearch").value(true));
  }

  @Test
  void 토큰이_없으면_401() throws Exception {
    mockMvc.perform(get("/api/v1/platform-db/capabilities")).andExpect(status().isUnauthorized());
  }
}
