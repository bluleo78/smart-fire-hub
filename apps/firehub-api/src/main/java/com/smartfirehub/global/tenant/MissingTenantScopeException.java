package com.smartfirehub.global.tenant;

/**
 * 테넌트 컨텍스트 없이 테넌트 스코프가 필요한 지점에 진입했을 때 던진다.
 *
 * <p><b>왜 최상위 public 클래스로 승격됐는가:</b> {@link TenantContext#require()} 의 예외 메시지가
 * "배경 잡 예약" 문맥에 하드코딩돼 있었다. 그래서 P2-f 의 Slack 웹훅 가드({@code SlackInboundService})는
 * 그 메시지를 쓸 수 없어 같은 개념의 package-private 중첩 클래스를 따로 만들었다 — 지점이 하나 더
 * 늘면 세 번째 예외 타입이 생길 판이었다. 원인은 메시지 문자열이 하드코딩돼 있었다는 것 하나였다.
 * 이제 {@link TenantContext#require(String)} 가 호출부별 문맥 설명을 인자로 받아 이 클래스를 던지므로,
 * 새 호출 지점은 예외 타입을 새로 만들 필요 없이 문맥 문자열만 넘기면 된다.
 *
 * <p>{@link IllegalStateException} 을 상속하는 것은 우연이 아니라 계약이다 —
 * {@code GlobalExceptionHandler.handleIllegalState} 가 이 계층을 409 로 변환하고,
 * {@code SlackInboundService.dispatch} 의 catch 가 이 타입을 구분해 "배선 결함" 로그를 남긴다.
 * 상속을 끊으면 두 지점 모두 조용히 깨져, 이 예외가 처리되지 않은 500 으로 표면화된다.
 */
public class MissingTenantScopeException extends IllegalStateException {

  /**
   * @param where 예외가 발생한 호출 문맥 설명 (예: "배경 잡 예약", "Slack inbound 본 처리
   *     (team=T123)"). 운영자가 로그만 보고 어느 호출 지점인지 바로 알 수 있어야 한다.
   */
  public MissingTenantScopeException(String where) {
    super("테넌트 컨텍스트 없이 진입했다: " + where);
  }
}
