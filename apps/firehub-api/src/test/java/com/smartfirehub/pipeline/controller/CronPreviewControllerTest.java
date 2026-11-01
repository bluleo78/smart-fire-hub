package com.smartfirehub.pipeline.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.global.config.SecurityConfig;
import com.smartfirehub.global.security.JwtAuthenticationFilter;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.proactive.controller.ProactiveJobController;
import com.smartfirehub.proactive.service.PdfExportService;
import com.smartfirehub.proactive.service.ProactiveJobService;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.util.Optional;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * cron 미리보기 API (WD-391) — 파이프라인 트리거({@code GET /api/v1/triggers/cron-preview}, trigger:read)와 스마트
 * 작업({@code GET /api/v1/proactive/jobs/cron-preview}, proactive:read) 두 엔드포인트의 권한·응답·오류 문구를 검증한다.
 *
 * <p>서머타임 계산 자체는 {@code DstAwareCronTest}·{@code CronPreviewTest} 가 고정 시각으로 검증한다. 여기서는 지금 시각 기준이라
 * 서머타임 없는 Asia/Seoul 로 값을 확인한다.
 */
@WebMvcTest({TriggerCronPreviewController.class, ProactiveJobController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class CronPreviewControllerTest {

  private static final String TRIGGER_URL = "/api/v1/triggers/cron-preview";
  private static final String PROACTIVE_URL = "/api/v1/proactive/jobs/cron-preview";

  // WebMvcConfig 가 DatasetAccessInterceptor(→DatasetAccessGuard)를 등록하므로 슬라이스에도 빈이 있어야 한다.
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private ProactiveJobService proactiveJobService;
  @MockitoBean private PdfExportService pdfExportService;
  @MockitoBean private PermissionService permissionService;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;

  @Autowired private MockMvc mockMvc;

  private void mockAuth(String... permissions) {
    when(jwtTokenProvider.parseAccessToken("valid-token"))
        .thenReturn(Optional.of(new JwtTokenProvider.AccessTokenPrincipal(1L, null, false)));
    when(permissionService.getUserPermissions(1L)).thenReturn(Set.of(permissions));
  }

  @ParameterizedTest
  @ValueSource(strings = {TRIGGER_URL, PROACTIVE_URL})
  void preview_returnsNextFireTimesAsUtcInstants(String url) throws Exception {
    mockAuth("trigger:read", "proactive:read");

    // 서울 매일 09:00 = UTC 00:00. 5필드는 6필드로 정규화돼 돌아온다.
    mockMvc
        .perform(
            get(url)
                .param("cron", "0 9 * * *")
                .param("timezone", "Asia/Seoul")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.cron").value("0 0 9 * * *"))
        .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
        .andExpect(jsonPath("$.nextFireTimes.length()").value(5))
        .andExpect(
            jsonPath("$.nextFireTimes[*]")
                .value(
                    Matchers.everyItem(Matchers.matchesRegex("\\d{4}-\\d{2}-\\d{2}T00:00:00Z"))));
  }

  @Test
  void preview_countAndDefaultTimezone() throws Exception {
    mockAuth("proactive:read");

    mockMvc
        .perform(
            get(PROACTIVE_URL)
                .param("cron", "0 0 9 * * *")
                .param("count", "1")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value("Asia/Seoul"))
        .andExpect(jsonPath("$.nextFireTimes.length()").value(1));
  }

  @Test
  void preview_withoutAuth_returnsUnauthorized() throws Exception {
    mockMvc
        .perform(get(TRIGGER_URL).param("cron", "0 9 * * *"))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(get(PROACTIVE_URL).param("cron", "0 9 * * *"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void triggerPreview_requiresTriggerRead() throws Exception {
    // 스마트 작업 권한만 있으면 트리거 미리보기는 막힌다
    mockAuth("proactive:read");
    mockMvc
        .perform(
            get(TRIGGER_URL)
                .param("cron", "0 9 * * *")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @Test
  void proactivePreview_requiresProactiveRead() throws Exception {
    mockAuth("trigger:read");
    mockMvc
        .perform(
            get(PROACTIVE_URL)
                .param("cron", "0 9 * * *")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(strings = {TRIGGER_URL, PROACTIVE_URL})
  void preview_invalidCron_returnsKoreanBadRequest(String url) throws Exception {
    mockAuth("trigger:read", "proactive:read");

    mockMvc
        .perform(
            get(url)
                .param("cron", "61 9 * * *")
                .param("timezone", "Asia/Seoul")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.message")
                .value(
                    "유효하지 않은 cron 표현식입니다: \"61 9 * * *\" — 「분」 칸의 값 \"61\" 이(가) 올바르지 않습니다 (허용 범위 0~59)"));
  }

  @Test
  void preview_tooFrequent_returnsKoreanBadRequest() throws Exception {
    mockAuth("trigger:read");

    mockMvc
        .perform(
            get(TRIGGER_URL)
                .param("cron", "* * * * * *")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(Matchers.startsWith("실행 주기는 1분보다 짧게 할 수 없습니다")));
  }

  @ParameterizedTest
  @ValueSource(strings = {TRIGGER_URL, PROACTIVE_URL})
  void preview_invalidTimezone_returnsKoreanBadRequest(String url) throws Exception {
    mockAuth("trigger:read", "proactive:read");

    mockMvc
        .perform(
            get(url)
                .param("cron", "0 9 * * *")
                .param("timezone", "Not/AZone")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("유효하지 않은 시간대입니다: Not/AZone"));
  }

  @Test
  void preview_blankCronOrCountOutOfRange_returnsBadRequest() throws Exception {
    mockAuth("trigger:read");

    mockMvc
        .perform(get(TRIGGER_URL).param("cron", " ").header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(Matchers.startsWith("cron 표현식을 입력하세요")));
    mockMvc
        .perform(
            get(TRIGGER_URL)
                .param("cron", "0 9 * * *")
                .param("count", "11")
                .header("Authorization", "Bearer valid-token"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(Matchers.startsWith("미리보기 개수(count)는 1~10")));
  }
}
