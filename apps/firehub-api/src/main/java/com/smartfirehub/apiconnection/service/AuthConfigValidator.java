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

  /**
   * 토큰 칸에 접두어까지 붙여 넣은 값 — 그대로 두면 「Authorization: Bearer Bearer …」 로 나간다.
   *
   * <p>접두어 뒤가 공백이거나 <b>값의 끝</b>이어도 떼고, 접두어가 여러 번 겹쳐 있어도(「Bearer Bearer abc」) 모두 뗀다(WD-623). 앞뒤 공백을
   * strip 한 뒤 맞추므로 「Bearer 」 는 「Bearer」 가 되는데, 예전 패턴(뒤에 공백 필수·한 번만)은 이를 놓쳐 「Bearer」 가 토큰으로 저장됐다.
   * 접두어만 있으면 빈 토큰이 돼야 비밀 필드 검사가 거절한다. 「bearerXYZ」 처럼 접두어 뒤에 바로 글자가 이어지는 값은 토큰 자체이므로 건드리지 않는다.
   *
   * <p>값 전체가 글자 그대로 「bearer」(대소문자 무관)인 토큰도 빈 토큰으로 본다 — 실제 토큰일 가능성보다 접두어만 붙여 넣은 실수일 가능성이 훨씬 커서, 저장해
   * 「Bearer bearer」 로 호출하기보다 「Bearer 토큰을 입력하세요」 로 안내하는 쪽을 택했다.
   */
  private static final Pattern BEARER_PREFIX = Pattern.compile("(?i)^(bearer(\\s+|$))+");

  /**
   * 지원하는 인증 방식과 그 방식에 반드시 있어야 하는 비밀 필드(API_KEY → apiKey, BEARER → token). 인증 헤더·쿼리 조립이 읽는 키와 같다.
   * 저장·저장 전 테스트 검사와 저장된 값 검사({@link #describeProblem})가 같은 규칙을 쓰도록 여기 한 곳에 둔다(WD-623).
   */
  static final Map<String, String> REQUIRED_SECRET_BY_AUTH_TYPE =
      Map.of("API_KEY", "apiKey", "BEARER", "token");

  /** 비밀 필드가 비었을 때의 안내(WD-588) — 줄바꿈 안내와 같은 이름(API 키·Bearer 토큰)을 쓴다. */
  private static final Map<String, String> MISSING_SECRET_MESSAGE_BY_AUTH_TYPE =
      Map.of(
          "API_KEY", "API 키를 입력하세요 (authConfig.apiKey)",
          "BEARER", "Bearer 토큰을 입력하세요 (authConfig.token)");

  /**
   * Bearer 토큰 값을 정리한다 — 앞뒤 공백을 떼고 앞에 붙은 「Bearer」 접두어(겹친 것까지)를 뗀다. 저장 검사와 비밀 필드 빈 값 판정이 같은 정리를 거치도록
   * 이 메서드 하나만 쓴다(WD-623).
   *
   * @return 정리한 토큰. null 이면 null
   */
  static String normalizeBearerToken(String token) {
    if (token == null) return null;
    // 앞뒤 공백까지 함께 정리한다 — 공백도 줄바꿈처럼 헤더 값을 깨거나 인증을 실패시킨다.
    return BEARER_PREFIX.matcher(token.strip()).replaceFirst("");
  }

  /**
   * 인증 방식의 비밀 필드 값을 정리한 형태로 꺼낸다(BEARER 면 {@link #normalizeBearerToken} 적용). 지원하지 않는 방식이거나 config 가
   * null 이면 null.
   */
  static String normalizedSecret(String authType, Map<String, String> config) {
    String key = REQUIRED_SECRET_BY_AUTH_TYPE.get(authType);
    if (key == null || config == null) return null;
    String value = config.get(key);
    return "BEARER".equals(authType) ? normalizeBearerToken(value) : value;
  }

  /** API_KEY + placement=header 에서 헤더 이름을 비워 둘 때 쓰는 기본 헤더 — 검사·호출·비교가 같은 기본값을 보도록 한 곳에 둔다. */
  public static final String DEFAULT_API_KEY_HEADER = "X-API-Key";

  /**
   * 가린 값 판정용으로 비밀 값을 정리한다(WD-639) — 앞뒤 공백을 떼고, {@code token} 필드면 「Bearer」 접두어(겹친 것까지)도 뗀다.
   *
   * <p>예전에는 원값 그대로 {@code ****} 로 시작하는지만 봐서 「Bearer ****oken」·「 ****cret」 처럼 되돌아온 가린 값을 놓쳤다. 놓친 값은
   * 뒤이은 정리(「Bearer 」 떼기·strip)를 거쳐 {@code ****oken} 이 된 채 저장돼 실제 토큰을 가린 문자열로 덮었다. 저장 정리와 같은 규칙으로
   * 판정해야 정리 뒤에 가린 값이 되는 입력을 모두 잡는다.
   *
   * @param key authConfig 필드 이름
   * @return 정리한 값. null 이면 null
   */
  static String maskComparable(String key, String value) {
    if (value == null) return null;
    return "token".equals(key) ? normalizeBearerToken(value) : value.strip();
  }

  /**
   * 조회 화면이 돌려준 가린 값({@code ****…})인지 — 앞뒤 공백·「Bearer 」 접두어가 붙어 와도 가린 값으로 본다(WD-639). 수정·생성·저장 전
   * 테스트의 가린 값 처리와 방식 변경 검사가 모두 이 판정 하나를 쓴다.
   */
  static boolean isMaskedSecret(String key, String value) {
    return AuthQueryMasker.isMasked(maskComparable(key, value));
  }

  /** 인증 방식의 비밀 필드 값이 가린 값인지({@link #isMaskedSecret}). 지원하지 않는 방식이거나 config 가 null 이면 false. */
  static boolean isSecretMasked(String authType, Map<String, String> config) {
    String key = authType != null ? REQUIRED_SECRET_BY_AUTH_TYPE.get(authType) : null;
    return key != null && config != null && isMaskedSecret(key, config.get(key));
  }

  /**
   * 인증 정보가 「실제로」 바뀌었는지 비교하기 위한 사본을 만든다(감사 credentialsChanged, WD-639).
   *
   * <p>양쪽(요청을 정리한 값·저장값)을 같은 규칙으로 맞춘 뒤 비교해야 뜻이 같은 저장을 변경으로 오판하지 않는다 —
   *
   * <ul>
   *   <li>{@code authType} 키는 뺀다(저장값 조회가 덧붙이고, 화면은 authConfig 에도 싣는다. 방식은 컬럼으로 따로 비교한다).
   *   <li>BEARER 토큰은 저장 정리와 같이 「Bearer 」·앞뒤 공백을 뗀다 — 정리 규칙 전에 저장된 옛 토큰에 가린 값을 되돌려 보내면 저장값만 정리된 형태로
   *       다시 쓰일 뿐 사용자가 비밀을 바꾼 것이 아니다.
   *   <li>API_KEY 의 placement(header)·headerName(X-API-Key) 기본값을 채운다 — 화면은 기본값을 명시해 보내지만 AI 챗·API 로
   *       만든 연결은 빠져 있을 수 있다. 호출은 같은 기본값을 쓰므로 같은 설정이다.
   * </ul>
   *
   * @param config 정리 전·후 어느 쪽이든 된다. null 이면 빈 Map 으로 본다
   */
  static Map<String, String> comparableConfig(String authType, Map<String, String> config) {
    Map<String, String> copy = new HashMap<>(config == null ? Map.of() : config);
    copy.remove("authType");
    if ("BEARER".equals(authType)) {
      copy.computeIfPresent("token", (k, token) -> normalizeBearerToken(token));
    } else if ("API_KEY".equals(authType)) {
      copy.putIfAbsent("placement", "header");
      if ("header".equals(copy.get("placement"))) {
        copy.putIfAbsent("headerName", DEFAULT_API_KEY_HEADER);
      }
    }
    return copy;
  }

  /**
   * 인증 방식의 비밀 필드가 정리한 뒤 비어 있는지(없음·공백·접두어만) 본다. 비밀 필드가 정해지지 않은 방식이면 false.
   *
   * @param config 정리 전·후 어느 쪽이든 된다 — 판정은 정리한 값으로 한다
   */
  public static boolean isSecretMissing(String authType, Map<String, String> config) {
    // Map.of 는 null 키 조회에 NPE 를 던지므로 방식이 없으면 먼저 돌려보낸다 — API_CALL 인라인 인증은 방식이 없을 수 있다(WD-639).
    if (authType == null || !REQUIRED_SECRET_BY_AUTH_TYPE.containsKey(authType)) return false;
    String value = normalizedSecret(authType, config);
    return value == null || value.isBlank();
  }

  /**
   * 저장·저장 전 테스트에 쓰는 검사. 잘못된 필드는 한국어 사유와 함께 {@link ApiConnectionException}(400)으로 거절한다.
   *
   * @param authType 검증을 마친 인증 방식(API_KEY·BEARER)
   * @param config 가린 값을 저장 원문으로 채운 authConfig. null 이면 비밀 필드 없음으로 거절한다
   * @return 정리한 새 Map — BEARER 토큰 앞의 「Bearer 」 를 뗀다(요청 Map 은 불변일 수 있어 건드리지 않는다)
   */
  public static Map<String, String> validateAndNormalize(
      String authType, Map<String, String> config) {
    Map<String, String> normalized = config != null ? new HashMap<>(config) : null;
    if (normalized != null && "BEARER".equals(authType)) {
      normalized.computeIfPresent("token", (k, token) -> normalizeBearerToken(token));
    }
    // describeProblem 은 비밀 필드 빈 값을 가장 먼저 본다 — config 가 null 이어도 「… 입력하세요」 로 거절한다(WD-623).
    String problem = describeProblem(authType, normalized);
    if (problem != null) {
      throw new ApiConnectionException(problem);
    }
    return normalized;
  }

  /**
   * 이미 저장된 authConfig 로 호출하기 전에 쓸 수 없는 값인지 본다 — 연결 테스트가 네트워크 오류처럼 안내하지 않고 실제 원인을 보이게 한다.
   *
   * <p>비밀 필드가 비었는지(정리한 뒤 기준)를 <b>가장 먼저</b> 본다(WD-623) — 헤더 이름·줄바꿈 등 다른 사유보다 앞선다. 저장할 때는 이 규칙으로
   * 거절하지만, 그 전에 빈 비밀로 저장된 연결은 연결 테스트·10분 주기 상태 점검에서 빈 인증으로 대상을 부르고 대상의 401/403 만 보였다. 이제 호출하지 않고
   * 「Bearer 토큰을 입력하세요」 처럼 무엇을 채워야 하는지 보인다.
   *
   * @param config 정리한 authConfig. null 이면 비밀 필드가 없는 것으로 본다
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String describeProblem(String authType, Map<String, String> config) {
    if (isSecretMissing(authType, config)) {
      return MISSING_SECRET_MESSAGE_BY_AUTH_TYPE.get(authType);
    }
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
        String problem =
            headerNameProblem(config.getOrDefault("headerName", DEFAULT_API_KEY_HEADER));
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
