package com.smartfirehub.global.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 클라이언트 IP 추출 유틸리티.
 *
 * <p>리버스 프록시(nginx 등) 뒤에 배포된 환경에서는 {@link HttpServletRequest#getRemoteAddr()}이 항상 프록시 IP를 반환한다. 따라서
 * 감사 로그에 실제 클라이언트 IP를 기록하려면 {@code X-Forwarded-For} 헤더를 우선 확인해야 한다.
 *
 * <p>이 유틸은 감사 로그 IP 기록 방식의 일관성을 보장하기 위해 도입되었다 (이슈 #147 참조).
 */
public final class ClientIpExtractor {

  private ClientIpExtractor() {
    // 유틸 클래스 - 인스턴스 생성 금지
  }

  /**
   * 요청의 실제 클라이언트 IP를 추출한다.
   *
   * <p>{@code X-Forwarded-For} 헤더가 존재하면 첫 번째 값(원본 클라이언트 IP)을 반환하고, 없으면 {@link
   * HttpServletRequest#getRemoteAddr()}로 폴백한다. {@code X-Forwarded-For}는 프록시 체인을 통과할 때마다 IP가 추가되므로
   * (예: {@code client, proxy1, proxy2}) 첫 번째 값이 최초 클라이언트 IP다.
   *
   * @param request HTTP 요청 객체
   * @return 클라이언트 IP 문자열 (요청이 null이면 null)
   */
  public static String extract(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      return forwarded.split(",")[0].trim();
    }
    return request.getRemoteAddr();
  }
}
