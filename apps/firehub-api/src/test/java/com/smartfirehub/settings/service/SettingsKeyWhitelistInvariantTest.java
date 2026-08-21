package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 화이트리스트 사이의 부분집합 불변식을 실행 가능한 단언으로 고정한다.
 *
 * <p>이 파일이 {@code com.smartfirehub.settings.service} 패키지에 있는 이유: 불변식을 검사하려면
 * {@code SettingsService} 의 패키지 가시성 상수들을 읽어야 한다. 상수를 {@code public} 으로 열어
 * 테스트 하나를 편하게 하는 것보다, 테스트를 상수가 사는 패키지로 옮기는 쪽이 노출이 작다.
 */
class SettingsKeyWhitelistInvariantTest {

  /**
   * 테넌트 오버라이드 허용 키는 전부 "플랫폼 평면이 쓸 수 있는 키"의 부분집합이어야 한다.
   *
   * <p><b>왜 합집합인가.</b> P7-b 까지 이 단언은 {@code ALLOWED_AI_KEYS} 하나만 봤다. 그때는
   * 그것으로 충분했는데 <b>테넌트 허용 키가 전부 {@code ai.*} 였기 때문일 뿐</b>이고, 불변식
   * 자체가 "AI 키여야 한다"였던 적은 없다. P7-c1 이 SMTP 를 열자 그 우연이 깨졌다.
   *
   * <p>이 불변식이 지키는 것: 테넌트가 재정의할 수 있는데 <b>플랫폼은 기본값을 정할 수 없는</b>
   * 키가 생기지 않도록 한다. 그런 키는 상속의 윗단이 비어 있어 2단 상속이 1단으로 무너진다.
   */
  @Test
  void 테넌트_허용키는_플랫폼_쓰기가능키의_부분집합이다() {
    Set<String> platformWritable = new HashSet<>();
    platformWritable.addAll(SettingsService.ALLOWED_AI_KEYS);
    platformWritable.addAll(SettingsService.ALLOWED_SMTP_KEYS);
    platformWritable.addAll(SettingsService.ALLOWED_EMBEDDING_KEYS);

    assertThat(platformWritable).containsAll(SettingsOverridePolicy.tenantOverridableKeys());
  }
}
