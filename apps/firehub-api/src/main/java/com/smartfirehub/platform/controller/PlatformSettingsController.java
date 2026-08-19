package com.smartfirehub.platform.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.service.SettingsService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 기본 설정 조회·쓰기(운영자 전용). 18키 전체를 대상으로 {@code system_settings} 를 읽고
 * 쓴다.
 *
 * <p><b>쓰기(P7-b Task 6)</b>: {@code PUT} 은 {@link SettingsService#updatePlatformSettings} 로
 * 위임한다 — 검증·마스킹·암호화 로직은 Task 5 가 {@code SettingsService} 에 남겨 둔 것과
 * <b>동일한 코드</b>를 재사용한다(플랫폼 쓰기가 검증을 다시 구현하면 테넌트 평면과 값 규칙이
 * 어긋난다). 테넌트 ADMIN 의 {@code /api/v1/settings} 쓰기는 Task 5 부터 화이트리스트 6키로
 * 좁혀져 {@code tenant_settings} 만 건드리므로, 이 엔드포인트와 같은 전역 18행을 두고 경쟁하지
 * 않는다.
 *
 * <p>설계서 §4.5 참고 — 이 밴드가 끝난 시점에 설정의 테넌트 구분 결함(한 테넌트의 저장이 전
 * 테넌트에 적용되던 문제)은 해소된다. 테넌트 평면은 자기 오버라이드만, 플랫폼 평면은 이 엔드포인트로
 * 전역 기본값만 쓴다.
 */
@RestController
@RequestMapping("/api/platform/settings")
@RequiredArgsConstructor
public class PlatformSettingsController {

  private final SettingsService settingsService;

  /** 플랫폼 기본 설정 전체. 비밀값(ai.api_key / ai.cli_oauth_token / embedding.api_key)은 마스킹된다. */
  @GetMapping
  @RequirePermission("platform:settings:read")
  public ResponseEntity<List<SettingResponse>> getAll() {
    return ResponseEntity.ok(settingsService.getAll());
  }

  /**
   * 플랫폼 기본 설정 갱신. AI·임베딩·SMTP 키를 한 번에 받을 수 있다(부분 갱신 허용). 마스킹된
   * 센티널({@code ****xxxx})은 "기존 값 유지"로 해석되어 살아 있는 자격증명을 덮어쓰지 않는다
   * ({@code SettingsService} 의 {@code isMaskedApiKey} 필터를 그대로 지난다).
   */
  @PutMapping
  @RequirePermission("platform:settings:write")
  public ResponseEntity<Void> updateAll(
      Authentication authentication, @RequestBody Map<String, String> settings) {
    Long userId = (Long) authentication.getPrincipal();
    settingsService.updatePlatformSettings(settings, userId);
    return ResponseEntity.noContent().build();
  }
}
