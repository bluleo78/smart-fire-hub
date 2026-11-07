package com.smartfirehub.notification;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 알림 채널 종류 식별. 새 채널 추가 시 여기에 enum 값 추가 + Channel 구현체 1개.
 *
 * <p>각 enum에 {@link AuthStrategy}를 고정으로 묶는다. RoutingResolver 등에서 Channel Bean 유무와 무관하게 라우팅 결정에 쓰기
 * 위함.
 */
public enum ChannelType {
  CHAT(AuthStrategy.NONE), // 웹 인박스 (안전망, opt-out 불가)
  EMAIL(AuthStrategy.EMAIL_ADDRESS),
  KAKAO(AuthStrategy.OAUTH),
  SLACK(AuthStrategy.BOT_TOKEN);

  private final AuthStrategy authStrategy;

  ChannelType(AuthStrategy authStrategy) {
    this.authStrategy = authStrategy;
  }

  public AuthStrategy authStrategy() {
    return authStrategy;
  }

  /** binding(외부 토큰) 필요 여부 — OAuth/봇 기반 채널만 true. */
  public boolean requiresBinding() {
    return authStrategy == AuthStrategy.OAUTH || authStrategy == AuthStrategy.BOT_TOKEN;
  }

  /**
   * URL 경로 등 사용자 입력의 채널 이름을 enum 으로 바꾼다(대소문자 무시).
   *
   * <p>{@link #valueOf(String)} 를 그대로 쓰면 모르는 값에서 「No enum constant
   * com.smartfirehub.notification.ChannelType.NOPE」가 400 메시지로 나가 내부 클래스 경로가 드러난다(WD-543). 대신 지원 채널
   * 목록을 담은 한국어 메시지로 {@link IllegalArgumentException} 을 던져 전역 처리기가 그대로 400 으로 응답하게 한다. 목록은 {@link
   * #values()} 에서 만들어 채널이 늘어도 안내가 어긋나지 않는다. 대문자 변환은 {@link Locale#ROOT} 로 해 터키어 로케일 등에서 「i」가 다른 글자로
   * 바뀌는 일을 막는다.
   *
   * @param raw 사용자가 보낸 채널 이름 (null·빈 값 허용 — 같은 400)
   * @return 일치하는 채널
   * @throws IllegalArgumentException 지원하지 않는 채널이거나 값이 비어 있을 때
   */
  public static ChannelType fromPath(String raw) {
    String normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    for (ChannelType type : values()) {
      if (type.name().equals(normalized)) {
        return type;
      }
    }
    String supported = Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    throw new IllegalArgumentException(
        "지원하지 않는 채널입니다: " + normalized + " (" + supported + " 중 하나)");
  }
}
