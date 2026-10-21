package com.smartfirehub.pipeline.service.executor;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SsrfProtectionService {

  /**
   * Validates a URL string for SSRF safety. Checks scheme, then resolves all DNS addresses for the
   * hostname and validates each.
   *
   * @param url the URL string to validate
   * @throws SsrfException if the URL is unsafe
   */
  public void validateUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new SsrfException("호출할 URL 이 비어 있습니다");
    }

    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new SsrfException("URL 형식이 올바르지 않습니다: " + ApiCallErrorMessages.redactUrl(url), e);
    }

    // 스킴이 아예 없는 경우(getScheme()==null)와 스킴은 있으나 허용되지 않는 경우를 구분한다.
    // 구분하지 않으면 "URL scheme not allowed: null. ..." 처럼 null 리터럴이
    // 사용자에게 그대로 노출되어 원인을 이해하기 어렵다 (#561).
    String scheme = uri.getScheme();
    if (scheme == null) {
      throw new SsrfException(
          "URL에 스킴(http:// 또는 https://)이 없습니다: " + ApiCallErrorMessages.redactUrl(url));
    }
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      throw new SsrfException("http 또는 https 주소만 호출할 수 있습니다(입력: " + scheme + ")");
    }

    String hostname = uri.getHost();
    if (hostname == null || hostname.isBlank()) {
      throw new SsrfException("URL 에 호스트가 없습니다: " + ApiCallErrorMessages.redactUrl(url));
    }

    // Resolve all IPs for the hostname to prevent DNS rebinding attacks
    InetAddress[] resolvedAddresses;
    try {
      resolvedAddresses = InetAddress.getAllByName(hostname);
    } catch (UnknownHostException e) {
      throw new SsrfException(ApiCallErrorMessages.unknownHost(hostname), e);
    }

    for (InetAddress address : resolvedAddresses) {
      validateResolvedAddress(address);
    }

    log.debug("URL passed SSRF validation: {}", url);
  }

  /**
   * Validates a resolved IP address against private/internal network ranges. Call this for every
   * hop in a redirect chain.
   *
   * @param address the resolved InetAddress to validate
   * @throws SsrfException if the address is private, loopback, link-local, multicast, or
   *     unspecified
   */
  public void validateResolvedAddress(InetAddress address) {
    if (address.isLoopbackAddress()) {
      throw new SsrfException(blocked("루프백", address));
    }

    if (address.isSiteLocalAddress()) {
      // Covers RFC 1918: 10.x.x.x, 172.16-31.x.x, 192.168.x.x
      throw new SsrfException(blocked("사설망", address));
    }

    if (address.isLinkLocalAddress()) {
      // Covers 169.254.x.x (IPv4) and fe80::/10 (IPv6)
      throw new SsrfException(blocked("링크 로컬", address));
    }

    if (address.isMulticastAddress()) {
      throw new SsrfException(blocked("멀티캐스트", address));
    }

    if (address.isAnyLocalAddress()) {
      // Covers 0.0.0.0 and ::
      throw new SsrfException(blocked("와일드카드", address));
    }

    // InetAddress 의 is*Address() 판정이 놓치는 사설·예약 대역을 직접 막는다(WD-148).
    // 특히 isSiteLocalAddress() 는 IPv6 에서 폐기된 fec0::/10 만 보므로, IPv6 고유 로컬(ULA, fc00::/7 —
    // AWS IMDS 의 IPv6 주소 fd00:ec2::254 도 여기)이 그대로 통과해 「테스트 호출」로 서버 내부 응답을 읽을 수 있었다.
    // 운영 실행 경로의 Python 가드(ipaddress.is_global 기준)와 같은 범위를 막는다.
    if (isReservedRange(address)) {
      throw new SsrfException(blocked("사설·예약 대역", address));
    }

    log.debug("Address passed SSRF validation: {}", address.getHostAddress());
  }

  /**
   * 차단 문구 — 「내부망 주소는 호출할 수 없습니다(종류): IP」(WD-150). 예전 영어 원문({@code Requests to loopback addresses
   * are not allowed})을 실행 상세·「테스트 호출」에서 그대로 봤다. 종류는 고칠 방향(루프백=같은 서버, 사설망=사내 IP 등)을 알려 주므로 남긴다.
   * Python 실행기 가드({@code ssrf_protection.py})가 같은 문장을 쓴다 — 함께 고칠 것.
   */
  private static String blocked(String kind, InetAddress address) {
    return "내부망 주소는 호출할 수 없습니다(" + kind + "): " + address.getHostAddress();
  }

  /**
   * 공용 인터넷이 아닌 IPv4 대역 — IANA 특수 목적 주소 등록부 중 전역 도달이 아닌 것. RFC 1918·루프백·링크 로컬·멀티캐스트는 위 is*Address()
   * 가 먼저 막지만, 판정 근거를 한 표로 보이도록 함께 둔다.
   */
  private static final Cidr[] RESERVED_V4 = {
    Cidr.of("0.0.0.0", 8), // 「이 네트워크」 — isAnyLocalAddress() 는 0.0.0.0 하나만 본다
    Cidr.of("10.0.0.0", 8),
    Cidr.of("100.64.0.0", 10), // CGNAT — 일부 k8s 파드 대역·클라우드 메타데이터(100.100.100.200)
    Cidr.of("127.0.0.0", 8),
    Cidr.of("169.254.0.0", 16),
    Cidr.of("172.16.0.0", 12),
    Cidr.of("192.0.0.0", 24),
    Cidr.of("192.0.2.0", 24),
    Cidr.of("192.168.0.0", 16),
    Cidr.of("198.18.0.0", 15),
    Cidr.of("198.51.100.0", 24),
    Cidr.of("203.0.113.0", 24),
    Cidr.of("224.0.0.0", 4),
    Cidr.of("240.0.0.0", 4), // 예약 + 255.255.255.255
  };

  /** 공용 인터넷이 아닌 IPv6 대역. IPv4 를 품는 대역(사상·NAT64)은 아래에서 품은 IPv4 를 따로 본다. */
  private static final Cidr[] RESERVED_V6 = {
    Cidr.of("::", 96), // 미지정·루프백·IPv4 호환(::a.b.c.d — isLoopbackAddress() 는 ::127.0.0.1 을 못 잡는다)
    Cidr.of("64:ff9b:1::", 48), // 로컬 NAT64
    Cidr.of("100::", 64), // 폐기용
    Cidr.of("2001::", 23), // IETF 프로토콜 할당(Teredo 포함)
    Cidr.of("2001:db8::", 32), // 문서용
    Cidr.of("2002::", 16), // 6to4 — 폐기 대역(RFC 7526), 품은 IPv4 와 상관없이 막는다(Python 가드 is_global 과 같음)
    Cidr.of("fc00::", 7), // 고유 로컬(ULA)
    Cidr.of("fe80::", 10),
    Cidr.of("fec0::", 10),
    Cidr.of("ff00::", 8),
  };

  private static final Cidr NAT64 = Cidr.of("64:ff9b::", 96);

  /** 공용이 아닌 주소인지. IPv4 를 품은 IPv6(사상·NAT64)는 품은 IPv4 로 다시 판정한다 — 우회 경로를 막는다. */
  static boolean isReservedRange(InetAddress address) {
    byte[] b = address.getAddress();
    if (b.length == 4) {
      return matchesAny(RESERVED_V4, b);
    }
    // ::ffff:a.b.c.d 는 JDK 가 Inet4Address 로 바꿔 주지만, 바이트로 들어오는 경로를 대비해 직접도 본다.
    if (isV4Mapped(b)) {
      return matchesAny(RESERVED_V4, Arrays.copyOfRange(b, 12, 16));
    }
    if (NAT64.contains(b)) {
      return matchesAny(RESERVED_V4, Arrays.copyOfRange(b, 12, 16));
    }
    return matchesAny(RESERVED_V6, b);
  }

  private static boolean isV4Mapped(byte[] b) {
    for (int i = 0; i < 10; i++) {
      if (b[i] != 0) return false;
    }
    return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
  }

  private static boolean matchesAny(Cidr[] ranges, byte[] b) {
    for (Cidr range : ranges) {
      if (range.contains(b)) return true;
    }
    return false;
  }

  /** 주소 바이트의 앞 prefixBits 비트가 같은지로 대역 포함을 판정한다(IPv4·IPv6 공용). */
  private record Cidr(byte[] network, int prefixBits) {

    static Cidr of(String literal, int prefixBits) {
      try {
        // 숫자 리터럴만 넘기므로 DNS 조회가 일어나지 않는다.
        return new Cidr(InetAddress.getByName(literal).getAddress(), prefixBits);
      } catch (UnknownHostException e) {
        throw new IllegalStateException("잘못된 대역 리터럴: " + literal, e);
      }
    }

    boolean contains(byte[] address) {
      if (address.length != network.length) return false;
      int fullBytes = prefixBits / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (address[i] != network[i]) return false;
      }
      int remBits = prefixBits % 8;
      if (remBits == 0) return true;
      int mask = (0xFF << (8 - remBits)) & 0xFF;
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
  }
}
