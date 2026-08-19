package com.smartfirehub.settings.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
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
import com.smartfirehub.settings.dto.SettingResponse;
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
 * SettingsController WebMvcTest — JaCoCo LINE 커버리지 보강용. 핵심 경로(getSettings / getDecryptedAiApiKey /
 * updateSettings / getSmtpSettings / updateSmtpSettings / testSmtpSettings) 각각의 성공 분기만 커버한다.
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
   * {@code GET /api/v1/settings/ai-api-key} 는 <b>삭제됐다</b>(P7-b Task 7) — 404 여야 한다.
   *
   * <p>이 경로는 {@code ai:settings} 권한을 가진 <b>테넌트</b> 관리자에게 {@code ai.api_key} 의
   * <b>복호화 평문</b>을 그대로 돌려줬다. P7-b 가 {@code ai.api_key} 를 플랫폼 소유로 확정하는
   * 순간 그것은 "테넌트 관리자가 플랫폼 자격증명을 평문으로 읽는다"가 되어, 이 밴드가 세우는
   * 경계를 정면으로 무력화한다(다른 모든 읽기 경로는 {@code maskSecret} 을 지나 {@code ****} 만
   * 내보낸다 — 이 엔드포인트만 예외였다).
   *
   * <p>소비자가 없다는 것을 확인하고 지웠다: web 의 {@code #ai-api-key} 는 입력 필드의 HTML id 일
   * 뿐이고, ai-agent 는 이 경로를 역호출하던 구조를 이미 버렸다(호출부에 그 사실이 주석으로 남아
   * 있다). 즉 기능 손실이 없다.
   *
   * <p><b>404 가 아니라 405 다.</b> Task 5 가 추가한 {@code DELETE /api/v1/settings/{key}} 매핑이
   * 이 경로를 {@code key="ai-api-key"} 로 흡수하므로, GET 은 "매핑 없음"이 아니라 "메서드 불허"가
   * 된다. 실측으로 확인한 값을 단언한다 — 삭제 후 상태를 연역으로 404 라고 적으면 테스트가 처음부터
   * 실패한다(실제로 그렇게 적어 한 번 실패했다). 참고로 그 {@code DELETE} 로 이 경로를 부르면
   * 존재하지 않는 오버라이드 키를 지우려는 멱등 호출이 되어 아무 일도 일어나지 않는다.
   */
  @Test
  void getDecryptedAiApiKey_endpointRemoved_returnsMethodNotAllowed() throws Exception {
    mockAuth("ai:settings");

    mockMvc
        .perform(get("/api/v1/settings/ai-api-key").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void updateSettings_validBody_returnsNoContent() throws Exception {
    mockAuth("ai:settings");
    doNothing().when(settingsService).updateSettings(any(), anyLong());
    UpdateSettingsRequest body = new UpdateSettingsRequest(Map.of("ai.max_turns", "10"));

    mockMvc
        .perform(
            put("/api/v1/settings")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isNoContent());
  }

  /** 오버라이드가 있든 없든 204다 — "이미 상속 중"은 오류가 아니라 멱등한 성공이다. */
  @Test
  void clearOverride_returnsNoContent() throws Exception {
    mockAuth("ai:settings");
    doNothing().when(settingsService).clearOverride("ai.model");

    mockMvc
        .perform(delete("/api/v1/settings/ai.model").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isNoContent());
  }

  @Test
  void getSmtpSettings_returnsList() throws Exception {
    mockAuth("settings:write");
    when(settingsService.getSmtpSettings())
        .thenReturn(
            List.of(new SettingResponse("smtp.host", "localhost", null, LocalDateTime.now())));

    mockMvc
        .perform(get("/api/v1/settings/smtp").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].key").value("smtp.host"));
  }

  /**
   * 테넌트 평면 SMTP 쓰기는 403 이다(P7-b Task 5).
   *
   * <p>이전 버전은 서비스를 {@code doNothing()} 으로 스텁하고 204 를 단언했다 — 서비스가 실제로는
   * 항상 거부하게 된 뒤에도 <b>스텁 때문에 계속 통과하는</b> 테스트였다. 즉 "이 엔드포인트는
   * 성공한다"는 거짓을 고정하고 있었다. 실제 서비스가 던지는 예외를 재현해, 그것이
   * {@code GlobalExceptionHandler} 를 지나 500 이 아니라 <b>403</b> 으로 나가는지까지 확인한다.
   */
  @Test
  void updateSmtpSettings_onTenantPlane_returnsForbidden() throws Exception {
    mockAuth("settings:write");
    doThrow(new org.springframework.security.access.AccessDeniedException("SMTP 설정은 플랫폼 관리자만 변경할 수 있습니다"))
        .when(settingsService)
        .updateSmtpSettings(any(), eq(1L));

    mockMvc
        .perform(
            put("/api/v1/settings/smtp")
                .header("Authorization", "Bearer valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("smtp.host", "localhost"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void testSmtpSettings_whenHostBlank_returnsFailureMessage() throws Exception {
    mockAuth("settings:write");
    // 호스트가 비어 있으면 컨트롤러가 success=false 응답을 즉시 반환 — JavaMailSender 생성 로직을 타지 않음
    when(settingsService.getSmtpConfig()).thenReturn(Map.of("smtp.host", ""));

    mockMvc
        .perform(post("/api/v1/settings/smtp/test").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void testSmtpSettings_invalidHost_returnsCaughtError() throws Exception {
    mockAuth("settings:write");
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
