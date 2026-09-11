package com.smartfirehub.notification;

/**
 * {@link DeliveryResult.TransientFailure#reason()}에 담기는 안정적인 사유 코드 상수.
 *
 * <p>채널 구현체(Email/Kakao/Slack)는 예외를 잡았을 때 {@code e.getClass().getSimpleName()} 같은 원본 예외
 * 클래스명을 그대로 담지 않고, 여기 정의된 안정적인 코드만 사용해야 한다. 예외 클래스명은 JVM/라이브러리
 * 구현 세부사항이라 사용자에게 그대로 노출되면 의미를 알 수 없고(예: {@code WebClientRequestException}),
 * 나중에 라이브러리가 바뀌면 코드 값도 따라 바뀌어버리는 문제가 있다.
 *
 * <p>원본 예외 상세는 {@link DeliveryResult.TransientFailure#cause()}와 서버 로그에만 남기고, 사용자에게
 * 노출되는 문자열은 {@code ChannelSettingsService}가 이 코드를 한국어 메시지로 매핑해 생성한다.
 */
public final class TransientFailureReason {

  /** 채널 서버(firehub-channel) 연결 실패, 타임아웃 등 네트워크 레벨 오류. */
  public static final String NETWORK_ERROR = "NETWORK_ERROR";

  /** firehub-channel이 HTTP 상태 코드로 응답한 실패. 실제 코드는 "CHANNEL_HTTP_{status}" 형태로 붙는다. */
  public static final String CHANNEL_HTTP_PREFIX = "CHANNEL_HTTP_";

  private TransientFailureReason() {}
}
