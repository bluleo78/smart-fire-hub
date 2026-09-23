package com.smartfirehub.settings.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.settings.model.AiBehaviorDefaults;
import com.smartfirehub.settings.model.AiCredentialSlot;
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
   * <b>AI 키는 플랫폼 평면에 쓸 수 없다</b> — AI 설정은 테넌트 전용이다. 누가 플랫폼 평면 키
   * 목록에 {@code ai.*} 를 넣으면, 아무도 읽지 않는 플랫폼 값이 "저장됨"으로 보이는 무동작이
   * 생긴다.
   */
  @Test
  void AI_키와_SMTP_키는_플랫폼_쓰기가능키와_겹치지_않는다() {
    // #712: SMTP 도 테넌트 전용이다 — 플랫폼 쓰기 가능 키는 임베딩 4키뿐이다.
    assertThat(SettingsOverridePolicy.platformOnlyKeys())
        .noneMatch(k -> k.startsWith("ai.") || k.startsWith("smtp."));
  }

  /** 테넌트가 저장할 수 있는 키 = SMTP 6키 ∪ 테넌트 전용 AI 동작 키 — 빠짐도 초과도 없다. */
  @Test
  void 테넌트_쓰기가능키는_SMTP_키와_AI_동작_키의_합집합이다() {
    Set<String> expected = new HashSet<>(SettingsOverridePolicy.smtpKeys());
    expected.addAll(AiBehaviorDefaults.keys());
    assertThat(SettingsOverridePolicy.tenantOverridableKeys()).isEqualTo(expected);
  }

  /**
   * AI 자격증명 슬롯이 소유한 키(채팅·분류 자격증명 + 분류 모델)는 전부 전용 서비스 소유이고,
   * 코드 기본값이 없어야 한다 — 기본값이 생기면 "없음 = 채팅 설정 사용"(#707 결정 2)이 깨진다.
   */
  @Test
  void 자격증명_슬롯_소유_키는_전부_EXTERNAL_OWNER_이고_기본값이_없다() {
    assertThat(AiCredentialSlot.ownedKeys())
        .containsExactlyInAnyOrder("ai.credential", "ai.classify_credential", "ai.classify_model");
    for (String key : AiCredentialSlot.ownedKeys()) {
      assertThat(SettingsOverridePolicy.planeOf(key)).as(key)
          .isEqualTo(SettingsOverridePolicy.Plane.EXTERNAL_OWNER);
      assertThat(AiBehaviorDefaults.isKey(key)).as(key).isFalse();
      assertThat(SettingsOverridePolicy.tenantOverridableKeys()).as(key).doesNotContain(key);
    }
  }
}
