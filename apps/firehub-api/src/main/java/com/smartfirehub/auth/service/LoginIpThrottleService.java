package com.smartfirehub.auth.service;

import com.smartfirehub.auth.repository.LoginIpFailureRepository;
import com.smartfirehub.global.util.IpCidr;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 로그인 IP 기준 제한 정책 — 비밀번호 스프레이 차단(WD-578).
 *
 * <p>계정 잠금({@link LoginAttemptService}, 5회/15분)은 한 계정에 대한 무차별 대입만 막는다. 같은 IP 에서 계정을 바꿔 가며 흔한 비밀번호를
 * 하나씩 넣어 보면 계정마다 실패가 1~2회라 잠금에 걸리지 않는다. 그래서 <b>15분 동안 한 IP 키에서 로그인에 실패한 서로 다른 계정이 {@value
 * #MAX_DISTINCT_ACCOUNTS}개 이상</b>이면 그 IP 의 로그인을 맞는 비밀번호까지 거절한다.
 *
 * <ul>
 *   <li>IP 키 — IPv4 는 주소 하나, IPv6 는 앞 64비트(/64)로 묶는다. IPv6 는 한 가입자가 /64 를 통째로 받는 것이 보통이라, 주소 하나 단위로
 *       세면 주소를 바꿔 가며 쉽게 피한다. {@code ::ffff:a.b.c.d} 는 JDK 가 IPv4 로 돌려주므로 IPv4 키가 된다.
 *   <li>제외 대역 — {@code app.security.login-ip-limit.exempt}(쉼표로 구분한 CIDR, 기본 빈 값). 사내 NAT 처럼 많은 사람이
 *       한 주소로 나가는 대역을 위해 둔다. 제외 대역은 IP 제한을 받지 않고 계정 잠금만 적용된다.
 *   <li>IP 를 모르는 요청(null — 요청 컨텍스트 없는 내부 호출)은 제한하지 않는다 — 키가 없으면 셀 대상도 없다. 요청 IP 는 호출자가 {@code
 *       ClientIpExtractor}(신뢰 프록시 기준, WD-402)로 고르므로, 신뢰 프록시가 아닌 곳에서 보낸 X-Forwarded-For 는 아예 읽히지
 *       않는다.
 *   <li>해석할 수 없는 IP 는 {@link #UNPARSED_KEY} 하나로 모아 센다. 신뢰 프록시 뒤에서는 해석 안 되는 헤더 항목이 그대로 넘어오므로, 키를 주지
 *       않거나 값마다 키를 주면 헤더 한 줄로 제한을 피할 수 있기 때문이다.
 *   <li>같은 IP 키의 로그인은 검사 전에 트랜잭션 범위 잠금으로 줄을 세운다({@link #blockedKey} 참고) — 동시 요청이 한꺼번에 검사를 통과해 한도를
 *       넘겨 시도하지 못하게.
 *   <li>로그인 성공 시 기록을 지우지 않는다 — 공격자가 자기 계정으로 한 번 로그인해 카운터를 비우는 길을 막는다. 기록은 15분 뒤 만료로만 빠진다.
 * </ul>
 *
 * <p>실패 기록은 {@link LoginIpFailureRepository}로 PostgreSQL 에 둔다(login_attempts 와 같은 방식 — 재시작·멀티
 * 인스턴스에서 일관). DB 장애 시 예외를 그대로 올린다(fail-closed, {@link LoginAttemptService} 와 같음).
 */
@Service
public class LoginIpThrottleService {

  /** 한 IP 키에서 15분 안에 실패한 서로 다른 계정 수가 이 값 이상이면 그 IP 의 로그인을 거절한다. */
  public static final int MAX_DISTINCT_ACCOUNTS = 10;

  /**
   * 해석할 수 없는 클라이언트 IP(빈 값·호스트 이름·쓰레기 값)가 모두 함께 쓰는 키.
   *
   * <p>신뢰 프록시 뒤에서는 {@code ClientIpExtractor} 가 해석 안 되는 X-Forwarded-For 항목을 그대로 돌려준다(감사에 받은 값을
   * 남기려고). 이 값에 키를 주지 않으면 클라이언트가 {@code X-Forwarded-For: x} 한 줄로 제한을 끌 수 있었다. 그렇다고 값마다 키를 주면 값을 바꿔
   * 가며 피한다 — 그래서 모두 이 키 하나에 모아, 쓰레기 헤더 요청 전체가 한 버킷을 나눠 쓰고 10개 계정 뒤 함께 막힌다. 실제 IP 키(숫자·16진수·{@code
   * :}·{@code /})와 겹칠 수 없는 문자열이다.
   */
  public static final String UNPARSED_KEY = "unparsed";

  /** IP 기준 제한으로 거절할 때의 응답 메시지(429). 테넌트·운영자 로그인이 같은 문구를 쓰도록 한 곳에 둔다. */
  public static final String BLOCKED_MESSAGE = "이 위치에서 로그인 실패가 너무 많습니다. 잠시 후 다시 시도해 주세요.";

  /** IPv6 를 묶는 접두 길이(바이트) — /64. */
  private static final int IPV6_GROUP_BYTES = 8;

  private final LoginIpFailureRepository repository;

  /** IP 제한을 적용하지 않는 대역. 기동 시 한 번 파싱한다. */
  private final List<IpCidr> exempt;

  /**
   * 제외 대역 설정을 파싱해 만든다.
   *
   * <p>잘못된 항목이 있으면 {@link IllegalArgumentException} 으로 기동을 실패시킨다 — 보안 설정을 조용히 무시하지 않기 위해서다({@code
   * ClientIpExtractor} 의 신뢰 프록시 목록과 같은 규칙).
   *
   * @param repository 실패 기록 저장소
   * @param exemptCsv 쉼표로 구분한 CIDR 목록(빈 값·null 이면 제외 없음)
   */
  public LoginIpThrottleService(
      LoginIpFailureRepository repository,
      @Value("${app.security.login-ip-limit.exempt:}") String exemptCsv) {
    this.repository = repository;
    this.exempt = IpCidr.parseList(exemptCsv);
  }

  /**
   * 클라이언트 IP 의 제한 키를 만든다.
   *
   * @param clientIp 신뢰 프록시 기준으로 고른 클라이언트 IP(null 가능)
   * @return IPv4 는 주소 문자열, IPv6 는 {@code 2001:db8:1:2::/64} 모양의 /64 접두, 해석할 수 없으면(빈 값 포함) {@link
   *     #UNPARSED_KEY}. IP 를 모르거나(null) 제외 대역이면 empty(제한 안 함)
   */
  public Optional<String> keyFor(String clientIp) {
    // null 은 "HTTP 요청이 아님"(내부 호출) — 셀 주체가 없다. 요청은 있는데 값이 이상하면 아래에서 공용 키로 센다.
    if (clientIp == null) return Optional.empty();
    Optional<InetAddress> parsed = IpCidr.parseLiteral(clientIp);
    if (parsed.isEmpty()) return Optional.of(UNPARSED_KEY);
    InetAddress address = parsed.get();
    for (IpCidr cidr : exempt) {
      if (cidr.contains(address)) return Optional.empty();
    }
    if (address instanceof Inet4Address) {
      return Optional.of(address.getHostAddress());
    }
    return Optional.of(ipv6Slash64(address.getAddress()));
  }

  /**
   * 이 IP 에서 실패한 서로 다른 계정이 한도 이상이면 그 IP 키를 돌려준다. 키가 없으면(IP 모름·제외 대역) 잠그지도 묻지도 않고 empty.
   *
   * <p>막힌 키를 돌려주는 이유: 호출자가 거절 감사를 IP 키 단위로 합치는데, {@link #keyFor} 를 다시 부르면 같은 계산을 두 번 하고 "막혔는데 키가
   * 없는" 있을 수 없는 분기까지 다뤄야 한다.
   *
   * <p>집계 전에 그 IP 키의 트랜잭션 범위 잠금을 잡는다({@link LoginIpFailureRepository#lockKey}). 잠금이 없으면 같은 IP 에서
   * 동시에 온 요청들이 실패 기록이 커밋되기 전에 모두 "아직 한도 아래" 를 보고 통과해, 한도를 넘는 계정을 시도할 수 있다. 잠금은 호출자(로그인) 트랜잭션이 끝날 때
   * 풀리므로 실패 기록을 쓴 뒤 커밋할 때까지 같은 키의 다음 로그인이 기다린다 — 다음 요청은 늘어난 집계를 본다.
   *
   * <p>대가: 같은 IP 키(사내 NAT 등)의 로그인은 로그인 트랜잭션 동안(BCrypt 비교 포함) 한 번에 하나씩 처리된다. 다른 IP 끼리는 서로 막지 않고, 키가
   * 없는 요청(IP 모름·제외 대역)은 잠금을 잡지 않는다 — 많은 사람이 한 주소로 나가는 대역은 제외 대역으로 두면 직렬화도 피한다. 반드시 로그인 트랜잭션 안에서 불러야
   * 한다(밖이면 저장소가 예외로 알린다).
   */
  public Optional<String> blockedKey(String clientIp) {
    Optional<String> key = keyFor(clientIp);
    if (key.isEmpty()) return Optional.empty();
    repository.lockKey(key.get());
    return repository.countActiveAccounts(key.get()) >= MAX_DISTINCT_ACCOUNTS
        ? key
        : Optional.empty();
  }

  /**
   * 로그인 실패를 IP 키·계정 쌍으로 기록한다.
   *
   * <p>같은 계정의 반복 실패는 한 번으로 센다(저장소 UPSERT). 기록 뒤 집계를 다시 묻지 않는다 — 결과를 쓰는 호출자가 없고, 다음 로그인의 {@link
   * #blockedKey} 가 어차피 센다.
   *
   * @param clientIp 클라이언트 IP(null 가능)
   * @param username 정규화된 로그인 아이디(null 이면 기록하지 않음)
   */
  public void loginFailed(String clientIp, String username) {
    if (username == null) return;
    Optional<String> key = keyFor(clientIp);
    if (key.isEmpty()) return;
    repository.recordFailure(key.get(), username);
  }

  /**
   * IPv6 주소의 앞 64비트를 {@code xxxx:xxxx:xxxx:xxxx::/64} 로 적는다. 각 그룹은 앞 0 을 뺀 소문자 16진수라, 같은 /64 면 입력
   * 표기(대소문자· 축약)와 관계없이 같은 문자열이 된다.
   */
  private static String ipv6Slash64(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < IPV6_GROUP_BYTES; i += 2) {
      int group = ((bytes[i] & 0xFF) << 8) | (bytes[i + 1] & 0xFF);
      sb.append(Integer.toHexString(group)).append(':');
    }
    return sb.append(":/64").toString();
  }
}
