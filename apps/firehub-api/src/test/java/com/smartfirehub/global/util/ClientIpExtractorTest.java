package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** ClientIpExtractor 단위 테스트 — X-Forwarded-For 우선 처리 및 remoteAddr 폴백 검증 (이슈 #147) */
class ClientIpExtractorTest {

  /** X-Forwarded-For 헤더가 있으면 첫 번째 값을 클라이언트 IP로 반환한다 */
  @Test
  void extract_whenXForwardedForPresent_returnsFirstHopIp() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1"); // 프록시 IP (무시되어야 함)
    req.addHeader("X-Forwarded-For", "203.0.113.5, 198.51.100.10, 10.0.0.1");

    String ip = ClientIpExtractor.extract(req);

    assertThat(ip).isEqualTo("203.0.113.5");
  }

  /** X-Forwarded-For 헤더가 단일 값이어도 정상 추출한다 */
  @Test
  void extract_whenXForwardedForSingleValue_returnsThatValue() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1");
    req.addHeader("X-Forwarded-For", "203.0.113.5");

    assertThat(ClientIpExtractor.extract(req)).isEqualTo("203.0.113.5");
  }

  /** X-Forwarded-For 첫 값에 공백이 있어도 trim된다 */
  @Test
  void extract_trimsWhitespaceAroundFirstHop() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("10.0.0.1");
    req.addHeader("X-Forwarded-For", "  203.0.113.5  ,  10.0.0.1  ");

    assertThat(ClientIpExtractor.extract(req)).isEqualTo("203.0.113.5");
  }

  /** X-Forwarded-For 헤더가 없으면 remoteAddr로 폴백한다 */
  @Test
  void extract_whenNoXForwardedFor_fallsBackToRemoteAddr() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("192.168.1.42");

    assertThat(ClientIpExtractor.extract(req)).isEqualTo("192.168.1.42");
  }

  /** X-Forwarded-For 헤더가 빈 문자열이면 remoteAddr로 폴백한다 */
  @Test
  void extract_whenXForwardedForBlank_fallsBackToRemoteAddr() {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr("192.168.1.42");
    req.addHeader("X-Forwarded-For", "   ");

    assertThat(ClientIpExtractor.extract(req)).isEqualTo("192.168.1.42");
  }

  /** request 자체가 null이면 null을 반환한다 (방어적 처리) */
  @Test
  void extract_whenRequestNull_returnsNull() {
    assertThat(ClientIpExtractor.extract(null)).isNull();
  }
}
