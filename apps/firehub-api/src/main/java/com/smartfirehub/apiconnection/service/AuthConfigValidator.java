package com.smartfirehub.apiconnection.service;

import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * API 연결 인증 정보(authConfig) 개별 필드를 검사·정리한다(WD-392).
 *
 * <p>예전에는 authType 과 필수 비밀 존재만 봤다 — 헤더 이름에 띄어쓰기·콜론이 있거나 비어 있어도, 키 값에 줄바꿈이 있어도 저장됐다. 그런 연결은 HTTP
 * 클라이언트가 요청을 만들다 예외를 던져 「대상 서버에 연결하지 못했습니다」로 안내돼, 사용자는 주소·네트워크를 의심했다. 저장할 때 막고, 이미 저장된 값으로 테스트할 때도
 * 같은 사유를 보여 주려고 한 곳에 모은다.
 */
public final class AuthConfigValidator {

  private AuthConfigValidator() {}

  /** HTTP 헤더 이름에 쓸 수 있는 문자(RFC 9110 token) — 띄어쓰기·콜론·한글 등은 쓸 수 없다. */
  private static final Pattern HTTP_TOKEN = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");

  /**
   * 인증 헤더 이름으로 쓸 수 없는 예약 헤더 — 요청 자체를 바꾸거나(Host·길이·전송 방식) 클라이언트가 직접 관리하는 헤더다. 예: Host 로 두면 Host 헤더를
   * 키 값으로 덮어써 대상이 403 을 준다. Authorization 은 「Authorization: apikey …」 식 API 가 있어 막지 않는다.
   */
  private static final Set<String> RESERVED_HEADER_NAMES =
      Set.of(
          "host",
          "content-length",
          "transfer-encoding",
          "connection",
          "keep-alive",
          "proxy-connection",
          "upgrade",
          "te",
          "trailer",
          "expect");

  /** 토큰 칸에 접두어까지 붙여 넣은 값 — 그대로 두면 「Authorization: Bearer Bearer …」 로 나간다. */
  private static final Pattern BEARER_PREFIX = Pattern.compile("(?i)^bearer\\s+");

  /**
   * 저장·저장 전 테스트에 쓰는 검사. 잘못된 필드는 한국어 사유와 함께 {@link ApiConnectionException}(400)으로 거절한다.
   *
   * @param authType 검증을 마친 인증 방식(API_KEY·BEARER)
   * @param config 가린 값을 저장 원문으로 채운 authConfig. null 이면 null
   * @return 정리한 새 Map — BEARER 토큰 앞의 「Bearer 」 를 뗀다(요청 Map 은 불변일 수 있어 건드리지 않는다)
   */
  public static Map<String, String> validateAndNormalize(
      String authType, Map<String, String> config) {
    if (config == null) return null;
    Map<String, String> normalized = new HashMap<>(config);
    if ("BEARER".equals(authType)) {
      String token = config.get("token");
      if (token != null) {
        // 앞뒤 공백까지 함께 정리한다 — 공백도 줄바꿈처럼 헤더 값을 깨거나 인증을 실패시킨다.
        normalized.put("token", BEARER_PREFIX.matcher(token.strip()).replaceFirst(""));
      }
    }
    String problem = describeProblem(authType, normalized);
    if (problem != null) {
      throw new ApiConnectionException(problem);
    }
    return normalized;
  }

  /**
   * 이미 저장된 authConfig 로 호출하기 전에 쓸 수 없는 값인지 본다 — 연결 테스트가 네트워크 오류처럼 안내하지 않고 실제 원인을 보이게 한다.
   *
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String describeProblem(String authType, Map<String, String> config) {
    if (config == null) return null;
    if ("API_KEY".equals(authType)) {
      String placement = config.getOrDefault("placement", "header");
      if ("query".equals(placement)) {
        String paramName = config.get("paramName");
        if (paramName == null || paramName.isBlank()) {
          // 비어 있으면 호출 URL 에 키가 아예 붙지 않아 대상이 인증 실패로만 응답한다.
          return "쿼리 파라미터 이름을 입력하세요";
        }
      } else if ("header".equals(placement)) {
        String problem = headerNameProblem(config.getOrDefault("headerName", "X-API-Key"));
        if (problem != null) return problem;
      } else {
        return "키 위치(placement)는 header 또는 query 만 쓸 수 있습니다: " + placement;
      }
      return lineBreakProblem(config.get("apiKey"), "API 키");
    }
    if ("BEARER".equals(authType)) {
      return lineBreakProblem(config.get("token"), "Bearer 토큰");
    }
    return null;
  }

  private static String headerNameProblem(String headerName) {
    if (headerName == null || headerName.isBlank()) {
      return "헤더 이름을 입력하세요";
    }
    if (!HTTP_TOKEN.matcher(headerName).matches()) {
      return "헤더 이름이 올바르지 않습니다 — 영문·숫자와 - _ . 등만 쓸 수 있고 띄어쓰기·콜론은 쓸 수 없습니다: " + headerName;
    }
    if (RESERVED_HEADER_NAMES.contains(headerName.toLowerCase(Locale.ROOT))) {
      return "헤더 이름으로 " + headerName + " 은(는) 쓸 수 없습니다 — 요청이 쓰는 예약 헤더입니다";
    }
    return null;
  }

  /** 비밀 값은 문구에 싣지 않는다 — 필드 이름만 알린다. */
  private static String lineBreakProblem(String value, String label) {
    if (value != null && value.chars().anyMatch(c -> c == '\r' || c == '\n')) {
      return label + "에 줄바꿈을 넣을 수 없습니다 — 한 줄로 입력하세요";
    }
    return null;
  }
}
