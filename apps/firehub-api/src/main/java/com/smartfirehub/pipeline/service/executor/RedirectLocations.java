package com.smartfirehub.pipeline.service.executor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리다이렉트 Location 을 지금 요청 기준 절대 주소로 푼다(WD-676) — RFC 3986 §5.2.2(참조 해석)·§5.2.3(merge)·§5.2.4(점 구간
 * 제거)를 raw 구성요소 그대로 따른다.
 *
 * <p><b>왜 {@link URI#resolve(String)} 를 쓰지 않는가.</b> JDK 의 resolve 는 RFC 2396 기준이라 (1) path 가 빈
 * base({@code https://api.example.com?k=v})에 슬래시 없는 상대 경로({@code items})를 붙이면 {@code
 * https://api.example.comitems} 처럼 호스트에 붙여 버리고, (2) 쿼리만 있는 참조({@code ?page=2})에서 base 경로의 마지막 구간을
 * 지운다. Python 실행기는 RFC 3986 을 따르는 {@code urllib.parse.urljoin} 을 쓰므로 두 실행기가 다른 주소로 갔다. 공용 사례
 * 표({@code api-call-auth-vectors.json} 의 {@code redirectLocation})로 두 쪽이 같은 결과를 내는지 검증한다.
 *
 * <p><b>프래그먼트는 뗀다(WD-686).</b> 프래그먼트는 요청 줄에 실리지 않아 서버 요청에 의미가 없고, 남겨 두면 로그·오류 문구·주소 검사(SSRF)에 섞인다.
 * RFC 9110 §10.2.2 의 「Location 에 프래그먼트가 없으면 원 요청의 것을 물려받는다」도 같은 이유로 따르지 않는다. Python 은 {@code
 * urldefrag} 로 같은 지점에서 뗀다.
 */
public final class RedirectLocations {

  /** RFC 3986 부록 B 의 구성요소 분해 정규식 — 스킴·authority·경로·쿼리·프래그먼트. */
  private static final Pattern URI_PARTS =
      Pattern.compile("^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$");

  private RedirectLocations() {}

  /**
   * {@code location} 을 {@code base} 기준으로 풀어 프래그먼트 없는 절대 URI 를 만든다.
   *
   * @throws ApiCallException Location 이 URI 문법에 맞지 않거나 풀린 결과가 URI 가 아닐 때 — 다시 보내도 같은 Location 이 오므로
   *     재시도하지 않는다. 문구에는 URL 을 넣지 않는다(쿼리에 비밀이 있을 수 있다).
   */
  public static URI resolve(URI base, String location) {
    // 프래그먼트는 문법 검사보다 먼저 뗀다(WD-686) — 어차피 버릴 부분의 문법 오류(「#」 두 번·공백 등)로 실패하면, 같은 Location 을
    // 따라가는 Python(urldefrag)과 결과가 달라진다. 첫 「#」 부터가 프래그먼트다(RFC 3986 부록 B).
    int hash = location.indexOf('#');
    String reference = hash >= 0 ? location.substring(0, hash) : location;
    try {
      // 참조 자체가 URI 문법에 맞는지 먼저 본다(공백·한글 등) — 아래 문자열 조립은 문법을 검사하지 않는다.
      new URI(reference);
      return new URI(resolveString(base.toString(), reference));
    } catch (URISyntaxException e) {
      throw new ApiCallException(ApiCallErrorMessages.invalidRedirectLocation(), e);
    }
  }

  /**
   * 리다이렉트 홉이 메서드·본문을 그대로 유지하는지(WD-686) — 리다이렉트 메서드·본문 규칙은 여기 한 곳에 적는다(RFC 9110·브라우저 관례).
   *
   * <ul>
   *   <li>307·308: 메서드를 바꾸지 말라는 상태라 처음 요청과 같은 출처면 메서드·본문(Content-Type 포함)을 유지한다. 다른 출처면 비밀이 든 본문을
   *       보내지 않으려고 본문 없는 GET 으로 바꾼다(WD-676).
   *   <li>301·302·303: 출처와 상관없이 본문 없는 GET.
   *   <li>한 번 본문을 버리면 그 뒤 홉(같은 출처 307·처음 출처로 돌아옴 포함)도 본문 없는 GET 이고, 본문 헤더({@code BODY_HEADERS})도
   *       뺀다.
   * </ul>
   *
   * Python {@code _redirect_keeps_method_and_body} 와 같고, 공용 사례 표({@code redirectMethod})로 검증한다. 그
   * 밖의 3xx(300·304·305 …)는 Java 실행기가 본문 없는 GET 으로 따라가고(이 메서드가 false) Python 은 일반 응답으로 다룬다 — 원래 있던
   * 차이로, 바꾸지 않았다.
   */
  public static boolean keepsMethodAndBody(int status, boolean sameOrigin) {
    return (status == 307 || status == 308) && sameOrigin;
  }

  /** 문자열 단위 RFC 3986 참조 해석(§5.2.2, 프래그먼트 포함) — 프래그먼트는 호출부 {@link #resolve} 가 뗀다. */
  static String resolveString(String base, String reference) {
    Parts b = Parts.parse(base);
    Parts r = Parts.parse(reference);
    String scheme;
    String authority;
    String path;
    String query;
    if (r.scheme != null) {
      scheme = r.scheme;
      authority = r.authority;
      path = removeDotSegments(r.path);
      query = r.query;
    } else {
      if (r.authority != null) {
        authority = r.authority;
        path = removeDotSegments(r.path);
        query = r.query;
      } else {
        if (r.path.isEmpty()) {
          // 빈 경로 참조(「?page=2」「#f」「」)는 base 경로를 그대로 둔다 — 쿼리가 없으면 base 쿼리도 둔다.
          path = b.path;
          query = r.query != null ? r.query : b.query;
        } else {
          if (r.path.startsWith("/")) {
            path = removeDotSegments(r.path);
          } else {
            path = removeDotSegments(merge(b, r.path));
          }
          query = r.query;
        }
        authority = b.authority;
      }
      scheme = b.scheme;
    }
    StringBuilder sb = new StringBuilder();
    if (scheme != null) sb.append(scheme).append(':');
    if (authority != null) sb.append("//").append(authority);
    sb.append(path);
    if (query != null) sb.append('?').append(query);
    // RFC 3986 그대로 참조의 프래그먼트를 붙인다. 리다이렉트용으로는 resolve() 가 미리 떼어 여기 오지 않는다(WD-686).
    if (r.fragment != null) sb.append('#').append(r.fragment);
    return sb.toString();
  }

  /** §5.2.3 merge — authority 가 있고 경로가 빈 base 는 「/」 를 앞에 붙인다(JDK 가 빠뜨리는 부분). */
  private static String merge(Parts base, String refPath) {
    if (base.authority != null && base.path.isEmpty()) {
      return "/" + refPath;
    }
    int slash = base.path.lastIndexOf('/');
    return slash >= 0 ? base.path.substring(0, slash + 1) + refPath : refPath;
  }

  /** §5.2.4 점 구간 제거 — 「.」「..」 구간을 지운다. 루트 위로 올라가는 「..」 는 버린다. */
  static String removeDotSegments(String path) {
    if (path.isEmpty()) return path;
    Deque<String> out = new ArrayDeque<>();
    String in = path;
    while (!in.isEmpty()) {
      if (in.startsWith("../")) {
        in = in.substring(3);
      } else if (in.startsWith("./")) {
        in = in.substring(2);
      } else if (in.startsWith("/./")) {
        in = in.substring(2);
      } else if (in.equals("/.")) {
        in = "/";
      } else if (in.startsWith("/../")) {
        in = in.substring(3);
        if (!out.isEmpty()) out.removeLast();
      } else if (in.equals("/..")) {
        in = "/";
        if (!out.isEmpty()) out.removeLast();
      } else if (in.equals(".") || in.equals("..")) {
        in = "";
      } else {
        // 첫 구간(앞의 「/」 포함)을 출력으로 옮긴다.
        int start = in.startsWith("/") ? 1 : 0;
        int next = in.indexOf('/', start);
        String segment = next >= 0 ? in.substring(0, next) : in;
        out.addLast(segment);
        in = next >= 0 ? in.substring(next) : "";
      }
    }
    return String.join("", out);
  }

  /** 분해된 URI 참조 — 정의되지 않은 구성요소는 null(경로는 항상 문자열). */
  private record Parts(
      String scheme, String authority, String path, String query, String fragment) {

    static Parts parse(String s) {
      Matcher m = URI_PARTS.matcher(s);
      if (!m.matches()) {
        // 부록 B 정규식은 모든 문자열에 맞는다 — 방어용.
        return new Parts(null, null, s, null, null);
      }
      return new Parts(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
    }
  }
}
