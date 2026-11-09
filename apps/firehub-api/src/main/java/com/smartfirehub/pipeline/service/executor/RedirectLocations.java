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
 */
public final class RedirectLocations {

  /** RFC 3986 부록 B 의 구성요소 분해 정규식 — 스킴·authority·경로·쿼리·프래그먼트. */
  private static final Pattern URI_PARTS =
      Pattern.compile("^(?:([^:/?#]+):)?(?://([^/?#]*))?([^?#]*)(?:\\?([^#]*))?(?:#(.*))?$");

  private RedirectLocations() {}

  /**
   * {@code location} 을 {@code base} 기준으로 풀어 절대 URI 를 만든다.
   *
   * @throws ApiCallException Location 이 URI 문법에 맞지 않거나 풀린 결과가 URI 가 아닐 때 — 다시 보내도 같은 Location 이 오므로
   *     재시도하지 않는다. 문구에는 URL 을 넣지 않는다(쿼리에 비밀이 있을 수 있다).
   */
  public static URI resolve(URI base, String location) {
    try {
      // 참조 자체가 URI 문법에 맞는지 먼저 본다(공백·한글 등) — 아래 문자열 조립은 문법을 검사하지 않는다.
      new URI(location);
      return new URI(resolveString(base.toString(), location));
    } catch (URISyntaxException e) {
      throw new ApiCallException(ApiCallErrorMessages.invalidRedirectLocation(), e);
    }
  }

  /** 문자열 단위 RFC 3986 참조 해석 — 테스트·사례 표 검증용으로 package-private. */
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
