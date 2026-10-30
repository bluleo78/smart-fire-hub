package com.smartfirehub.global.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * IP 주소 리터럴 파싱과 CIDR 대역 포함 판정(WD-402).
 *
 * <p>트리거 허용 IP 검사({@code TriggerService#isIpAllowed})와 신뢰 프록시 판정({@link ClientIpExtractor})이 같은 비트
 * 마스크 비교를 쓰도록 한 곳에 모았다. 예전에는 {@code TriggerService} 안의 private 메서드였다.
 *
 * <p>파싱은 숫자 리터럴만 받는다 — {@link InetAddress#getByName(String)} 에 호스트 이름이 들어가면 DNS 조회가 일어나므로, 헤더처럼
 * 바깥에서 들어온 값은 먼저 리터럴 모양인지 확인한 뒤에만 넘긴다. 해석할 수 없는 값은 어떤 대역에도 속하지 않는다(허용 목록 통과 불가).
 *
 * <p>바이트 배열을 들고 있으므로 record 대신 final 클래스로 두고, 생성·조회 시 복사해 바깥에서 대역이 바뀌지 않게 한다.
 */
public final class IpCidr {

  /** 대역의 네트워크 주소 바이트(IPv4 4바이트·IPv6 16바이트). 외부에 그대로 내주지 않는다. */
  private final byte[] network;

  /** 접두 비트 수(0 ~ 주소 비트 길이). */
  private final int prefixBits;

  private IpCidr(byte[] network, int prefixBits) {
    this.network = network.clone();
    this.prefixBits = prefixBits;
  }

  /** 점 네 개로 나뉜 0~255 십진 옥텟만 허용하는 IPv4 리터럴 모양(WD-402). 이 모양이 아니면 DNS 로 넘기지 않는다. */
  private static final Pattern IPV4_LITERAL =
      Pattern.compile(
          "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");

  /** IPv6 리터럴에 나올 수 있는 글자(16진수·콜론·점(IPv4 내장 표기)). 콜론이 있는 값은 JDK 가 DNS 없이 IPv6 로만 해석한다. */
  private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

  /**
   * 문자열을 IP 주소로 해석한다(DNS 조회 없음, WD-402).
   *
   * <p>앞뒤 공백을 없애고, IPv6 의 대괄호 표기({@code [::1]})도 받는다. 존 ID({@code fe80::1%eth0})·호스트 이름·빈 값·쓰레기 값은
   * 모두 empty 다. {@code ::ffff:a.b.c.d} 는 JDK 가 IPv4 주소로 바꿔 돌려준다.
   *
   * @param value 주소 문자열
   * @return 해석된 주소, 리터럴이 아니면 empty
   */
  public static Optional<InetAddress> parseLiteral(String value) {
    if (value == null) return Optional.empty();
    String s = value.trim();
    if (s.startsWith("[") && s.endsWith("]")) {
      s = s.substring(1, s.length() - 1);
    }
    if (s.isEmpty()) return Optional.empty();
    boolean looksV4 = IPV4_LITERAL.matcher(s).matches();
    // 콜론이 있어야 IPv6 로 본다 — 콜론 없는 값이 v4 모양도 아니면 getByName 이 DNS 를 부르므로 여기서 끊는다.
    boolean looksV6 = s.indexOf(':') >= 0 && IPV6_LITERAL.matcher(s).matches();
    if (!looksV4 && !looksV6) return Optional.empty();
    try {
      return Optional.of(InetAddress.getByName(s));
    } catch (UnknownHostException | RuntimeException e) {
      // "1:2:3" 처럼 모양만 비슷한 잘못된 IPv6 — JDK 가 DNS 없이 바로 거절한다.
      return Optional.empty();
    }
  }

  /**
   * {@code "10.0.0.0/8"}·{@code "::1/128"}·접두가 없는 단일 주소({@code "192.168.1.5"} → /32, IPv6 → /128)를
   * 대역으로 만든다.
   *
   * @param cidr CIDR 표기 문자열
   * @return 대역
   * @throws IllegalArgumentException 주소나 접두 길이가 잘못된 경우
   */
  public static IpCidr parse(String cidr) {
    if (cidr == null || cidr.isBlank()) {
      throw new IllegalArgumentException("빈 CIDR 항목");
    }
    String s = cidr.trim();
    int slash = s.indexOf('/');
    String addressPart = slash < 0 ? s : s.substring(0, slash);
    InetAddress address =
        parseLiteral(addressPart)
            .orElseThrow(() -> new IllegalArgumentException("잘못된 CIDR 주소: " + cidr));
    byte[] bytes = address.getAddress();
    int maxBits = bytes.length * 8;
    int prefix;
    if (slash < 0) {
      prefix = maxBits;
    } else {
      try {
        prefix = Integer.parseInt(s.substring(slash + 1).trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("잘못된 CIDR 접두 길이: " + cidr, e);
      }
    }
    if (prefix < 0 || prefix > maxBits) {
      throw new IllegalArgumentException("CIDR 접두 길이 범위 밖: " + cidr);
    }
    return new IpCidr(bytes, prefix);
  }

  /** 네트워크 주소 바이트의 복사본. */
  public byte[] network() {
    return network.clone();
  }

  /** 접두 비트 수. */
  public int prefixBits() {
    return prefixBits;
  }

  /** 주소가 이 대역에 속하는지. 주소 길이(IPv4/IPv6)가 다르면 속하지 않는다. */
  public boolean contains(InetAddress address) {
    return address != null && contains(address.getAddress());
  }

  /** 주소 바이트의 앞 prefixBits 비트가 네트워크 주소와 같은지로 대역 포함을 판정한다(IPv4·IPv6 공용). */
  public boolean contains(byte[] address) {
    if (address == null || address.length != network.length) return false;
    int fullBytes = prefixBits / 8;
    int remainBits = prefixBits % 8;
    for (int i = 0; i < fullBytes; i++) {
      if (address[i] != network[i]) return false;
    }
    if (remainBits > 0 && fullBytes < address.length) {
      int mask = 0xFF & (0xFF << (8 - remainBits));
      return (address[fullBytes] & mask) == (network[fullBytes] & mask);
    }
    return true;
  }

  /** 바이트 내용과 접두 길이로 비교한다(배열 참조 비교가 아니라). */
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    return o instanceof IpCidr other
        && prefixBits == other.prefixBits
        && Arrays.equals(network, other.network);
  }

  @Override
  public int hashCode() {
    return 31 * Arrays.hashCode(network) + prefixBits;
  }

  /** 로그·디버깅용 CIDR 표기(예: {@code 10.0.0.0/8}). */
  @Override
  public String toString() {
    try {
      return InetAddress.getByAddress(network).getHostAddress() + "/" + prefixBits;
    } catch (UnknownHostException e) {
      return Arrays.toString(network) + "/" + prefixBits;
    }
  }
}
