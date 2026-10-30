package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * ClientIpExtractor 단위 테스트 — 신뢰 프록시 기준 X-Forwarded-For 해석(WD-402).
 *
 * <p>신뢰하지 않는 상대가 보낸 헤더는 무시하고, 신뢰 프록시를 거친 경우 헤더를 오른쪽부터 읽어 처음 만나는 신뢰하지 않는 주소를 고른다.
 */
class ClientIpExtractorTest {

  private final ClientIpExtractor extractor = new ClientIpExtractor("");

  private static MockHttpServletRequest request(String remoteAddr, String... xff) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setRemoteAddr(remoteAddr);
    for (String v : xff) req.addHeader("X-Forwarded-For", v);
    return req;
  }

  /** 신뢰하지 않는 상대가 직접 보낸 X-Forwarded-For 는 무시하고 remoteAddr 를 쓴다(위조 차단 핵심) */
  @Test
  void untrustedRemote_ignoresForwardedFor() {
    assertThat(extractor.extract(request("198.51.100.9", "10.0.0.5"))).isEqualTo("198.51.100.9");
    assertThat(extractor.extract(request("198.51.100.9", "203.0.113.5, 127.0.0.1")))
        .isEqualTo("198.51.100.9");
  }

  /** 신뢰 프록시 뒤: 클라이언트가 앞에 끼운 위조 값이 아니라 nginx 가 덧붙인 실제 주소를 쓴다 */
  @Test
  void trustedRemote_spoofedThenReal_returnsReal() {
    assertThat(extractor.extract(request("172.18.0.3", "203.0.113.77, 198.51.100.9")))
        .isEqualTo("198.51.100.9");
  }

  /** 프록시 여러 단(사설 주소)을 건너뛰고 처음 나오는 공인 주소를 고른다 */
  @Test
  void trustedRemote_multipleTrustedHops_skipsThem() {
    assertThat(
            extractor.extract(
                request("127.0.0.1", "203.0.113.77, 198.51.100.9, 10.0.0.2, 192.168.1.10")))
        .isEqualTo("198.51.100.9");
  }

  /** 여러 줄로 온 헤더도 순서대로 이어 하나의 체인으로 본다 */
  @Test
  void trustedRemote_multipleHeaderLines_treatedAsOneChain() {
    assertThat(extractor.extract(request("127.0.0.1", "203.0.113.77", "198.51.100.9, 10.0.0.2")))
        .isEqualTo("198.51.100.9");
  }

  /** 전부 신뢰 프록시면 맨 오른쪽 값(마지막 프록시가 덧붙인 직전 홉)을 쓴다 — 맨 왼쪽은 클라이언트가 쓴 값이다(WD-402) */
  @Test
  void trustedRemote_allTrusted_returnsRightmost() {
    assertThat(extractor.extract(request("10.0.0.1", "192.168.1.42, 10.0.0.2")))
        .isEqualTo("10.0.0.2");
  }

  /** 내부망 공격자(10.9.9.9)가 허용 IP(10.1.2.3)를 헤더에 적어도 nginx 가 덧붙인 실제 주소가 나온다 */
  @Test
  void intranetAttacker_spoofingAllowedPrivateIp_getsOwnAddress() {
    assertThat(extractor.extract(request("172.18.0.3", "10.1.2.3, 10.9.9.9")))
        .isEqualTo("10.9.9.9");
  }

  /**
   * 같은 도커 네트워크의 다른 컨테이너(172.18.0.9)가 api 에 직접 X-Forwarded-For: 127.0.0.1 을 보내는 경우. 기본 목록은 사설망 전체를
   * 믿으므로 막지 못한다 — 신뢰 목록을 nginx 주소(172.18.0.3/32)로 좁혀야 이웃 컨테이너의 헤더가 무시된다(APP_TRUSTED_PROXIES).
   */
  @Test
  void dockerNeighbour_spoofingLoopback_blockedOnlyWithNarrowedList() {
    MockHttpServletRequest neighbour = request("172.18.0.9", "127.0.0.1");
    // 기본 목록: 이웃도 신뢰 대역이라 위장이 통한다(한계 — 목록을 좁혀 막는다)
    assertThat(extractor.extract(neighbour)).isEqualTo("127.0.0.1");
    // 좁힌 목록: nginx 만 믿으므로 이웃의 헤더는 무시되고 자기 주소가 나온다
    ClientIpExtractor narrowed = new ClientIpExtractor("172.18.0.3/32");
    assertThat(narrowed.extract(neighbour)).isEqualTo("172.18.0.9");
    // nginx 를 거친 정상 요청은 그대로 실제 주소
    assertThat(narrowed.extract(request("172.18.0.3", "127.0.0.1, 198.51.100.9")))
        .isEqualTo("198.51.100.9");
  }

  /** 앞뒤 공백은 잘라 비교한다 */
  @Test
  void trustedRemote_trimsEntries() {
    assertThat(extractor.extract(request("10.0.0.1", "  203.0.113.5  ,  10.0.0.1  ")))
        .isEqualTo("203.0.113.5");
  }

  /** 헤더가 없거나 비었거나 쉼표뿐이면 remoteAddr 로 폴백한다 */
  @Test
  void trustedRemote_missingOrBlankHeader_fallsBackToRemoteAddr() {
    assertThat(extractor.extract(request("192.168.1.42"))).isEqualTo("192.168.1.42");
    assertThat(extractor.extract(request("192.168.1.42", "   "))).isEqualTo("192.168.1.42");
    assertThat(extractor.extract(request("192.168.1.42", " , ,"))).isEqualTo("192.168.1.42");
  }

  /** 해석되지 않는 값은 신뢰하지 않는 값이라 그 자리에서 멈춘다 — 그 왼쪽의 위조 값까지 가지 않는다 */
  @Test
  void trustedRemote_garbageEntry_isUntrustedAndReturnedAsIs() {
    assertThat(extractor.extract(request("127.0.0.1", "10.0.0.5, garbage, 10.0.0.1")))
        .isEqualTo("garbage");
    // 호스트 이름처럼 보이는 값도 신뢰 프록시로 취급되지 않는다(DNS 조회 없음)
    assertThat(extractor.isTrustedProxy("localhost")).isFalse();
    assertThat(extractor.isTrustedProxy("10.0.0.256")).isFalse();
    assertThat(extractor.isTrustedProxy("")).isFalse();
    assertThat(extractor.isTrustedProxy(null)).isFalse();
  }

  /** IPv6 — 루프백·ULA 는 신뢰 프록시, 공인 IPv6 는 클라이언트. Tomcat 의 0:0:0:0:0:0:0:1 표기도 루프백으로 본다 */
  @Test
  void ipv6_trustedAndUntrusted() {
    assertThat(extractor.extract(request("0:0:0:0:0:0:0:1", "2001:db8::bad, 2001:db8::1, fd00::2")))
        .isEqualTo("2001:db8::1");
    assertThat(extractor.extract(request("2001:db8::9", "127.0.0.1"))).isEqualTo("2001:db8::9");
    assertThat(extractor.extract(request("::1", "[2001:db8::7]"))).isEqualTo("[2001:db8::7]");
    // IPv4 사상 주소는 IPv4 로 판정된다
    assertThat(extractor.isTrustedProxy("::ffff:10.1.2.3")).isTrue();
    assertThat(extractor.isTrustedProxy("fe80::1")).isTrue();
  }

  /** 신뢰 목록이 비면(목록 생성자) 헤더를 절대 보지 않는다 / 사용자 지정 대역만 신뢰한다 / 빈 설정값은 기본 목록이다 */
  @Test
  void customTrustedList() {
    ClientIpExtractor none = new ClientIpExtractor(List.of());
    assertThat(none.extract(request("127.0.0.1", "203.0.113.5"))).isEqualTo("127.0.0.1");

    assertThat(new ClientIpExtractor("  ").isTrustedProxy("10.1.1.1")).isTrue();

    ClientIpExtractor custom = new ClientIpExtractor(" 203.0.113.0/24 ");
    assertThat(custom.extract(request("203.0.113.1", "198.51.100.9"))).isEqualTo("198.51.100.9");
    assertThat(custom.extract(request("10.0.0.1", "198.51.100.9"))).isEqualTo("10.0.0.1");
  }

  /** 잘못된 신뢰 프록시 설정은 조용히 무시하지 않고 기동을 실패시킨다 */
  @Test
  void invalidTrustedConfig_failsFast() {
    assertThatThrownBy(() -> new ClientIpExtractor("10.0.0.0/8,not-a-cidr"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ClientIpExtractor("10.0.0.0/33"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new ClientIpExtractor(List.of()).extract(request("127.0.0.1", "1.2.3.4")))
        .isEqualTo("127.0.0.1");
  }

  /** null 요청은 null */
  @Test
  void extract_nullRequest_returnsNull() {
    assertThat(extractor.extract(null)).isNull();
  }
}
