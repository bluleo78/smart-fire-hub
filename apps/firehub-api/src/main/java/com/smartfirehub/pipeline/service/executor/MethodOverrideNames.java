package com.smartfirehub.pipeline.service.executor;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 메서드 변경(method override) 헤더·쿼리 이름 판정(WD-970).
 *
 * <p>대상 서버(프레임워크 필터)는 GET 요청에 {@code X-HTTP-Method-Override} 헤더나 {@code _method} 쿼리가 있으면 GET 을
 * DELETE 같은 쓰기로 바꿔 처리한다. GET 미리보기는 쓰기 확인 절차 없이 나가므로, 저장된 API 연결·요청에서 온 이런 이름은 부르기 전에 막아야 한다. 이 클래스는
 * 이름만 판정하는 순수 정적 유틸이다(값은 보지 않는다).
 */
public final class MethodOverrideNames {

  /**
   * 헤더 이름을 소문자+ASCII 영문·숫자만 남긴 꼴로 줄였을 때 메서드 변경으로 보는 이름 → 안내에 쓸 표준 이름. 안내에는 받은 원문(대상 서버의 Location·저장
   * 설정)을 넣지 않고 이 표준 이름만 쓴다 — 원문에 끼운 글이 화면·AI 도구 결과로 실리지 않게(WD-970 리뷰).
   */
  private static final Map<String, String> OVERRIDE_HEADERS =
      Map.of(
          "xhttpmethodoverride", "X-HTTP-Method-Override",
          "xhttpmethod", "X-HTTP-Method",
          "xmethodoverride", "X-Method-Override");

  /** 안내에 쓸 쿼리 이름 {@code _method} 의 표준 꼴. */
  private static final String METHOD_QUERY = "_method";

  private MethodOverrideNames() {}

  /** 헤더 이름이 메서드 변경 헤더인지 — 대소문자·구분 문자(-, _, 공백)를 무시한다. null·빈 값은 false. */
  public static boolean isOverrideHeader(String name) {
    return standardHeaderName(name).isPresent();
  }

  /** 메서드 변경 헤더면 그 표준 이름(예: X-HTTP-Method-Override), 아니면 empty. */
  private static Optional<String> standardHeaderName(String name) {
    if (name == null || name.isBlank()) return Optional.empty();
    StringBuilder sb = new StringBuilder();
    for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) sb.append(c);
    }
    return Optional.ofNullable(OVERRIDE_HEADERS.get(sb.toString()));
  }

  /**
   * 쿼리 이름이 메서드 변경 이름인지 — 서버는 퍼센트 인코딩을 풀어 읽으므로 먼저 디코딩한다(UTF-8, + 는 공백, 실패하면 원문). 앞뒤 공백을 떼고 소문자로 본
   * 이름이 {@code _method} 이거나 헤더 판정에 맞으면 true. 흔한 {@code method} 는 막지 않는다.
   */
  static boolean isOverrideQueryName(String rawName) {
    return rawName != null && standardQueryName(normalizeQueryName(rawName)).isPresent();
  }

  /** 쿼리 이름 정규화 — 퍼센트 디코딩({@link ApiCallAuth#decodeName}, 실패하면 원문) 뒤 앞뒤 공백 제거. */
  private static String normalizeQueryName(String rawName) {
    return ApiCallAuth.decodeName(rawName).trim();
  }

  /** 정규화된 쿼리 이름이 {@code _method} 면 {@code _method}, 헤더 판정에 맞으면 그 표준 헤더 이름, 아니면 empty. */
  private static Optional<String> standardQueryName(String name) {
    if (name.toLowerCase(Locale.ROOT).equals(METHOD_QUERY)) return Optional.of(METHOD_QUERY);
    return standardHeaderName(name);
  }

  /**
   * URI 의 원시 쿼리를 {@code &} 와 {@code ;} 로 나눠(서버마다 구분자가 다르다) 첫 위반 이름을 표준 이름({@code _method} 또는 표준 헤더
   * 이름)으로 돌려준다 — 받은 원문은 돌려주지 않는다. 쿼리가 없거나 위반이 없으면 empty.
   */
  public static Optional<String> findOverrideQuery(URI uri) {
    if (uri == null) return Optional.empty();
    String query = uri.getRawQuery();
    if (query == null || query.isEmpty()) return Optional.empty();
    for (String piece : query.split("[&;]")) {
      int eq = piece.indexOf('=');
      String rawName = eq >= 0 ? piece.substring(0, eq) : piece;
      Optional<String> standard = standardQueryName(normalizeQueryName(rawName));
      if (standard.isPresent()) return standard;
    }
    return Optional.empty();
  }

  /** 헤더 이름들 중 첫 메서드 변경 헤더의 표준 이름을 돌려준다 — 저장 설정의 원문은 돌려주지 않는다. */
  public static Optional<String> findOverrideHeader(Iterable<String> names) {
    if (names == null) return Optional.empty();
    for (String n : names) {
      Optional<String> standard = standardHeaderName(n);
      if (standard.isPresent()) return standard;
    }
    return Optional.empty();
  }
}
