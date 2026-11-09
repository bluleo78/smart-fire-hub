package com.smartfirehub.apiconnection.service;

import com.smartfirehub.pipeline.service.executor.UrlAuthority;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * URL 의 사용자 정보(userinfo, {@code 아이디:비밀번호@})를 읽고·떼고·가리는 규칙 한 곳(WD-681).
 *
 * <p><b>왜 필요한가.</b> 예전에는 API 연결의 baseUrl 에 {@code https://아이디:비밀번호@호스트} 를 넣으면 그대로 저장되고 목록·상세·선택
 * 목록·연결 테스트 {@code requestUrl}·미리보기 {@code resolvedUrl}·실행 기록에 비밀번호가 원문으로 나갔다(Python 실행기는 httpx 가 이
 * 사용자 정보를 Basic 인증으로 보내 실제로 쓰이고 있었다). 이제 BASIC 인증 방식이 생겨 새 연결은 사용자 정보를 인증 설정으로 옮기고, 이미 저장된 옛
 * 연결(API_KEY·BEARER + 사용자 정보)은 이관하지 않고 <b>응답에서만</b> 가린다. 가리는 모양·되돌리기 판정이 경로마다 달라지지 않게 여기 한 곳에 둔다.
 *
 * <p><b>가림 규칙.</b> 비밀번호만 {@link AuthQueryMasker#PASSWORD_MASK}({@code ****}) 로 바꾼다 — {@code
 * https://alice:****@host/path}. 아이디는 연결을 알아보는 데 필요하고 비밀이 아니라 그대로 둔다. 비밀번호가 없으면({@code
 * alice@host}·{@code alice:@host}) 가릴 것이 없어 그대로다.
 *
 * <p><b>해석 방식.</b> {@code java.net.URI} 는 밑줄 든 호스트·한글 경로 등에서 실패해 문자열로 읽는다 — {@code 스킴://} 뒤부터 첫
 * {@code /}·{@code ?}·{@code #} 앞까지를 authority 로 보고, 그 안의 마지막 {@code @} 앞을 사용자 정보로 본다. 저장 검사({@code
 * UrlAuthority})는 사용자 정보의 {@code @} 를 하나까지만 받으므로 저장된 값에서는 두 해석이 같다.
 */
public final class BaseUrlUserInfo {

  /** URL 스킴(RFC 3986) — 이 모양 뒤에 {@code ://} 가 와야 authority 가 있는 URL 로 본다. */
  private static final Pattern SCHEME = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*");

  /**
   * 자유 문장 안 URL 의 {@code 스킴://사용자정보@}(WD-699). 사용자 정보는 authority 안이라 공백·{@code /}·{@code ?}·{@code
   * #}·큰따옴표·꺾쇠에서 끝나고(작은따옴표는 RFC 3986 이 사용자 정보에 허락하는 문자라 비밀번호 {@code pa'ss} 를 놓치지 않게 끊지 않는다 — 공백 없는
   * 작은따옴표 문장을 더 가리는 쪽이 비밀번호를 흘리는 쪽보다 낫다), {@code @} 가 여럿이면 마지막 {@code @} 까지다({@link #split} 과 같은
   * 해석). {@code ?}·{@code #} 를 빼야 {@code https://host?email=a@b.com} 의 쿼리 {@code @} 를 사용자 정보로 잘못 읽지
   * 않는다. Python {@code api_call_errors._URL_USERINFO_FOR_MASK} 와 같은 패턴이다.
   */
  private static final Pattern USERINFO_IN_TEXT =
      Pattern.compile("([A-Za-z][A-Za-z0-9+.\\-]*://)((?:[^\\s/?#@\"<>]+@)+)");

  private BaseUrlUserInfo() {}

  /**
   * 자유 문장(오류 문구·로그·응답 헤더 값) 안 모든 URL 의 사용자 정보 비밀번호를 {@link #mask} 와 같은 모양({@code 아이디:****@})으로
   * 가린다(WD-699).
   *
   * <p><b>왜.</b> 옛 연결(API_KEY·BEARER + baseUrl 사용자 정보)은 이관하지 않아 그 비밀번호가 {@link
   * AuthQueryMasker.SentSecrets} 의 비밀 목록에 없다 — 분류하지 못한 예외 원문이나 대상 서버가 되울린 헤더에 호출 URL 이 실리면 비밀번호가
   * 그대로 나갔다. 비밀번호가 없거나 비었으면, 이미 가린 {@code ****} 이면 그대로다(두 번 가려도 같다). null 이면 null.
   */
  public static String maskInText(String text) {
    if (text == null || text.indexOf('@') < 0) return text;
    return USERINFO_IN_TEXT
        .matcher(text)
        .replaceAll(
            m -> {
              String chain = m.group(2);
              String raw = chain.substring(0, chain.length() - 1);
              int colon = raw.indexOf(':');
              if (colon < 0 || colon == raw.length() - 1) {
                return java.util.regex.Matcher.quoteReplacement(m.group());
              }
              return java.util.regex.Matcher.quoteReplacement(
                  m.group(1) + raw.substring(0, colon) + ":" + AuthQueryMasker.PASSWORD_MASK + "@");
            });
  }

  /**
   * URL 에서 읽은 사용자 정보 — 퍼센트 인코딩을 푼 값이다(httpx 가 Basic 인증으로 보내던 값과 같다).
   *
   * @param username 아이디(빈 문자열일 수 있다)
   * @param password 비밀번호. {@code :} 가 없으면 null
   */
  public record UserInfo(String username, String password) {}

  /**
   * URL 을 한 번만 훑어 나눈 조각들 — 각 공개 메서드가 같은 URL 을 다시 훑지 않도록 디코딩한 사용자 정보까지 함께 담는다.
   *
   * @param prefix {@code 스킴://}
   * @param rawUserInfo 인코딩된 사용자 정보({@code @} 제외). 없으면 null
   * @param hostPort {@code 호스트[:포트]} — 첫 {@code /}·{@code ?}·{@code #} 앞까지
   * @param tail 경로·쿼리·프래그먼트(없으면 빈 문자열)
   * @param userInfo 퍼센트 디코딩한 사용자 정보. 없으면 null
   */
  private record Parts(
      String prefix, String rawUserInfo, String hostPort, String tail, UserInfo userInfo) {

    /** 사용자 정보 자리만 {@code rawUserInfo}(인코딩된 그대로)로 바꾼 URL. null 이면 사용자 정보를 뗀다. */
    String withUserInfo(String raw) {
      return prefix + (raw == null ? "" : raw + "@") + hostPort + tail;
    }
  }

  /** URL 을 나눈다. {@code 스킴://} 모양이 아니면 빈 값이다(사용자 정보도 없다고 본다). */
  private static Optional<Parts> split(String url) {
    if (url == null) return Optional.empty();
    int schemeEnd = url.indexOf("://");
    if (schemeEnd <= 0 || !SCHEME.matcher(url.substring(0, schemeEnd)).matches()) {
      return Optional.empty();
    }
    int authStart = schemeEnd + 3;
    int authEnd = url.length();
    for (char c : new char[] {'/', '?', '#'}) {
      int i = url.indexOf(c, authStart);
      if (i >= 0) authEnd = Math.min(authEnd, i);
    }
    String prefix = url.substring(0, authStart);
    String tail = url.substring(authEnd);
    int at = url.lastIndexOf('@', authEnd - 1);
    if (at < authStart) {
      return Optional.of(new Parts(prefix, null, url.substring(authStart, authEnd), tail, null));
    }
    String raw = url.substring(authStart, at);
    return Optional.of(
        new Parts(prefix, raw, url.substring(at + 1, authEnd), tail, decodeUserInfo(raw)));
  }

  /** 사용자 정보가 있는 URL 의 조각. 사용자 정보가 없거나 URL 모양이 아니면 빈 값. */
  private static Optional<Parts> splitWithUserInfo(String url) {
    return split(url).filter(p -> p.rawUserInfo() != null);
  }

  /** URL 에 사용자 정보({@code …@})가 있는지. */
  public static boolean hasUserInfo(String url) {
    return splitWithUserInfo(url).isPresent();
  }

  /**
   * 응답·화면·실행 기록용으로 비밀번호만 가린다 — {@code https://alice:s3cret@host/p} → {@code
   * https://alice:****@host/p}. 사용자 정보가 없거나 비밀번호가 비었으면 받은 그대로다. null 이면 null.
   */
  public static String mask(String url) {
    Optional<Parts> parts = splitWithUserInfo(url);
    if (parts.isEmpty() || !hasPassword(parts.get().userInfo())) return url;
    Parts p = parts.get();
    String rawUser = p.rawUserInfo().substring(0, p.rawUserInfo().indexOf(':'));
    return p.withUserInfo(rawUser + ":" + AuthQueryMasker.PASSWORD_MASK);
  }

  /** 사용자 정보를 뗀 URL. 사용자 정보가 없으면 받은 그대로다. */
  public static String strip(String url) {
    return splitWithUserInfo(url).map(p -> p.withUserInfo(null)).orElse(url);
  }

  /** 사용자 정보를 퍼센트 디코딩해 읽는다. 없으면 빈 값. */
  public static Optional<UserInfo> extract(String url) {
    return splitWithUserInfo(url).map(Parts::userInfo);
  }

  /**
   * 사용자 정보의 비밀번호가 가린 값({@code ****})인지 — 화면이 받은 가린 baseUrl 을 호스트·경로·아이디를 바꿔 보내면 저장값으로 되돌리지 않으므로, 그
   * 가린 값이 실제 비밀번호로 저장되지 않게 거절하는 데 쓴다.
   */
  public static boolean hasMaskedPassword(String url) {
    return extract(url).map(u -> AuthQueryMasker.PASSWORD_MASK.equals(u.password())).orElse(false);
  }

  /** 사용자 정보에 비어 있지 않은 비밀번호가 있는지 — 가릴(되살릴) 비밀이 있는 저장값인지 본다(WD-681). */
  public static boolean hasPassword(String url) {
    return extract(url).map(BaseUrlUserInfo::hasPassword).orElse(false);
  }

  /** 디코딩한 사용자 정보에 비어 있지 않은 비밀번호가 있는지. */
  private static boolean hasPassword(UserInfo u) {
    return u != null && u.password() != null && !u.password().isEmpty();
  }

  /**
   * 요청 baseUrl 의 사용자 정보를 저장된 원래 사용자 정보로 되살린 URL(WD-681 리뷰). 되살릴 수 없으면 빈 값이다.
   *
   * <p><b>왜.</b> 수정 화면은 가린 baseUrl({@code 아이디:****@})을 받아 경로·쿼리만 고쳐 보낼 수 있다. 옛 연결(API_KEY·BEARER +
   * 사용자 정보)은 이관하지 않으므로 이때 저장된 비밀번호를 새 URL 에 되살려야 한다. 하지만 다른 주소로 바꾼 URL 에 저장된 비밀번호를 붙이면 비밀이 새 서버로
   * 가므로, <b>스킴·호스트·실제 포트(대소문자 무시, {@code https://h} 와 {@code https://h:443} 은 같다)와 아이디가 저장값과 같을
   * 때만</b> 되살린다 — 경로·쿼리·프래그먼트는 요청 값을 쓴다.
   *
   * <ul>
   *   <li>요청 비밀번호가 가린 값({@code ****})이고 저장값에 비밀번호가 있으면 저장된 사용자 정보(인코딩 그대로)로 바꾼다.
   *   <li>요청 사용자 정보가 저장값과 글자 그대로 같으면(아이디만 있는 옛 값 등) 그대로 둔다 — 새로 넣은 값이 아니다.
   *   <li>그 밖(사용자 정보 없음·다른 비밀번호 입력·주소나 아이디가 다름)은 빈 값.
   * </ul>
   *
   * @param requested 요청 baseUrl
   * @param stored 저장된 원래 baseUrl
   */
  public static Optional<String> restoreStoredUserInfo(String requested, String stored) {
    Optional<Parts> rq = splitWithUserInfo(requested);
    Optional<Parts> st = splitWithUserInfo(stored);
    if (rq.isEmpty() || st.isEmpty()) return Optional.empty();
    Parts r = rq.get();
    Parts s = st.get();
    if (!sameAddress(r, s)) return Optional.empty();
    if (!r.userInfo().username().equals(s.userInfo().username())) return Optional.empty();
    boolean maskedEcho =
        AuthQueryMasker.PASSWORD_MASK.equals(r.userInfo().password()) && hasPassword(s.userInfo());
    boolean sameRaw = r.rawUserInfo().equals(s.rawUserInfo());
    if (!maskedEcho && !sameRaw) return Optional.empty();
    return Optional.of(r.withUserInfo(s.rawUserInfo()));
  }

  /**
   * 두 URL 이 같은 주소(스킴·호스트·실제 포트)인지 — 호스트·포트는 {@link UrlAuthority} 로 읽어 밑줄 든 호스트도 받고, 포트가 없으면 스킴 기본
   * 포트로 본다. {@link UrlAuthority} 로 읽을 수 없는 주소면 예전처럼 {@code 호스트[:포트]} 글자를 대소문자 무시로 비교한다.
   */
  private static boolean sameAddress(Parts r, Parts s) {
    if (!r.prefix().equalsIgnoreCase(s.prefix())) return false;
    Optional<UrlAuthority> ra = authority(r);
    Optional<UrlAuthority> sa = authority(s);
    if (ra.isEmpty() || sa.isEmpty()) return r.hostPort().equalsIgnoreCase(s.hostPort());
    String scheme = r.prefix().substring(0, r.prefix().length() - 3);
    return ra.get().host().equalsIgnoreCase(sa.get().host())
        && ra.get().effectivePort(scheme) == sa.get().effectivePort(scheme);
  }

  /** {@code 스킴://호스트[:포트]} 만으로 호스트·포트를 읽는다 — 사용자 정보·경로의 글자 때문에 URI 해석이 실패하지 않게 뗀 값으로 읽는다. */
  private static Optional<UrlAuthority> authority(Parts p) {
    try {
      return UrlAuthority.of(new URI(p.prefix() + p.hostPort()));
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /** 인코딩된 사용자 정보를 첫 {@code :} 에서 아이디·비밀번호로 나눠 디코딩한다. */
  private static UserInfo decodeUserInfo(String raw) {
    int colon = raw.indexOf(':');
    if (colon < 0) return new UserInfo(decode(raw), null);
    return new UserInfo(decode(raw.substring(0, colon)), decode(raw.substring(colon + 1)));
  }

  /**
   * 사용자 정보 조각을 퍼센트 디코딩한다 — {@code +} 는 URL 경로·사용자 정보에서 글자 그대로라 공백으로 바꾸지 않는다(URLDecoder 는 폼 규칙이라 미리
   * {@code %2B} 로 바꾼다). 잘못된 % 시퀀스면 원문 그대로 쓴다.
   */
  private static String decode(String raw) {
    try {
      return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }
}
