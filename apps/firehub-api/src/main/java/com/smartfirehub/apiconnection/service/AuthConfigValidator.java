package com.smartfirehub.apiconnection.service;

import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.pipeline.service.executor.ApiCallAuth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
   *
   * <p>값의 끝은 {@code \z}(입력의 진짜 끝)로 본다(WD-657). {@code $} 는 끝 줄바꿈 문자(U+0085·U+2028 등) 앞에서도 맞아
   * 「Bearer」 + U+0085 에서 접두어를 떼 버렸는데, Python 실행기의 {@code $} 는 그러지 않아 두 실행기가 다른 토큰을 보냈다. Python 은
   * {@code \Z} 로 맞춘다.
   */
  private static final Pattern BEARER_PREFIX = Pattern.compile("(?i)^(bearer(\\s+|\\z))+");

  /** BASIC 인증의 아이디 필드 이름(WD-681) — 비밀이 아니라 응답에서 가리지 않는다. */
  public static final String BASIC_USERNAME_KEY = "username";

  /**
   * BASIC 인증의 비밀번호 필드 이름(WD-681) — 비밀 필드이고, 응답에서는 끝 글자 없이 고정 {@code ****} 로 가린다({@code
   * ApiConnectionReader.maskSecretValue}).
   */
  public static final String BASIC_PASSWORD_KEY = "password";

  /**
   * 지원하는 인증 방식과 그 방식에 반드시 있어야 하는 비밀 필드(API_KEY → apiKey, BEARER → token, BASIC → password). 인증
   * 헤더·쿼리 조립이 읽는 키와 같다. 저장·저장 전 테스트 검사와 저장된 값 검사({@link #describeProblem})가 같은 규칙을 쓰도록 여기 한 곳에
   * 둔다(WD-623). BASIC(WD-681)은 비밀번호가 비밀 필드이고, 아이디({@link #BASIC_USERNAME_KEY})는 비밀이 아니지만 따로 필수로 본다.
   */
  static final Map<String, String> REQUIRED_SECRET_BY_AUTH_TYPE =
      Map.of("API_KEY", "apiKey", "BEARER", "token", "BASIC", BASIC_PASSWORD_KEY);

  /** BASIC 아이디가 비었을 때의 안내(WD-681) — 비밀번호 안내와 같은 모양이다. */
  public static final String MISSING_USERNAME_MESSAGE = "아이디를 입력하세요 (authConfig.username)";

  /**
   * BASIC 아이디에 콜론이 있을 때의 안내(WD-681) — Basic 인증은 「아이디:비밀번호」 를 첫 콜론에서 나누므로(RFC 7617) 아이디의 콜론 뒤가 비밀번호로
   * 읽혀 인증이 깨진다.
   */
  static final String USERNAME_COLON_MESSAGE =
      "아이디에 콜론(:)을 넣을 수 없습니다 — Basic 인증 아이디에는 콜론을 쓸 수 없습니다";

  /** 비밀 필드가 비었을 때의 안내(WD-588) — 줄바꿈 안내와 같은 이름(API 키·Bearer 토큰)을 쓴다. */
  private static final Map<String, String> MISSING_SECRET_MESSAGE_BY_AUTH_TYPE =
      Map.of(
          "API_KEY", "API 키를 입력하세요 (authConfig.apiKey)",
          "BEARER", "Bearer 토큰을 입력하세요 (authConfig.token)",
          "BASIC", "비밀번호를 입력하세요 (authConfig.password)");

  /**
   * Bearer 토큰 값을 정리한다 — 앞뒤 공백을 떼고 앞에 붙은 「Bearer」 접두어(겹친 것까지)를 뗀다. 저장 검사와 비밀 필드 빈 값 판정이 같은 정리를 거치도록
   * 이 메서드 하나만 쓴다(WD-623).
   *
   * @return 정리한 토큰. null 이면 null
   */
  public static String normalizeBearerToken(String token) {
    if (token == null) return null;
    // 앞뒤 공백까지 함께 정리한다 — 공백도 줄바꿈처럼 헤더 값을 깨거나 인증을 실패시킨다.
    return BEARER_PREFIX.matcher(token.strip()).replaceFirst("");
  }

  /**
   * 추가 인증 헤더 키의 접두어(WD-753) — authConfig 의 {@code extraHeader:<헤더 이름>} 키가 그 이름으로 함께 보낼 비밀 헤더다. 네이버
   * API HUB 처럼 헤더 둘(키 ID·키)이 필요한 API 를 연결 하나로 인증하려고 둔다. authConfig 는 암호화된 평평한 Map 이라 접두어 키로 두면
   * 저장·암호화·가림· Python 전달 구조를 바꾸지 않고 기존 키 단위 규칙을 그대로 탄다. 콜론은 헤더 이름(HTTP 토큰)에 쓸 수 없어 접두어와 이름의 경계가 깨지지
   * 않는다.
   */
  public static final String EXTRA_HEADER_PREFIX = "extraHeader:";

  /** 연결 하나에 둘 수 있는 추가 인증 헤더 수 상한(WD-753) — 화면·요청이 지나치게 커지지 않게. */
  public static final int MAX_EXTRA_HEADERS = 10;

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
    // 추가 인증 헤더 키는 저장과 같이 정리한 키로 맞춘다(WD-753 리뷰) — 정리 전에 저장된 「extraHeader: X-Id」 를 같은 값으로 다시 저장하면
    // 키 모양만 바뀔 뿐 보내는 헤더는 같다.
    normalizeExtraHeaderKeys(copy);
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
    return isSecretValueMissing(
        authType, config != null ? config.get(REQUIRED_SECRET_BY_AUTH_TYPE.get(authType)) : null);
  }

  /**
   * 이미 골라 낸 비밀 값 하나가 정리한 뒤 비어 있는지 본다(WD-647). {@link #isSecretMissing} 은 표준 필드(apiKey·token)만 보지만,
   * API_CALL 경로({@code ApiCallAuth})는 Python 실행기와 같은 대체 키(value·key_value)에서 값을 고른 뒤 같은 정리·빈 값 규칙을
   * 써야 해서 값 단위로 나눴다. 저장·저장 전 테스트 검사는 여전히 표준 필드만 받는다.
   *
   * @return 비밀 필드가 정해진 방식이고 값이 없음·공백·(BEARER) 접두어만이면 true. 방식이 없거나 모르면 false
   */
  public static boolean isSecretValueMissing(String authType, String value) {
    if (authType == null || !REQUIRED_SECRET_BY_AUTH_TYPE.containsKey(authType)) return false;
    String normalized = "BEARER".equals(authType) ? normalizeBearerToken(value) : value;
    return normalized == null || normalized.isBlank();
  }

  /**
   * 실제로 보낼 값({@link ApiCallAuth#resolve} 로 푼 값)으로 연결 테스트 전 검사를 한다(WD-657). 저장된 연결의 「테스트」·10분 주기 상태
   * 점검이 파이프라인 실행과 같은 값을 보내므로, 검사도 그 값으로 해야 「테스트는 거절, 실행은 통과」 같은 어긋남이 없다(예: 대문자 {@code
   * QUERY}·paramName 없음·key_name 대체 키). 문구는 연결 화면용 {@link #describeProblem} 과 같다.
   *
   * <ol>
   *   <li>비밀이 비었는지(정리한 뒤) — 가장 먼저
   *   <li>API_KEY 헤더 이름을 쓸 수 있는지 — 헤더로 보낼 때만
   *   <li>비밀에 줄바꿈이 있는지
   *   <li>추가 인증 헤더({@link #extraHeadersProblem}, WD-753) — 메인 인증 검사를 통과한 뒤
   * </ol>
   *
   * <p>파이프라인 실행·미리보기의 호출 전 검사({@link ApiCallAuth#describeProblem})는 1·2 만 본다 — 줄바꿈 검사는 연결 테스트에만 있다.
   * 실행에 넣으면 지금 돌던 파이프라인의 동작이 바뀌어 일부러 맞추지 않았다.
   *
   * <p>원래 authConfig 의 추가 헤더 키 겹침({@link #rawExtraHeaderNameCollision}) 검사도 더한다(WD-753 리뷰). 푼
   * 값({@code auth.extraHeaders()})에서는 공백만 다른 두 키가 이미 한 이름으로 합쳐져 한쪽 값이 사라졌으므로, 원래 Map 이 있는 저장된 연결
   * 테스트·상태 점검은 그 Map 을 함께 넘긴다. 겹침은 메인 인증 검사를 통과한 뒤, 다른 추가 헤더 검사보다 먼저 본다(저장 검사 {@link
   * #describeProblem} 과 같은 순서).
   *
   * @param auth 실행과 같은 규칙으로 푼 인증 설정. 모르는 방식이면 문제없음
   * @param rawConfig {@code auth} 를 푼 원래 authConfig. null 이면 겹침 검사를 하지 않는다
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String describeSentValueProblem(
      ApiCallAuth.ResolvedAuth auth, Map<String, String> rawConfig) {
    String problem = describeSentMainProblem(auth);
    if (problem != null) return problem;
    String collision = rawExtraHeaderNameCollision(rawConfig);
    if (collision != null) return collision;
    // 추가 인증 헤더(WD-753)는 메인 인증 검사를 통과한 뒤 본다 — 메인 인증 헤더 이름(쿼리 인증·인증 없음이면 null)과 겹치는지도 본다.
    return extraHeadersProblem(auth.extraHeaders(), auth.authHeaderName());
  }

  /** {@link #describeSentValueProblem} 의 메인 인증(방식별 비밀·헤더 이름·줄바꿈) 검사 — WD-753 전 규칙 그대로다. */
  private static String describeSentMainProblem(ApiCallAuth.ResolvedAuth auth) {
    String authType = auth.authType();
    String secret = auth.secret();
    // BASIC 은 아이디를 비밀번호보다 먼저 본다(WD-681) — 화면 칸 순서(아이디 → 비밀번호)와 같다.
    if (auth.isBasic()) {
      String problem = basicUsernameProblem(auth.username());
      if (problem != null) return problem;
    }
    if (isSecretValueMissing(authType, secret)) {
      return MISSING_SECRET_MESSAGE_BY_AUTH_TYPE.get(authType);
    }
    if (auth.isHeaderApiKey()) {
      String problem = headerNameProblem(auth.headerName());
      if (problem != null) return problem;
    }
    if ("API_KEY".equals(authType)) return lineBreakProblem(secret, "API 키");
    if (auth.isBearer()) return lineBreakProblem(secret, "Bearer 토큰");
    if (auth.isBasic()) return lineBreakProblem(secret, "비밀번호");
    return null;
  }

  /**
   * 저장·저장 전 테스트에 쓰는 검사. 잘못된 필드는 한국어 사유와 함께 {@link ApiConnectionException}(400)으로 거절한다.
   *
   * @param authType 검증을 마친 인증 방식(API_KEY·BEARER·BASIC)
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
    // 추가 인증 헤더 키를 정리한 이름으로 다시 쓴다(WD-753 리뷰) — 겹침 검사를 통과한 뒤라 정리해도 두 키가 하나로 합쳐지지 않는다.
    // 「extraHeader: X-Id」 로 저장하면 화면은 앞뒤 공백을 뗀 이름으로 가린 값을 되돌려 보내 저장 키와 맞지 않았다 — 저장 키를 화면·호출이
    // 쓰는 이름과 같게 둔다.
    normalizeExtraHeaderKeys(normalized);
    return normalized;
  }

  /**
   * authConfig 의 추가 인증 헤더 키를 {@link #normalizeExtraHeaderKey} 로 바꿔 쓴다(WD-753 리뷰). 값은 손대지 않는다. 같은 정리
   * 키로 모이는 키가 둘 이상이면(겹침 검사 전 호출) 원래 키 정렬 순서의 마지막 값이 남는다 — 저장 경로는 겹침을 먼저 거절하므로 그런 일이 없다.
   *
   * <p>저장 정리({@link #validateAndNormalize})와 가린 값 되돌리기 저장값 조회({@code
   * ApiConnectionService.resolveMaskedSecrets})가 같은 정리를 쓰도록 공개한다.
   *
   * @param config 바꿔 쓸 수정 가능한 Map. null 이면 아무것도 하지 않는다
   */
  public static void normalizeExtraHeaderKeys(Map<String, String> config) {
    if (config == null) return;
    TreeMap<String, String> raw = rawExtraHeaderEntries(config);
    raw.keySet().forEach(config::remove);
    raw.forEach((key, value) -> config.put(normalizeExtraHeaderKey(key), value));
  }

  /**
   * 추가 인증 헤더 키를 정리한다(WD-753 리뷰) — 접두어 뒤 이름의 앞뒤 공백을 뗀 {@code extraHeader:<이름>}. 추가 헤더 키가 아니면 그대로
   * 돌려준다. 저장({@link #validateAndNormalize})·가린 값 되돌리기({@code
   * ApiConnectionService.resolveMaskedSecrets})·변경 비교({@link #comparableConfig})가 같은 키 정리를 쓴다 — 호출이
   * 보내는 이름({@link #extraHeaders})과도 같다.
   *
   * @param key authConfig 키(null 이면 null)
   */
  public static String normalizeExtraHeaderKey(String key) {
    if (key == null || !key.startsWith(EXTRA_HEADER_PREFIX)) return key;
    return EXTRA_HEADER_PREFIX + key.substring(EXTRA_HEADER_PREFIX.length()).strip();
  }

  /**
   * authConfig 에서 추가 인증 헤더 키({@link #EXTRA_HEADER_PREFIX} 로 시작)만 원래 키→값으로 골라 키 정렬 순서로 담는다(WD-753
   * 리뷰). 추가 헤더 모으기({@link #extraHeaders})·원래 키 겹침 검사({@link #rawExtraHeaderNameCollision})·키
   * 정리·저장값에서 추가 헤더만 고르기({@code ApiConnectionService})가 접두어 거르기를 이 한 곳에서 한다. TreeMap 은 null 키를 받지 않아
   * null 키는 건너뛴다.
   *
   * @param config authConfig(null 이면 빈 맵)
   * @return 원래 키→값(키 정렬 순서, 수정 가능한 새 맵)
   */
  public static TreeMap<String, String> rawExtraHeaderEntries(Map<String, String> config) {
    TreeMap<String, String> sorted = new TreeMap<>();
    if (config == null) return sorted;
    config.forEach(
        (key, value) -> {
          if (key != null && key.startsWith(EXTRA_HEADER_PREFIX)) sorted.put(key, value);
        });
    return sorted;
  }

  /**
   * 이미 저장된 authConfig 로 호출하기 전에 쓸 수 없는 값인지 본다 — 연결 테스트가 네트워크 오류처럼 안내하지 않고 실제 원인을 보이게 한다.
   *
   * <p>비밀 필드가 비었는지(정리한 뒤 기준)를 <b>가장 먼저</b> 본다(WD-623) — 헤더 이름·줄바꿈 등 다른 사유보다 앞선다. 저장할 때는 이 규칙으로
   * 거절하지만, 그 전에 빈 비밀로 저장된 연결은 연결 테스트·10분 주기 상태 점검에서 빈 인증으로 대상을 부르고 대상의 401/403 만 보였다. 이제 호출하지 않고
   * 「Bearer 토큰을 입력하세요」 처럼 무엇을 채워야 하는지 보인다.
   *
   * <p>메인 인증 검사를 통과하면 추가 인증 헤더({@link #extraHeadersProblem}, WD-753)를 본다 — 인증 방식과 상관없이 붙는 헤더라 방식이
   * 없어도 본다.
   *
   * @param config 정리한 authConfig. null 이면 비밀 필드가 없는 것으로 본다
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String describeProblem(String authType, Map<String, String> config) {
    String problem = describeMainProblem(authType, config);
    if (problem != null) return problem;
    // 추가 인증 헤더(WD-753)는 메인 인증 검사를 통과한 뒤 본다. 원래 키가 공백만 달라 모으면 합쳐지는 겹침을 먼저 본다 — 모은 맵에서는 이미
    // 한쪽 값이 사라져 보이지 않는다. 메인 헤더 이름은 호출이 실제로 붙이는 이름과 같게 전송 규칙({@link
    // ApiCallAuth#resolveWithAuthType})으로
    // 고른다(WD-753 리뷰) — 검사와 전송이 따로 규칙을 들면(기본 헤더·key_name 대체 키·공백 정리) 어긋난다.
    String collision = rawExtraHeaderNameCollision(config);
    if (collision != null) return collision;
    return extraHeadersProblem(
        extraHeaders(config), ApiCallAuth.resolveWithAuthType(authType, config).authHeaderName());
  }

  /**
   * 원래 키 기준 추가 헤더 이름 겹침(WD-753 리뷰) — {@code "extraHeader: X-A"} 와 {@code "extraHeader:X-A"} 처럼 앞뒤
   * 공백(·대소문자)만 다른 키는 {@link #extraHeaders} 가 한 이름으로 합쳐 한쪽 값을 말없이 버린다. 저장 검사는 원래 authConfig 를 보므로
   * 여기서 겹침으로 거절한다. 빈 이름은 건너뛴다 — 「추가 헤더 이름을 입력하세요」({@link #extraHeadersProblem})가 더 정확한 안내다.
   *
   * <p>API_CALL 호출 전 검사({@code ApiCallAuth.describeCallProblem}·미리보기)와 저장된 연결 테스트({@link
   * #describeSentValueProblem(ApiCallAuth.ResolvedAuth, Map)})도 원래 Map 이 있는 곳에서 같은 겹침을 보도록
   * 공개한다(WD-753 리뷰) — 인라인 인증·검사 전에 저장된 값은 저장 검사를 거치지 않는다.
   *
   * @return 겹침 문구(정리한 이름, 값 없음) 또는 겹침이 없으면 null
   */
  public static String rawExtraHeaderNameCollision(Map<String, String> config) {
    // 원래 키 정렬 순서로 보아 어느 Map 구현이든 같은 이름을 문구에 싣는다.
    Set<String> seen = new HashSet<>();
    for (String key : rawExtraHeaderEntries(config).keySet()) {
      String name = key.substring(EXTRA_HEADER_PREFIX.length()).strip();
      if (name.isEmpty()) continue;
      if (!seen.add(name.toLowerCase(Locale.ROOT))) return "추가 헤더 이름이 겹칩니다: " + name;
    }
    return null;
  }

  /** {@link #describeProblem} 의 메인 인증(방식별 비밀·헤더 이름·위치·줄바꿈) 검사 — WD-753 전 규칙 그대로다. */
  private static String describeMainProblem(String authType, Map<String, String> config) {
    // BASIC 은 아이디(비밀 아님)부터 본다(WD-681) — 화면 칸 순서와 같고, 아이디가 없으면 비밀번호가 있어도 인증할 수 없다.
    if ("BASIC".equals(authType)) {
      String problem = basicUsernameProblem(config != null ? config.get(BASIC_USERNAME_KEY) : null);
      if (problem != null) return problem;
    }
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
    if ("BASIC".equals(authType)) {
      return lineBreakProblem(config.get(BASIC_PASSWORD_KEY), "비밀번호");
    }
    return null;
  }

  /**
   * BASIC 아이디를 쓸 수 있는지 본다(WD-681) — 비었는지(공백만이어도 빈 값), 콜론·줄바꿈이 있는지. 저장 검사({@link #describeProblem})와
   * 보내는 값 검사({@link #describeSentValueProblem})가 같은 규칙을 쓴다. 아이디는 앞뒤 공백을 떼지 않는다 — 공백도 아이디의 일부일 수 있다.
   *
   * <p>API_CALL 호출 전 검사({@code ApiCallAuth.describeProblem}, 인라인 BASIC 포함)도 같은 규칙을 쓰도록 공개한다(WD-681
   * 리뷰).
   *
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String basicUsernameProblem(String username) {
    if (username == null || username.isBlank()) return MISSING_USERNAME_MESSAGE;
    if (username.indexOf(':') >= 0) return USERNAME_COLON_MESSAGE;
    return lineBreakProblem(username, "아이디");
  }

  /**
   * API_KEY 헤더 이름을 쓸 수 있는지 본다 — HTTP 토큰 형식·예약 헤더(Host 등). 저장 검사와 API_CALL 호출 전 검사({@code
   * ApiCallAuth}, WD-647)가 같은 규칙을 쓰도록 공개한다.
   *
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String headerNameProblem(String headerName) {
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

  /**
   * authConfig 에서 추가 인증 헤더({@link #EXTRA_HEADER_PREFIX} 키)를 이름→값으로 모은다(WD-753).
   *
   * <p>이름은 접두어 뒤를 앞뒤 공백을 떼고 쓰고(인증 헤더 이름과 같은 정리, WD-657), 값은 원문 그대로다(비밀 값은 손대지 않는다 — 공백만인 값은 검사가
   * 거절한다). 이름이 빈 키({@code "extraHeader:"})도 빈 이름으로 넣는다 — 조용히 버리면 저장한 값이 말없이 사라지므로 {@link
   * #extraHeadersProblem} 이 「추가 헤더 이름을 입력하세요」 로 거절하게 둔다. 순서는 원래 키의 정렬 순서(TreeMap)라 Map 구현과 상관없이 늘
   * 같다 — Python 실행기도 같은 순서로 보낸다(공용 사례 표 {@code extraHeaders}).
   *
   * @param config 저장 authConfig 또는 인라인 인증. null 이면 빈 맵
   * @return 이름→값(순서 유지, 수정 가능한 새 맵)
   */
  public static LinkedHashMap<String, String> extraHeaders(Map<String, String> config) {
    LinkedHashMap<String, String> out = new LinkedHashMap<>();
    rawExtraHeaderEntries(config)
        .forEach(
            (key, value) -> out.put(key.substring(EXTRA_HEADER_PREFIX.length()).strip(), value));
    return out;
  }

  /**
   * 추가 인증 헤더를 보낼 수 있는지 본다(WD-753) — 저장 검사({@link #describeProblem})·보내는 값 검사({@link
   * #describeSentValueProblem})·API_CALL 호출 전 검사({@code ApiCallAuth.describeProblem})가 같은 규칙을 쓴다.
   *
   * <ol>
   *   <li>개수 {@link #MAX_EXTRA_HEADERS} 초과
   *   <li>헤더마다 차례로: 이름이 빔 → 이름 규칙({@link #headerNameProblem}, 앞에 「추가 헤더 」) → 추가 헤더끼리 이름 겹침(대소문자 무시)
   *       → 메인 인증 헤더와 이름 같음(대소문자 무시) → 값이 빔(공백만 포함) → 값에 줄바꿈
   * </ol>
   *
   * <p>이름이 겹치면 같은 헤더가 두 번 나가 대상이 어느 값을 쓸지 알 수 없다 — 그래서 거절한다. 문구에는 헤더 이름만 싣고 값(비밀)은 싣지 않는다.
   *
   * @param extraHeaders {@link #extraHeaders} 결과(이름→값). null 이면 문제없음
   * @param mainAuthHeaderName 메인 인증이 붙이는 헤더 이름. 헤더로 인증하지 않으면 null
   * @return 문제 사유(한국어) 또는 문제가 없으면 null
   */
  public static String extraHeadersProblem(
      Map<String, String> extraHeaders, String mainAuthHeaderName) {
    if (extraHeaders == null || extraHeaders.isEmpty()) return null;
    if (extraHeaders.size() > MAX_EXTRA_HEADERS) {
      return "추가 헤더는 최대 " + MAX_EXTRA_HEADERS + "개까지 넣을 수 있습니다";
    }
    Set<String> seen = new HashSet<>();
    for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
      String name = e.getKey();
      String value = e.getValue();
      if (name == null || name.isBlank()) return "추가 헤더 이름을 입력하세요";
      String nameProblem = headerNameProblem(name);
      if (nameProblem != null) return "추가 헤더 " + nameProblem;
      if (!seen.add(name.toLowerCase(Locale.ROOT))) return "추가 헤더 이름이 겹칩니다: " + name;
      if (mainAuthHeaderName != null && mainAuthHeaderName.equalsIgnoreCase(name)) {
        return "추가 헤더 " + name + " 은(는) 인증 헤더와 이름이 같습니다 — 인증 칸에 이미 있는 헤더입니다";
      }
      if (value == null || value.isBlank()) return "추가 헤더 " + name + " 값을 입력하세요";
      String lineBreak = lineBreakProblem(value, "추가 헤더 " + name + " 값");
      if (lineBreak != null) return lineBreak;
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
