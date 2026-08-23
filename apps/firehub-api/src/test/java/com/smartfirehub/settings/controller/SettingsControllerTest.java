package com.smartfirehub.settings.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.settings.dto.ResolvedSettingResponse;
import com.smartfirehub.settings.dto.UpdateSettingsRequest;
import com.smartfirehub.settings.service.SettingsService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SettingsController WebMvcTest — JaCoCo LINE 커버리지 보강용. 핵심 경로(getSettings /
 * getDecryptedAiApiKey / updateSettings / clearOverride / testSmtpSettings) 각각의 성공 분기만 커버한다.
 */
@WebMvcTest(SettingsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SettingsControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;

  @MockitoBean private SettingsService settingsService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  /** 인증 mock — 유효 토큰 + 주어진 권한 세트를 PermissionInterceptor가 허용하도록 세팅한다. */
  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  /**
   * 테넌트 조회는 <b>해석된</b> 값과 플래그를 돌려준다(P7-b).
   *
   * <p>이전에는 {@code getByPrefix}(플랫폼 기본값만)를 스텁했다. 그대로 두면 오버라이드를 저장한
   * 뒤에도 화면이 예전 값을 보여주는 어긋남을 이 테스트가 승인하게 된다 — 컨트롤러가 어느 서비스
   * 메서드를 부르는지가 곧 계약이므로, 스텁 대상 자체가 단언의 일부다.
   */
  @Test
  void getSettings_withPrefix_returnsResolvedValuesWithFlags() throws Exception {
    mockAuth("ai:settings");
    ResolvedSettingResponse s =
        new ResolvedSettingResponse(
            "ai.model", "tenant-model", "desc", LocalDateTime.now(), true, true);
    when(settingsService.getResolvedByPrefix("ai")).thenReturn(List.of(s));

    mockMvc
        .perform(
            get("/api/v1/settings")
                .param("prefix", "ai")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("ai.model"))
        .andExpect(jsonPath("$[0].value").value("tenant-model"))
        .andExpect(jsonPath("$[0].overridden").value(true))
        .andExpect(jsonPath("$[0].tenantEditable").value(true));
  }

  /**
   * {@code GET /api/v1/settings/ai-api-key} 는 <b>삭제됐다</b>(P7-b Task 7) — 404 다.
   *
   * <p>이 경로는 {@code ai:settings} 권한을 가진 <b>테넌트</b> 관리자에게 {@code ai.api_key} 의
   * <b>복호화 평문</b>을 그대로 돌려줬다. P7-b 가 {@code ai.api_key} 를 플랫폼 소유로 확정하는
   * 순간 그것은 "테넌트 관리자가 플랫폼 자격증명을 평문으로 읽는다"가 되어, 이 밴드가 세우는
   * 경계를 정면으로 무력화한다(다른 모든 읽기 경로는 {@code maskSecret} 을 지나 {@code ****} 만
   * 내보낸다 — 이 엔드포인트만 예외였다). 소비자가 없다는 것을 확인하고 지웠다.
   *
   * <p><b>이 단언은 405 였다가 404 로 돌아왔고, 그 왕복 자체가 기록할 값어치가 있다.</b> Task 5 가
   * {@code DELETE /{key}} 를 추가했을 때 그 매핑이 이 경로를 {@code key="ai-api-key"} 로 삼켜
   * "매핑 없음"이 아니라 "메서드 불허"가 됐다(그때 405 를 실측으로 확인해 고쳤다). simplify 리뷰가
   * 그 흡수를 구조적 위험으로 지적해 경로를 {@code /overrides/{key}} 로 옮기자, 흡수가 사라지고
   * 삭제된 엔드포인트가 다시 정직하게 404 가 된다. 즉 <b>이 404 는 catch-all 이 없어졌다는
   * 증거</b>이기도 하다.
   */
  @Test
  void getDecryptedAiApiKey_endpointRemoved_returnsNotFound() throws Exception {
    mockAuth("ai:settings");

    mockMvc
        .perform(get("/api/v1/settings/ai-api-key").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
  }


  /**
   * 테넌트 쓰기는 <b>테넌트 평면 서비스 메서드</b>로 흘러야 한다.
   *
   * <p>이전 버전은 {@code doNothing()} 스텁 + 204 단언뿐이었다. void 메서드의 mock 은 원래
   * 아무것도 하지 않으므로 그 스텁은 의미가 없고, {@code verify} 가 없으니 <b>핸들러가 어느
   * 서비스 메서드를 부르는지가 전혀 고정되지 않았다</b>. 그 상태에서 핸들러를
   * {@code updatePlatformSettings} 로 바꾸면 — 즉 한 테넌트의 저장이 전역 18행을 덮어쓰는,
   * 이 밴드가 없애려는 바로 그 결함으로 되돌아가면 — mock 이 삼키고 204 가 나가며 저장소의
   * 모든 테스트가 녹색으로 남는다. 그래서 호출 대상과 인자를 명시적으로 검증하고, 플랫폼
   * 경로가 호출되지 <b>않는다</b>는 음성 단언까지 둔다.
   */
  @Test
  void updateSettings_validBody_routesToTenantPlaneService() throws Exception {
    mockAuth("ai:settings");
    UpdateSettingsRequest body = new UpdateSettingsRequest(Map.of("ai.max_turns", "10"));

    mockMvc
        .perform(
            put("/api/v1/settings")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNoContent());

    verify(settingsService).updateSettings(Map.of("ai.max_turns", "10"), 1L);
    verify(settingsService, never()).updatePlatformSettings(any(), any());
  }

  /** 오버라이드가 있든 없든 204다 — "이미 상속 중"은 오류가 아니라 멱등한 성공이다. */
  @Test
  void clearOverride_returnsNoContent() throws Exception {
    mockAuth("ai:settings");

    mockMvc
        .perform(delete("/api/v1/settings/overrides/ai.model").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());

    // verify 로 호출 자체와 키 인자를 고정한다. 204 만 단언하면 핸들러에서 clearOverride 호출을
    // 통째로 지워도(=기능이 컨트롤러에서 사라져도) 통과하고, 경로 변수 전달 회귀도 놓친다.
    verify(settingsService).clearOverride("ai.model");
  }

  /**
   * {@code ai:settings} 가 <b>없으면</b> 재정의 해제는 403 이다.
   *
   * <p>이 파일의 다른 모든 테스트는 필요한 권한을 항상 부여하고 시작한다. 그래서 어느 테스트도
   * "{@code @RequirePermission} 이 실제로 집행되는가"와 "애너테이션은 붙어 있지만 경로가
   * {@code PermissionInterceptor} 에 등록되지 않아 그냥 통과하는가"를 <b>구별하지 못한다</b>. P7-a 가
   * 정확히 그 함정(인터셉터 경로 등록 누락)을 한 번 겪었으므로, 이 밴드가 새로 추가한 유일한 쓰기
   * 경로에는 거부 쪽 단언을 하나 둔다. 여기서 204 가 나오면 애너테이션은 장식일 뿐이고, 권한 없는
   * 테넌트 관리자가 오버라이드를 조용히 지울 수 있다는 뜻이다 — 이 밴드가 세우려는 경계가 그대로
   * 무너진다.
   */
  @Test
  void clearOverride_withoutPermission_returnsForbidden() throws Exception {
    mockAuth("dataset:read");

    mockMvc
        .perform(delete("/api/v1/settings/overrides/ai.model").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  /**
   * 연결 테스트는 <b>저장과 같은 권한</b>({@code ai:settings})을 요구한다.
   *
   * <p>P7-c1 이전 이 라우트만 {@code settings:write} 를 요구했다. SMTP 쓰기가
   * {@code PUT /settings}({@code ai:settings})로 옮겨간 뒤 <b>같은 탭의 저장과 테스트가 서로 다른
   * 권한</b>을 요구하게 됐고, 그러면 {@code ai:settings} 만 가진 롤이 SMTP 자격증명을 저장해 놓고
   * 바로 옆 버튼에서 403 을 받는다. 두 권한 다 오늘은 ADMIN 롤에만 시드돼 있지만(V16/V42) 롤은
   * <b>런타임에 편집 가능</b>하므로 "그런 롤은 존재할 수 없다"에 기댈 수 없다.
   *
   * <p>거부 쪽도 함께 단언한다 — 허용만 보면 애너테이션을 통째로 지워도 통과한다.
   */
  @Test
  void testSmtpSettings_usesSamePermissionAsSave() throws Exception {
    // 저장 권한만 있어도 테스트가 된다(같은 탭의 두 버튼이 갈라지지 않는다).
    mockAuth("ai:settings");
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));
    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk());

    // 옛 권한만 가진 롤은 이제 거부된다 — 그 롤은 GET 조차 못 해 이 탭을 열 수 없다.
    mockAuth("settings:write");
    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  /**
   * {@code /settings/smtp} 에는 <b>어떤 메서드의 라우트도 없다</b> — 404 다.
   *
   * <p>이 테스트는 네 번 바뀌었고 그 궤적이 곧 교훈이다. 처음에는 {@code doNothing()} 스텁 + 204
   * 단언이라 서비스가 <b>항상 거부</b>하게 된 뒤에도 계속 통과했다(거짓을 고정하는 테스트).
   * 다음에는 실제 예외를 재현해 403 을 단언했고, P7-b 가 쓰기 라우트를 지운 뒤에는 405 였다
   * (GET 이 남아 있어 경로 자체는 매핑돼 있었기 때문이다). P7-c1 이 그 GET 마저 지워
   * — 해석기를 타지 않아 <b>틀린 값</b>을 주면서 소비자도 0이 된 경로였다 — 이제 404 다.
   *
   * <p>단언값(405→404)이 바뀐 것 자체가 검증 대상이다. 405 를 그대로 두면 "경로에 무언가 매핑돼
   * 있다"는 사실에 기대는 셈이라, GET 이 되살아나도 테스트는 조용히 통과한다.
   *
   * <p>{@code AccessDeniedException} → 403 매핑은 {@link #clearOverride_withoutPermission_returnsForbidden}
   * 이 계속 지킨다 — 그 단언까지 함께 잃지 않도록 확인하고 지웠다.
   */
  @Test
  void smtpRoutes_removed_returnNotFound() throws Exception {
    mockAuth("ai:settings");

    mockMvc
        .perform(
            put("/api/v1/settings/smtp")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("smtp.host", "localhost"))))
        .andExpect(status().isNotFound());

    mockMvc
        .perform(get("/api/v1/settings/smtp").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNotFound());
  }


  @Test
  void testSmtpSettings_whenHostBlank_returnsFailureMessage() throws Exception {
    mockAuth("ai:settings");
    // 호스트가 비어 있으면 컨트롤러가 success=false 응답을 즉시 반환 — JavaMailSender 생성 로직을 타지 않음
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void testSmtpSettings_invalidHost_returnsCaughtError() throws Exception {
    mockAuth("ai:settings");
    // 실제 연결이 실패하도록 존재하지 않는 호스트를 넣어 JavaMailSenderImpl 경로 전체를 타게 한다
    when(settingsService.getSmtpConfig())
        .thenReturn(
            Map.of(
                "smtp.host", "invalid.nonexistent.example.invalid",
                "smtp.port", "25",
                "smtp.username", "u",
                "smtp.password", "p",
                "smtp.starttls", "true"));

    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
  }
}
