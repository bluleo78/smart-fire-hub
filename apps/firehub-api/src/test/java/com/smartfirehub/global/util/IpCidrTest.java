package com.smartfirehub.global.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** IpCidr 단위 테스트 — 리터럴만 해석하고(DNS 없음) 대역 포함을 비트 단위로 판정한다(WD-402). */
class IpCidrTest {

  /** 숫자 리터럴만 주소로 해석하고, 호스트 이름·범위 밖 옥텟·존 ID·쓰레기는 거절한다 */
  @Test
  void parseLiteral_acceptsOnlyNumericLiterals() {
    assertThat(IpCidr.parseLiteral(" 10.0.0.1 ")).isPresent();
    assertThat(IpCidr.parseLiteral("::1")).isPresent();
    assertThat(IpCidr.parseLiteral("[2001:db8::1]")).isPresent();
    assertThat(IpCidr.parseLiteral("localhost")).isEmpty();
    assertThat(IpCidr.parseLiteral("example.com")).isEmpty();
    assertThat(IpCidr.parseLiteral("999.1.1.1")).isEmpty();
    assertThat(IpCidr.parseLiteral("1.2.3")).isEmpty();
    assertThat(IpCidr.parseLiteral("fe80::1%eth0")).isEmpty();
    assertThat(IpCidr.parseLiteral("1:2:3")).isEmpty();
    assertThat(IpCidr.parseLiteral("")).isEmpty();
    assertThat(IpCidr.parseLiteral(null)).isEmpty();
  }

  /** 접두 비트 경계와 IPv4/IPv6 길이 불일치 */
  @Test
  void contains_checksPrefixBits() {
    IpCidr v4 = IpCidr.parse("172.16.0.0/12");
    assertThat(v4.contains(IpCidr.parseLiteral("172.31.255.255").get())).isTrue();
    assertThat(v4.contains(IpCidr.parseLiteral("172.32.0.0").get())).isFalse();
    assertThat(v4.contains(IpCidr.parseLiteral("::1").get())).isFalse();

    IpCidr single = IpCidr.parse("192.168.1.5");
    assertThat(single.prefixBits()).isEqualTo(32);
    assertThat(single.contains(IpCidr.parseLiteral("192.168.1.5").get())).isTrue();
    assertThat(single.contains(IpCidr.parseLiteral("192.168.1.6").get())).isFalse();

    IpCidr ula = IpCidr.parse("fc00::/7");
    assertThat(ula.contains(IpCidr.parseLiteral("fd12::1").get())).isTrue();
    assertThat(ula.contains(IpCidr.parseLiteral("fe00::1").get())).isFalse();
  }

  /** 잘못된 CIDR 는 예외 */
  @Test
  void parse_rejectsInvalid() {
    assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/33"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IpCidr.parse("10.0.0.0/x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IpCidr.parse("host.example/8"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> IpCidr.parse(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
