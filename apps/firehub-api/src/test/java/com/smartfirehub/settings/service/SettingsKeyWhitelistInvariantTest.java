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
   * <b>AI 키는 플랫폼 평면에 쓸 수 없다</b> — AI 설정은 테넌트 전용이다. 누가 플랫폼 평면 키
   * 목록에 {@code ai.*} 를 넣으면, 아무도 읽지 않는 플랫폼 값이 "저장됨"으로 보이는 무동작이
   * 생긴다.
   */
  @Test
  void AI_키는_플랫폼_쓰기가능키와_겹치지_않는다() {
    assertThat(platformWritable()).noneMatch(k -> k.startsWith("ai."));
  }

  /** 테넌트가 저장할 수 있는 키 = 두 평면 키 ∪ 테넌트 전용 AI 동작 키 — 빠짐도 초과도 없다. */
  @Test
  void 테넌트_쓰기가능키는_두_평면_키와_테넌트_전용_키의_합집합이다() {
    Set<String> expected = new HashSet<>(SettingsOverridePolicy.twoPlaneKeys());
    expected.addAll(com.smartfirehub.settings.model.AiBehaviorDefaults.keys());
    assertThat(SettingsOverridePolicy.tenantOverridableKeys()).isEqualTo(expected);
  }

  private static Set<String> platformWritable() {
    Set<String> keys = new HashSet<>();
    keys.addAll(SettingsOverridePolicy.twoPlaneKeys());
    keys.addAll(SettingsOverridePolicy.platformOnlyKeys());
    return keys;
  }

  /**
   * SMTP <b>연결 번들 5키</b>는 전부 테넌트 오버라이드 허용 키여야 한다.
   *
   * <p><b>왜 필요한가.</b> {@code resolveOverridesByPrefix} 는 오버라이드 후보를
   * {@code isTenantOverridable} 로 거른 <b>다음</b> 번들 채움을 적용하고, 채움은 5키를
   * <b>무조건</b> {@code putIfAbsent} 한다. 두 집합이 어긋나면 — 즉 누가 연결 키 하나를 플랫폼으로
   * <b>회수</b>하면 — 방금 걸러낸 키가 채움으로 되살아나
   * {@code getResolvedByPrefix} 가 그 키를 {@code overridden=true} + {@code tenantEditable=false}
   * 라는 <b>모순된 조합</b>으로 내보낸다. web 은 그 조합을 fail-closed 로 읽어 연결 그룹 <b>전체</b>를
   * 잠그므로, 테넌트는 5키 중 어느 것도 편집할 수 없게 된다 — 예외도 로그도 없는 조용한 잠금이다.
   *
   * <p><b>왜 채움 쪽에서 거르지 않는가.</b> 그게 더 자연스러워 보이지만 <b>더 위험하다</b>:
   * 회수된 키를 채움에서 빼면 그 키가 상속 폴백으로 <b>플랫폼 값</b>이 되고, 그러면 "테넌트가
   * 지정한 호스트 + 플랫폼 자격증명"이라는 <b>이 밴드가 닫은 바로 그 유출</b>이 되살아난다.
   * 지금 동작(빈 값 + 그룹 잠금)은 안전한 쪽으로 실패하고 있고, 문제는 그 상태가 <b>조용히</b>
   * 배포될 수 있다는 것뿐이다. 그래서 런타임을 바꾸는 대신 <b>{@code test} 태스크를 깨뜨린다</b> —
   * 화이트리스트를 좁히는 사람이 번들 규칙을 함께 처리하도록 강제하는 것이 옳은 자리다.
   * ("빌드를 깨뜨린다"고 쓰지 않는다: 컴파일 에러가 아니라 테스트 실패이고, 이 저장소에는
   * {@code --no-verify} 전례가 많아 한 칸 과장하면 다음 사람이 강도를 잘못 읽는다.)
   */
  @Test
  void 연결_번들_5키는_전부_테넌트_오버라이드_허용키다() {
    assertThat(SettingsOverridePolicy.tenantOverridableKeys())
        .as("연결 키가 플랫폼으로 회수되면 번들 채움이 그 키를 되살려 그룹 전체가 조용히 잠긴다")
        .containsAll(SettingsService.SMTP_CONNECTION_KEYS);
  }
}
