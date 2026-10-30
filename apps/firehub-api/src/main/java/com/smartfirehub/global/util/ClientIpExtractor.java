package com.smartfirehub.global.util;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 클라이언트 IP 추출기 — 신뢰 프록시 기준(WD-402).
 *
 * <p>리버스 프록시(nginx 등) 뒤에서는 {@link HttpServletRequest#getRemoteAddr()} 가 프록시 IP 라 감사 로그·트리거 허용 IP
 * 검사에 {@code X-Forwarded-For} 를 봐야 한다(이슈 #147). 그러나 이 헤더는 클라이언트가 마음대로 보낼 수 있고, nginx 의 {@code
 * $proxy_add_x_forwarded_for} 는 받은 값 <b>뒤에</b> 실제 접속 주소를 덧붙일 뿐이다. 예전처럼 맨 앞 값을 믿으면 {@code
 * X-Forwarded-For: <허용 IP>} 한 줄로 API 트리거의 허용 IP 검사를 통과하고 감사 IP 도 위조된다(WD-402).
 *
 * <p>그래서 Tomcat {@code RemoteIpValve} 의 internalProxies 와 같은 규칙을 쓴다:
 *
 * <ul>
 *   <li>직접 접속한 주소(remoteAddr)가 신뢰 프록시가 아니면 헤더를 아예 보지 않고 remoteAddr 를 쓴다.
 *   <li>신뢰 프록시면 헤더를 오른쪽(가장 최근에 덧붙인 값)부터 읽어 신뢰 프록시를 건너뛰고, 처음 나온 신뢰하지 않는 값을 쓴다.
 *   <li>모두 신뢰 프록시면 맨 오른쪽 값(마지막 프록시가 덧붙인 직전 홉), 헤더가 없거나 비었으면 remoteAddr 를 쓴다. 맨 왼쪽은 클라이언트가 쓴 값이라 믿지
 *       않는다.
 * </ul>
 *
 * <p>신뢰 프록시 목록은 {@code app.security.trusted-proxies}(쉼표로 구분한 CIDR, 비우면 기본값)로 바꿀 수 있고, 기본값은
 * 루프백·사설망·링크 로컬이다 — 같은 도커 네트워크의 nginx 가 사설 주소로 들어오기 때문이다. Spring 의 {@code
 * server.forward-headers-strategy} 는 전역 영향이 커서 건드리지 않는다.
 */
@Component
public class ClientIpExtractor {

  /** 기본 신뢰 프록시 대역(WD-402) — 루프백, RFC 1918 사설망, 링크 로컬, IPv6 ULA·링크 로컬. */
  public static final String DEFAULT_TRUSTED_PROXIES =
      "127.0.0.0/8,::1/128,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,fc00::/7,fe80::/10";

  private static final String X_FORWARDED_FOR = "X-Forwarded-For";

  /** 신뢰 프록시 대역 목록. 기동 시 한 번 파싱하며, 잘못된 항목이 있으면 기동을 실패시킨다 — 보안 설정을 조용히 무시하지 않기 위해서다. */
  private final List<IpCidr> trustedProxies;

  /**
   * 설정값으로 신뢰 프록시 목록을 만든다.
   *
   * <p>기본 목록의 원본은 {@link #DEFAULT_TRUSTED_PROXIES} 하나다(WD-402). 설정이 비어 있으면(기본) 그 목록을 쓰고, 운영자는
   * {@code APP_TRUSTED_PROXIES} 로 nginx/admin 컨테이너 대역처럼 좁힌 목록을 준다.
   *
   * @param trustedProxies 쉼표로 구분한 CIDR 목록(빈 값이면 기본 목록)
   */
  @Autowired // 생성자가 둘이라 스프링이 쓸 생성자를 명시한다
  public ClientIpExtractor(@Value("${app.security.trusted-proxies:}") String trustedProxies) {
    this(
        parseList(
            trustedProxies == null || trustedProxies.isBlank()
                ? DEFAULT_TRUSTED_PROXIES
                : trustedProxies));
  }

  /** 대역 목록을 직접 받는 생성자 — 같은 패키지의 단위 테스트용(빈 목록이면 신뢰 프록시 없음 → 항상 remoteAddr). */
  ClientIpExtractor(List<IpCidr> trustedProxies) {
    this.trustedProxies = List.copyOf(trustedProxies);
  }

  /**
   * 요청의 실제 클라이언트 IP 를 신뢰 프록시 기준으로 고른다(WD-402).
   *
   * <p>헤더 항목은 앞뒤 공백을 없애 비교한다. 주소로 해석되지 않는 항목은 "신뢰하지 않는 값"으로 보고 그대로 돌려준다 — 감사에는 받은 그대로 남기고, 허용 IP
   * 검사에서는 어떤 대역과도 맞지 않아 차단된다. 해석되지 않는 값이 신뢰 프록시로 취급되는 경로는 없다.
   *
   * @param request HTTP 요청
   * @return 클라이언트 IP 문자열(요청이 null 이면 null)
   */
  public String extract(HttpServletRequest request) {
    if (request == null) {
      return null;
    }
    String remoteAddr = request.getRemoteAddr();
    // 직접 접속한 상대가 신뢰 프록시가 아니면 헤더는 클라이언트가 쓴 값일 뿐이다 — 보지 않는다.
    if (!isTrustedProxy(remoteAddr)) {
      return remoteAddr;
    }
    // 여러 줄로 온 헤더도 순서대로 이어 하나의 체인으로 본다(프록시마다 줄을 따로 붙이는 경우).
    List<String> chain = new ArrayList<>();
    var headers = request.getHeaders(X_FORWARDED_FOR);
    if (headers != null) {
      while (headers.hasMoreElements()) {
        String line = headers.nextElement();
        if (line == null) continue;
        for (String part : line.split(",")) {
          String entry = part.trim();
          if (!entry.isEmpty()) chain.add(entry);
        }
      }
    }
    if (chain.isEmpty()) {
      return remoteAddr;
    }
    // 오른쪽이 우리 쪽에 가까운 홉이다. 신뢰 프록시를 건너뛰고 처음 만나는 신뢰하지 않는 값이 실제 클라이언트다.
    for (int i = chain.size() - 1; i >= 0; i--) {
      String entry = chain.get(i);
      if (!isTrustedProxy(entry)) {
        return entry;
      }
    }
    // 전부 신뢰 프록시면 맨 오른쪽 값 — 마지막 신뢰 프록시가 직접 덧붙인 직전 홉 — 을 쓴다(WD-402). 맨 왼쪽은 클라이언트가 마음대로
    // 쓴 값이라, 내부망 공격자(10.9.9.9)가 "X-Forwarded-For: 10.1.2.3" 이나 "127.0.0.1" 을 보내 허용 목록을 통과하는 길이 된다.
    return chain.get(chain.size() - 1);
  }

  /** 주소가 신뢰 프록시 대역에 속하는지. 해석되지 않는 값은 절대 신뢰하지 않는다. */
  boolean isTrustedProxy(String address) {
    Optional<InetAddress> parsed = IpCidr.parseLiteral(address);
    if (parsed.isEmpty()) return false;
    InetAddress inet = parsed.get();
    for (IpCidr cidr : trustedProxies) {
      if (cidr.contains(inet)) return true;
    }
    return false;
  }

  /** 쉼표 목록을 CIDR 로 파싱한다. 빈 항목은 건너뛰고, 잘못된 항목은 예외로 알린다. */
  private static List<IpCidr> parseList(String csv) {
    if (csv == null || csv.isBlank()) return List.of();
    return Arrays.stream(csv.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(IpCidr::parse)
        .toList();
  }
}
