package com.smartfirehub.notification.settings;

import com.smartfirehub.notification.ChannelType;
import com.smartfirehub.notification.settings.dto.ChannelPreferenceRequest;
import com.smartfirehub.notification.settings.dto.ChannelSettingResponse;
import com.smartfirehub.notification.settings.dto.ChannelTestResult;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 사용자 채널 설정 API 컨트롤러.
 *
 * <p>인증된 사용자가 자신의 채널별 알림 수신 설정 조회·변경·연동 해제를 수행한다. 모든 엔드포인트는 JWT 인증 필요 (Security Filter에서 처리).
 *
 * <p>GET /api/v1/channels/settings — 전체 채널 설정 조회 PATCH
 * /api/v1/channels/settings/{channel}/preference — 알림 수신 여부 변경 DELETE
 * /api/v1/channels/settings/{channel} — 외부 binding 해제
 */
@RestController
@RequestMapping("/api/v1/channels/settings")
public class ChannelSettingsController {

  private final ChannelSettingsService channelSettingsService;

  public ChannelSettingsController(ChannelSettingsService channelSettingsService) {
    this.channelSettingsService = channelSettingsService;
  }

  /**
   * 현재 사용자의 모든 채널 설정 조회.
   *
   * @param authentication JWT 필터가 주입한 인증 객체 (principal = userId Long)
   * @return CHAT · EMAIL · KAKAO · SLACK 채널 설정 목록
   */
  @GetMapping
  public List<ChannelSettingResponse> getSettings(Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    return channelSettingsService.getSettings(userId);
  }

  /**
   * 채널 알림 수신 여부 변경.
   *
   * <p>CHAT 채널은 항상 활성이므로 변경 시 400 응답 (GlobalExceptionHandler에서 처리).
   *
   * @param channel URL 경로의 채널 이름 (대소문자 무관)
   * @param request 활성화 여부
   * @param authentication 인증 객체
   */
  @PatchMapping("/{channel}/preference")
  public ResponseEntity<Void> updatePreference(
      @PathVariable("channel") String channel,
      @RequestBody ChannelPreferenceRequest request,
      Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ChannelType channelType = ChannelType.valueOf(channel.toUpperCase());
    channelSettingsService.updatePreference(userId, channelType, request.enabled());
    return ResponseEntity.noContent().build();
  }

  /**
   * 채널 외부 binding 해제.
   *
   * <p>CHAT·EMAIL은 해제 불가 (400). binding이 없는 경우는 no-op으로 204 반환.
   *
   * @param channel URL 경로의 채널 이름 (대소문자 무관)
   * @param authentication 인증 객체
   */
  @DeleteMapping("/{channel}")
  public ResponseEntity<Void> disconnectBinding(
      @PathVariable("channel") String channel, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ChannelType channelType = ChannelType.valueOf(channel.toUpperCase());
    channelSettingsService.disconnectBinding(userId, channelType);
    return ResponseEntity.noContent().build();
  }

  /**
   * 채널 테스트 발송.
   *
   * <p>사용자가 /settings/channels 페이지의 채널 카드에서 "테스트 발송" 버튼을 누르면 호출. 해당 채널로 즉시 동기 발송하여
   * 결과(success/message)를 반환한다. SMTP 테스트 엔드포인트와 동일한 응답 형태로 프론트엔드 토스트 처리 로직을 통일한다.
   *
   * <p>발송 자체가 실패해도 200 OK + {@code success: false}로 반환 (네트워크 오류와 구분 — 사용자에게 사유 안내).
   *
   * @param channel URL 경로의 채널 이름 (대소문자 무관). CHAT은 400 (테스트 불필요).
   * @param authentication 인증 객체
   * @return 발송 결과
   */
  @PostMapping("/{channel}/test")
  public ResponseEntity<ChannelTestResult> testChannel(
      @PathVariable("channel") String channel, Authentication authentication) {
    Long userId = (Long) authentication.getPrincipal();
    ChannelType channelType = ChannelType.valueOf(channel.toUpperCase());
    return ResponseEntity.ok(channelSettingsService.testChannel(userId, channelType));
  }
}
