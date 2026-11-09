package com.smartfirehub.pipeline.service.executor;

/** 내부망 등 호출할 수 없는 주소 — 메시지는 고정 한국어 차단 문구다({@link SafeUserMessage}, WD-693). */
public class SsrfException extends RuntimeException implements SafeUserMessage {
  public SsrfException(String message) {
    super(message);
  }

  public SsrfException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * 예외 원인 사슬에서 가장 바깥의 {@link SsrfException} 을 찾는다(없으면 null).
   *
   * <p>접속 시점 주소 검사({@link SsrfGuardedAddressResolverGroup}, WD-613)가 막으면 SsrfException 이 WebClient
   * 의 {@code WebClientRequestException} 등에 감싸여 올라온다. 호출부가 이를 꺼내 사전 검사(validateUrl)와 같은 차단 문구·처리(재시도
   * 안 함 등)로 다루게 하려는 것이다.
   */
  public static SsrfException findIn(Throwable error) {
    int depth = 0;
    // 서로를 원인으로 갖는 순환 사슬에서 끝없이 돌지 않게 깊이를 제한한다.
    for (Throwable c = error; c != null && depth < 32; c = c.getCause(), depth++) {
      if (c instanceof SsrfException ssrf) {
        return ssrf;
      }
    }
    return null;
  }
}
