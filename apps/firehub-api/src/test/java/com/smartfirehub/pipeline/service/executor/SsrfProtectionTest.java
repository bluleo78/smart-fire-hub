package com.smartfirehub.pipeline.service.executor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SsrfProtectionTest {

  private SsrfProtectionService service;

  @BeforeEach
  void setUp() {
    service = new SsrfProtectionService();
  }

  // --- validateUrl: scheme checks ---

  @Test
  void validateUrl_httpScheme_passes() {
    assertDoesNotThrow(() -> service.validateUrl("http://example.com/api"));
  }

  @Test
  void validateUrl_httpsScheme_passes() {
    assertDoesNotThrow(() -> service.validateUrl("https://example.com/api"));
  }

  @Test
  void validateUrl_ftpScheme_blocked() {
    assertThrows(SsrfException.class, () -> service.validateUrl("ftp://example.com"));
  }

  @Test
  void validateUrl_fileScheme_blocked() {
    assertThrows(SsrfException.class, () -> service.validateUrl("file:///etc/passwd"));
  }

  @Test
  void validateUrl_invalidUrl_blocked() {
    assertThrows(SsrfException.class, () -> service.validateUrl("not-a-url"));
  }

  /**
   * #561: 스킴이 없는 URL 문자열은 URI.getScheme()이 null을 반환하는데, 이 값이 예외 메시지에 문자열 연결로 그대로 삽입되면 "URL scheme
   * not allowed: null. ..." 처럼 null 리터럴이 사용자에게 노출된다. 스킴 부재 케이스는 별도 메시지로 처리해야 하며 "null" 문자열을 포함해서는 안
   * 된다.
   */
  @Test
  void validateUrl_missingScheme_blockedWithoutNullLiteral() {
    SsrfException ex =
        assertThrows(SsrfException.class, () -> service.validateUrl("not-a-valid-url"));
    assertFalse(
        ex.getMessage().contains("null"), "예외 메시지에 'null' 리터럴이 노출되면 안 됨: " + ex.getMessage());
    assertTrue(ex.getMessage().contains("스킴"), "스킴 부재를 안내하는 메시지여야 함: " + ex.getMessage());
  }

  // --- validateResolvedAddress: IP range checks ---

  @Test
  void validateResolvedAddress_publicIp_passes() throws UnknownHostException {
    InetAddress publicIp = InetAddress.getByName("8.8.8.8");
    assertDoesNotThrow(() -> service.validateResolvedAddress(publicIp));
  }

  @Test
  void validateResolvedAddress_loopback_blocked() throws UnknownHostException {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(loopback));
  }

  @Test
  void validateResolvedAddress_privateClassA_blocked() throws UnknownHostException {
    InetAddress privateA = InetAddress.getByName("10.0.0.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(privateA));
  }

  @Test
  void validateResolvedAddress_privateClassB_blocked() throws UnknownHostException {
    InetAddress privateB = InetAddress.getByName("172.16.0.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(privateB));
  }

  @Test
  void validateResolvedAddress_privateClassC_blocked() throws UnknownHostException {
    InetAddress privateC = InetAddress.getByName("192.168.1.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(privateC));
  }

  @Test
  void validateResolvedAddress_linkLocal_blocked() throws UnknownHostException {
    InetAddress linkLocal = InetAddress.getByName("169.254.1.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(linkLocal));
  }

  // --- validateUrl: localhost hostname (DNS rebinding) ---

  @Test
  void validateUrl_localhostHostname_blocked() {
    // Instead of relying on environment-specific DNS resolution of "localhost",
    // directly validate the loopback address to prove the protection mechanism works.
    InetAddress loopback = InetAddress.getLoopbackAddress();
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(loopback));
  }

  // --- Redirect SSRF protection ---

  /**
   * Simulates the SSRF-via-redirect attack scenario: an attacker's server issues a 301 redirect to
   * http://169.254.169.254/ (cloud metadata endpoint).
   *
   * <p>ApiCallExecutor.executeWithRedirects() calls ssrfProtectionService.validateUrl() on every
   * Location header value before following the redirect. This test verifies that the SSRF guard
   * correctly rejects a redirect target pointing to a link-local (169.254.x.x) address.
   *
   * <p>The link-local range 169.254.0.0/16 is used by AWS/GCP/Azure metadata services and is
   * blocked by validateResolvedAddress() via InetAddress.isLinkLocalAddress().
   */
  @Test
  void validateUrl_redirectToLinkLocal_blocked() throws UnknownHostException {
    // Direct IP form avoids external DNS — validates the protection mechanism without network
    // calls.
    // An attacker-controlled redirect Location header would contain this URL.
    InetAddress linkLocalMetadata = InetAddress.getByName("169.254.169.254");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(linkLocalMetadata));
  }

  /**
   * Verifies that a redirect to a private RFC-1918 address is also blocked. Covers the scenario
   * where a redirect targets an internal service (e.g. http://10.0.0.1/).
   */
  @Test
  void validateUrl_redirectToPrivateAddress_blocked() throws UnknownHostException {
    InetAddress privateAddr = InetAddress.getByName("10.0.0.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(privateAddr));
  }

  /**
   * Verifies that a redirect to a loopback address is blocked. Covers the scenario where a redirect
   * targets http://127.0.0.1/ to reach localhost services.
   */
  @Test
  void validateUrl_redirectToLoopback_blocked() throws UnknownHostException {
    InetAddress loopbackAddr = InetAddress.getByName("127.0.0.1");
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(loopbackAddr));
  }

  /**
   * Verifies that validateUrl() rejects a URL whose hostname resolves to a link-local address. This
   * is the full-stack check: URL string -> hostname parse -> DNS resolve -> IP block. Uses a
   * literal IP URL to avoid DNS flakiness in CI.
   */
  @Test
  void validateUrl_linkLocalIpUrl_blocked() {
    // http://169.254.169.254/ written as a URL — URI.getHost() returns "169.254.169.254"
    // which resolves to itself and fails isLinkLocalAddress().
    assertThrows(
        SsrfException.class, () -> service.validateUrl("http://169.254.169.254/latest/meta-data/"));
  }

  // --- WD-148: InetAddress 판정이 놓치던 사설·예약 대역 ---

  /**
   * IPv6 고유 로컬(ULA, fc00::/7): {@code isSiteLocalAddress()} 는 IPv6 에서 폐기된 fec0::/10 만 보므로 예전에는 통과해,
   * 파이프라인 「테스트 호출」로 서버 내부 응답을 읽을 수 있었다(WD-148). AWS IMDS 의 IPv6 주소도 이 대역이다.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "fd07:b51a:cc66:0:a617:db5e:ab7:e9f1", // 탐색에서 실제로 뚫린 주소 형태
        "fd00:ec2::254", // AWS IMDS IPv6
        "fc00::1",
        "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff",
      })
  void validateResolvedAddress_ipv6UniqueLocal_blocked(String literal) throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(literal);
    // 기존 판정만으로는 못 잡는다는 전제를 함께 못 박는다 — 이 전제가 깨지면 테스트가 공허해진다.
    assertFalse(addr.isSiteLocalAddress());
    SsrfException e =
        assertThrows(SsrfException.class, () -> service.validateResolvedAddress(addr));
    assertTrue(e.getMessage().startsWith("내부망 주소는 호출할 수 없습니다(사설·예약 대역): "));
  }

  @Test
  void validateUrl_ipv6UniqueLocalLiteralUrl_blocked() {
    // URL 문자열 → 호스트 → 해석 → 대역 차단까지 전체 경로(탐색에서 쓴 요청과 같은 모양)
    assertThrows(
        SsrfException.class,
        () ->
            service.validateUrl(
                "http://[fd07:b51a:cc66:0:a617:db5e:ab7:e9f1]:6010/api/v1/auth/signup-status"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "100.64.0.1", // CGNAT
        "100.100.100.200", // CGNAT 대역의 클라우드 메타데이터
        "100.127.255.254",
        "0.0.0.1", // 「이 네트워크」 — isAnyLocalAddress() 는 0.0.0.0 만 본다
        "192.0.0.8",
        "198.18.0.1", // 벤치마크
        "192.0.2.1", // 문서용
        "240.0.0.1", // 예약
        "255.255.255.255",
      })
  void validateResolvedAddress_reservedIpv4_blocked(String literal) throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(literal);
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(addr));
  }

  /** IPv4 를 품는 IPv6 로 사설 IPv4 를 우회하지 못한다(NAT64·6to4·IPv4 호환). 6to4 는 폐기 대역이라 공용 IPv4 를 품어도 막는다. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "64:ff9b::a9fe:a9fe", // NAT64 → 169.254.169.254
        "64:ff9b::7f00:1", // NAT64 → 127.0.0.1
        "2002:a00:1::1", // 6to4 → 10.0.0.1
        "2002:808:808::1", // 6to4 → 8.8.8.8 이어도 막는다(Python 가드와 같음)
        "::127.0.0.1", // IPv4 호환 — isLoopbackAddress() 는 거짓
        "::ffff:10.0.0.5", // 사상(JDK 가 Inet4Address 로 바꿈)
      })
  void validateResolvedAddress_ipv6EmbeddingPrivateIpv4_blocked(String literal)
      throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(literal);
    assertThrows(SsrfException.class, () -> service.validateResolvedAddress(addr));
  }

  /** 공용 주소는 계속 통과한다 — 대역 표가 너무 넓어 정상 외부 API 를 막지 않는지. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "8.8.8.8",
        "1.1.1.1",
        "100.63.255.255", // CGNAT 바로 아래
        "100.128.0.1", // CGNAT 바로 위
        "2606:4700:4700::1111", // 공용 IPv6
        "2001:4860:4860::8888", // 2001::/23 밖의 공용 IPv6
        "64:ff9b::808:808", // NAT64 → 8.8.8.8
      })
  void validateResolvedAddress_publicAddresses_pass(String literal) throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(literal);
    assertDoesNotThrow(() -> service.validateResolvedAddress(addr));
  }

  // --- WD-150: 사용자에게 보이는 한국어 문구(Python 실행기 ssrf_protection.py 와 같은 문장) ---

  /** 차단 종류별 문구 — 영어 원문 대신 「내부망 주소는 호출할 수 없습니다(종류): IP」. */
  @ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "127.0.0.1, 루프백",
    "10.1.2.3, 사설망",
    "169.254.169.254, 링크 로컬",
    "224.0.0.1, 멀티캐스트",
    "0.0.0.0, 와일드카드",
    "100.64.0.1, 사설·예약 대역",
    "fd00:ec2:0:0:0:0:0:254, 사설·예약 대역",
    "192.0.2.1, 사설·예약 대역",
    "64:ff9b:0:0:0:0:7f00:1, 사설·예약 대역",
  })
  void validateResolvedAddress_blockedMessage_isKoreanWithKind(String literal, String kind)
      throws UnknownHostException {
    InetAddress addr = InetAddress.getByName(literal);
    SsrfException e =
        assertThrows(SsrfException.class, () -> service.validateResolvedAddress(addr));
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .isEqualTo("내부망 주소는 호출할 수 없습니다(" + kind + "): " + literal);
  }

  @Test
  void validateUrl_ftpScheme_koreanMessageWithScheme() {
    SsrfException e =
        assertThrows(SsrfException.class, () -> service.validateUrl("ftp://example.com/file"));
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .isEqualTo("http 또는 https 주소만 호출할 수 있습니다(입력: ftp)");
  }

  @Test
  void validateUrl_unresolvableHost_koreanMessage() {
    SsrfException e =
        assertThrows(
            SsrfException.class,
            () -> service.validateUrl("http://wd150-no-such-host.invalid/data"));
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .isEqualTo("호스트 이름을 찾을 수 없습니다: wd150-no-such-host.invalid");
  }

  @Test
  void validateUrl_loopbackUrl_koreanMessage() {
    SsrfException e =
        assertThrows(SsrfException.class, () -> service.validateUrl("http://127.0.0.1:8080/x"));
    org.assertj.core.api.Assertions.assertThat(e.getMessage())
        .isEqualTo("내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1");
  }

  /** URL 을 문구에 넣는 경우에도 쿼리(API 키가 실릴 수 있음)는 빠진다. */
  @Test
  void validateUrl_messagesWithUrl_dropQueryString() {
    SsrfException noScheme =
        assertThrows(
            SsrfException.class, () -> service.validateUrl("example.com/api?serviceKey=SECRET123"));
    org.assertj.core.api.Assertions.assertThat(noScheme.getMessage())
        .contains("스킴")
        .doesNotContain("SECRET123")
        .doesNotContain("?");

    SsrfException invalid =
        assertThrows(
            SsrfException.class,
            () -> service.validateUrl("http://exa mple.com/api?serviceKey=SECRET123"));
    org.assertj.core.api.Assertions.assertThat(invalid.getMessage())
        .startsWith("URL 형식이 올바르지 않습니다: ")
        .doesNotContain("SECRET123");
  }
}
