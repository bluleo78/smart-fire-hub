package com.smartfirehub.platform.controller;

import com.smartfirehub.global.security.RequirePermission;
import com.smartfirehub.settings.dto.SettingResponse;
import com.smartfirehub.settings.service.SettingsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 플랫폼 기본 설정 <b>조회</b>(운영자 전용). 18키 전체를 돌려주고 비밀값은 마스킹된다.
 *
 * <p><b>쓰기는 의도적으로 없다 — P7-b 로 미룬다.</b> 테넌트 ADMIN 의 {@code /api/v1/settings} 쓰기
 * 경로가 P7-b 까지 살아 있어서, 지금 플랫폼 쓰기를 함께 열면 <b>같은 전역 18행을 두 평면이 조율 없이
 * 쓴다</b> — 오늘의 결함(한 테넌트가 저장하면 전 테넌트에 적용)에 last-write-wins 경쟁을 하나 더
 * 얹는 셈이라 지금보다 엄격히 나쁘다. 편집 능력 자체는 기존 경로로 이미 존재하므로 잃는 기능은 없다.
 * P7-b 가 {@code tenant_settings} 와 오버라이드 화이트리스트를 넣을 때 쓰기를 정상 경로로 만든다
 * ({@code platform:settings:write} 권한 코드는 V113 에서 이미 만들어 뒀다).
 *
 * <p>설계서 §4.5 참고 — 이 밴드가 끝난 시점에도 설정의 테넌트 구분 결함은 그대로 남아 있고, 이
 * 밴드는 그것을 고칠 <b>자리</b>를 만든다.
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
}
