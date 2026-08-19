package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 두 화이트리스트 사이의 부분집합 불변식을 실행 가능한 단언으로 고정한다.
 *
 * <p>이 파일이 {@code com.smartfirehub.settings.service} 패키지에 있는 이유: 불변식을 검사하려면
 * {@code SettingsService.ALLOWED_AI_KEYS}(패키지 가시성)를 읽어야 한다. 상수를 {@code public} 으로
 * 열어 테스트 하나를 편하게 하는 것보다, 테스트를 상수가 사는 패키지로 옮기는 쪽이 노출이 작다.
 */
class SettingsKeyWhitelistInvariantTest {

  /**
   * 테넌트 재정의 허용 6키는 플랫폼 쓰기 허용 9키의 <b>부분집합</b>이어야 한다.
   *
   * <p>이 불변식은 지금까지 {@code SettingsService} javadoc <b>문장으로만</b> 존재했다. 깨지면
   * "테넌트는 재정의할 수 있는데 플랫폼은 기본값을 못 정하는 키"가 생긴다 — 이 밴드가 실제로 겪은
   * {@code ai.session_max_tokens} 결함(플랫폼 저장이 조용히 무시됨)과 정확히 같은 모양의 비대칭이고,
   * 2단 상속의 윗단이 비는 것이다. 정책에 키를 추가하면서 {@code ALLOWED_AI_KEYS} 를 잊는 순간
   * 여기서 걸린다.
   */
  @Test
  void 테넌트_허용_키는_플랫폼_허용_키의_부분집합이다() {
    assertThat(SettingsService.ALLOWED_AI_KEYS)
        .containsAll(SettingsOverridePolicy.tenantOverridableKeys());
  }
}
