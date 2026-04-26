package com.smartfirehub.notification;

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
}
