package com.smartfirehub.pipeline.service.executor;

import java.net.URI;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URL 의 호스트·포트(WD-676) — {@link URI#getHost()} 가 null 을 주는 주소에서도 호스트를 읽는다.
 *
 * <p><b>왜 필요한가.</b> {@code java.net.URI} 는 호스트 이름에 밑줄({@code my_api.internal})이 들면 서버 기반 authority
 * 로 해석하지 못해 {@code getHost()} 가 null, {@code getPort()} 가 -1 이다. 그래서 SSRF 검사는 「URL 에 호스트가 없습니다」로
 * 거부하고, 리다이렉트 출처 판정은 같은 호스트로 가는 홉도 다른 출처로 봐 인증을 뗐다. Python 실행기({@code urlsplit().hostname})는 이런
 * 호스트를 그대로 읽으므로 두 실행기가 달랐다.
 *
 * <p><b>보안(SSRF 해석 차이).</b> 여기서 읽은 호스트와 HTTP 클라이언트가 실제로 접속하는 호스트가 다르면 SSRF 검사를 우회할 수 있다. 그래서 {@code
 * getHost()} 가 null 일 때의 대체 해석은 보수적으로 한다 — 사용자 정보({@code @})는 하나까지, 호스트는 영문·숫자·{@code .}·{@code
 * _}·{@code -} 만, 포트는 숫자 1~5자리(0~65535)만 받는다. 역슬래시·{@code %}·대괄호가 authority 에 있으면 해석하지 않는다(빈 값) —
 * 파서마다 다르게 읽는 문자이기 때문이다.
 *
 * @param host 소문자 호스트(IPv6 는 {@code getHost()} 그대로 대괄호 포함)
 * @param port 명시 포트, 없으면 -1
 */
public record UrlAuthority(String host, int port) {

  /** {@code getHost()} 가 null 일 때 raw authority 를 읽는 엄격한 형식 — [사용자정보@]호스트[:포트]. */
  private static final Pattern REGISTRY_AUTHORITY =
      Pattern.compile("^(?:[^@/\\\\]*@)?([A-Za-z0-9._-]+)(?::(\\d{1,5}))?$");

  /**
   * URI 의 호스트·포트를 읽는다. {@code getHost()} 가 있으면 그 값과 {@code getPort()} 를, 없으면 raw authority 를 위 엄격한
   * 형식으로 해석한다. 해석할 수 없으면(호스트 없음·모호한 문자·포트 범위 밖) 빈 값이다 — 호출자는 이를 「호스트를 알 수 없음」으로 다룬다.
   */
  public static Optional<UrlAuthority> of(URI uri) {
    if (uri == null) return Optional.empty();
    String host = uri.getHost();
    if (host != null && !host.isBlank()) {
      return Optional.of(new UrlAuthority(host.toLowerCase(Locale.ROOT), uri.getPort()));
    }
    String raw = uri.getRawAuthority();
    if (raw == null || raw.isEmpty()) return Optional.empty();
    // 파서마다 다르게 읽는 문자(역슬래시·퍼센트 인코딩·IPv6 대괄호)가 있으면 해석하지 않는다 — SSRF 해석 차이를 막는다.
    if (raw.indexOf('\\') >= 0
        || raw.indexOf('%') >= 0
        || raw.indexOf('[') >= 0
        || raw.indexOf(']') >= 0) {
      return Optional.empty();
    }
    Matcher m = REGISTRY_AUTHORITY.matcher(raw);
    if (!m.matches()) return Optional.empty();
    int port = -1;
    if (m.group(2) != null) {
      port = Integer.parseInt(m.group(2));
      if (port > 65535) return Optional.empty();
    }
    return Optional.of(new UrlAuthority(m.group(1).toLowerCase(Locale.ROOT), port));
  }

  /**
   * 이 서버의 HTTP 클라이언트(Reactor Netty)가 부를 수 있는 주소인지 요청 전에 본다(WD-676). Netty 는 getHost() 가 null 인 주소
   * (밑줄 든 호스트 등)를 거부하므로, 호스트는 읽히는데 getHost() 가 null 이면 호스트 이름을 담은 고정 문구로 미리 실패한다.
   *
   * @throws ApiCallException 재시도하지 않는 실패({@link ApiCallErrorMessages#unsupportedHostName})
   */
  public static void requireClientSupportedHost(URI uri) {
    if (uri == null || uri.getHost() != null) return;
    String host = hostOrNull(uri);
    if (host != null) {
      throw new ApiCallException(ApiCallErrorMessages.unsupportedHostName(host));
    }
  }

  /** 문자열 판 — URI 로 읽을 수 없으면 검사하지 않는다(그 오류는 HTTP 클라이언트가 원래대로 낸다). */
  public static void requireClientSupportedHost(String url) {
    if (url == null) return;
    try {
      requireClientSupportedHost(new URI(url));
    } catch (java.net.URISyntaxException e) {
      // 검사 대상 아님
    }
  }

  /** 호스트만 — 오류 문구용. 알 수 없으면 null(문구 쪽이 「알 수 없음」으로 바꾼다). */
  public static String hostOrNull(URI uri) {
    return of(uri).map(UrlAuthority::host).orElse(null);
  }

  /** 포트가 없으면 스킴의 기본 포트(http 80·https 443). 모르는 스킴이면 -1. */
  public int effectivePort(String scheme) {
    if (port != -1) return port;
    if ("http".equalsIgnoreCase(scheme)) return 80;
    if ("https".equalsIgnoreCase(scheme)) return 443;
    return -1;
  }
}
