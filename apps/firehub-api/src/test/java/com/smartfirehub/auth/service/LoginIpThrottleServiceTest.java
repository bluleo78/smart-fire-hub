package com.smartfirehub.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * 로그인 IP 기준 제한 정책 단위 테스트(WD-578).
 *
 * <p>IP 키 계산(IPv4 주소 하나·IPv6 /64 묶음·IPv4 매핑 IPv6), 제외 대역, 모르는 IP(null)·해석 불가 IP(공용 키), 집계 전 잠금, 한도
 * 경계값을 저장소 목으로 검증한다. 저장소 동작(UPSERT·만료)은 {@code JooqLoginIpFailureRepositoryTest} 가, 로그인 흐름은 {@code
 * LoginIpLimitTest} 가 맡는다.
 */
class LoginIpThrottleServiceTest {

  private LoginIpFailureRepository repository;
  private LoginIpThrottleService service;

  @BeforeEach
  void setUp() {
    repository = mock(LoginIpFailureRepository.class);
    service = new LoginIpThrottleService(repository, "");
  }

  @Test
  @DisplayName("IPv4 는 주소 하나가 키다")
  void keyFor_ipv4_isAddressItself() {
    assertThat(service.keyFor("203.0.113.7")).contains("203.0.113.7");
    assertThat(service.keyFor(" 203.0.113.7 ")).contains("203.0.113.7");
  }

  @Test
  @DisplayName("IPv6 는 같은 /64 면 주소가 달라도 같은 키, 다른 /64 면 다른 키")
  void keyFor_ipv6_groupsBySlash64() {
    String a = service.keyFor("2001:db8:1:2::1").orElseThrow();
    String b = service.keyFor("2001:db8:1:2:ffff:eeee:dddd:cccc").orElseThrow();
    String other = service.keyFor("2001:db8:1:3::1").orElseThrow();

    assertThat(a).isEqualTo("2001:db8:1:2::/64");
    assertThat(b).isEqualTo(a);
    assertThat(other).isEqualTo("2001:db8:1:3::/64").isNotEqualTo(a);
    // 대괄호·대문자 표기도 같은 키
    assertThat(service.keyFor("[2001:DB8:1:2::abcd]")).contains(a);
  }

  @Test
  @DisplayName("IPv4 매핑 IPv6(::ffff:a.b.c.d)는 IPv4 키로 센다")
  void keyFor_ipv4MappedIpv6_isIpv4Key() {
    assertThat(service.keyFor("::ffff:1.2.3.4")).contains("1.2.3.4");
  }

  @Test
  @DisplayName("IP 를 모르면(null — 요청 없는 내부 호출) 키가 없다(제한 안 함)")
  void keyFor_null_isEmpty() {
    assertThat(service.keyFor(null)).isEmpty();
  }

  @Test
  @DisplayName("해석할 수 없는 IP(빈 값·호스트 이름·쓰레기 값)는 모두 하나의 공용 키로 센다 — 제한을 피하지 못한다")
  void keyFor_unparseable_isSharedConstantKey() {
    // 신뢰 프록시 뒤에서 "X-Forwarded-For: x" 처럼 쓰레기 값을 보내면 ClientIpExtractor 가 그 값을 그대로 돌려준다.
    // 예전에는 키가 없어 제한을 통째로 피했다 — 이제 쓰레기 값끼리 한 버킷에 모여 10개 계정 뒤 막힌다.
    String key = LoginIpThrottleService.UNPARSED_KEY;
    assertThat(service.keyFor("")).contains(key);
    assertThat(service.keyFor("   ")).contains(key);
    assertThat(service.keyFor("localhost")).contains(key);
    assertThat(service.keyFor("9".repeat(100))).contains(key);
    assertThat(service.keyFor("1:2:3")).contains(key);
    assertThat(service.keyFor("junk-1")).contains(key);
  }

  @Test
  @DisplayName("IP 를 모르면(null) 막지도 기록하지도 잠그지도 않는다 — 저장소를 부르지 않는다")
  void nullIp_neverTouchesRepository() {
    assertThat(service.blockedKey(null)).isEmpty();
    service.loginFailed(null, "alice");
    verifyNoInteractions(repository);
  }

  @Test
  @DisplayName("해석 불가 IP 는 공용 키로 잠그고·집계하고·기록한다")
  void unparseableIp_usesSharedKey() {
    String key = LoginIpThrottleService.UNPARSED_KEY;
    when(repository.countActiveAccounts(key)).thenReturn(10);

    assertThat(service.blockedKey("garbage")).contains(key);
    service.loginFailed("other-garbage", "alice");

    verify(repository).lockKey(key);
    verify(repository).recordFailure(key, "alice");
  }

  @Test
  @DisplayName("집계 전에 그 IP 키의 트랜잭션 잠금을 먼저 잡는다 — 같은 IP 의 동시 로그인이 한꺼번에 검사를 통과하지 못하게")
  void blockedKey_locksKeyBeforeCounting() {
    service.blockedKey("203.0.113.5");

    InOrder order = inOrder(repository);
    order.verify(repository).lockKey("203.0.113.5");
    order.verify(repository).countActiveAccounts("203.0.113.5");
  }

  @Test
  @DisplayName("제외 대역(IPv4·IPv6)은 키가 없고 저장소를 부르지 않는다")
  void exemptRanges_areNotLimited() {
    LoginIpThrottleService exempting =
        new LoginIpThrottleService(repository, " 10.20.0.0/16 , 2001:db8:aaaa::/48 ");

    assertThat(exempting.keyFor("10.20.3.4")).isEmpty();
    assertThat(exempting.keyFor("2001:db8:aaaa:1::5")).isEmpty();
    assertThat(exempting.blockedKey("10.20.3.4")).isEmpty();
    exempting.loginFailed("2001:db8:aaaa:1::5", "bob");
    verifyNoInteractions(repository);

    // 대역 밖은 그대로 센다
    assertThat(exempting.keyFor("10.21.0.1")).contains("10.21.0.1");
    assertThat(exempting.keyFor("2001:db8:bbbb::1")).contains("2001:db8:bbbb:0::/64");
  }

  @Test
  @DisplayName("잘못된 제외 대역 항목은 생성자에서 예외 — 보안 설정을 조용히 무시하지 않는다")
  void invalidExempt_failsFast() {
    assertThatThrownBy(() -> new LoginIpThrottleService(repository, "10.0.0.0/8,not-an-ip"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new LoginIpThrottleService(repository, "10.0.0.0/33"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("null 제외 설정은 제외 없음으로 본다")
  void nullExempt_meansNoExemption() {
    LoginIpThrottleService noExempt = new LoginIpThrottleService(repository, null);
    assertThat(noExempt.keyFor("10.0.0.1")).contains("10.0.0.1");
  }

  @Test
  @DisplayName("서로 다른 실패 계정 9개는 막지 않고 10개부터 막는다")
  void blockedKey_boundary() {
    when(repository.countActiveAccounts("203.0.113.9")).thenReturn(9);
    assertThat(service.blockedKey("203.0.113.9")).isEmpty();

    when(repository.countActiveAccounts("203.0.113.9")).thenReturn(10);
    assertThat(service.blockedKey("203.0.113.9")).contains("203.0.113.9");

    assertThat(LoginIpThrottleService.MAX_DISTINCT_ACCOUNTS).isEqualTo(10);
  }

  @Test
  @DisplayName("IPv6 는 /64 키로 집계를 묻는다")
  void blockedKey_ipv6_queriesBySlash64Key() {
    when(repository.countActiveAccounts("2001:db8:1:2::/64")).thenReturn(10);
    assertThat(service.blockedKey("2001:db8:1:2::99")).contains("2001:db8:1:2::/64");
  }

  @Test
  @DisplayName("실패 기록은 키·계정으로 남기기만 한다 — 기록 뒤 집계를 다시 묻지 않는다")
  void loginFailed_recordsOnly() {
    service.loginFailed("198.51.100.1", "user9");

    verify(repository).recordFailure("198.51.100.1", "user9");
    verify(repository, never()).countActiveAccounts(anyString());
  }

  @Test
  @DisplayName("계정 이름이 null 이면 기록하지 않는다")
  void loginFailed_nullUsername_skips() {
    service.loginFailed("198.51.100.1", null);
    verify(repository, never()).recordFailure(anyString(), anyString());
  }
}
