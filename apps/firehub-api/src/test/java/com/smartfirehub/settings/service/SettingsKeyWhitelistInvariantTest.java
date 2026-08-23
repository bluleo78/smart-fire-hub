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
