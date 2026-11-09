package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.AuthConfigValidator;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * API_CALL 미리보기·실행이 함께 쓰는 인증 규칙(WD-639).
 *
 * <p>미리보기({@code ApiCallPreviewService})와 실행({@code PipelineAsyncRunner} → {@link
 * ApiCallExecutor}·Python 실행기)이 같은 연결로 같은 요청을 보내야 한다. 예전에는 경로마다 따로 규칙을 들고 있어 어긋났다 —
 *
 * <ul>
 *   <li>헤더 이름이 없는 API_KEY 헤더 연결: 연결 테스트는 기본 헤더(X-API-Key)로 키를 보내는데 미리보기·Java 실행기는 헤더를 아예 붙이지 않아,
 *       테스트는 통과하고 실행은 401 이 났다.
 *   <li>비밀 필드가 빈 연결·인라인 인증: 연결 테스트·상태 점검(WD-623)은 호출하지 않고 안내하는데 미리보기·실행은 빈 인증 헤더로 호출해 대상의 401/403 만
 *       남겼다.
 * </ul>
 *
 * 두 규칙을 여기 한 곳에 두고 모든 API_CALL 경로가 부른다.
 *
 * <p><b>검사하는 값 = 보내는 값(WD-647).</b> 인증 설정은 {@link #resolve} 로 한 번 풀어 {@link ResolvedAuth} 하나로
 * 검사({@link #describeProblem})와 전송(Java 실행기·미리보기)을 모두 한다. Python 실행기({@code _resolve_auth})도 같은
 * 키·우선순위·정리 규칙을 따른다.
 */
public final class ApiCallAuth {

  private ApiCallAuth() {}

  /** 안내 문구의 비밀 필드 이름(주격 조사 포함) — 관리 화면 칸 이름과 같다. */
  private static final Map<String, String> SECRET_SUBJECT_BY_AUTH_TYPE =
      Map.of("API_KEY", "API 키가", "BEARER", "Bearer 토큰이");

  /** Python 실행기의 쿼리 인증 기본 파라미터 이름. */
  static final String DEFAULT_API_KEY_PARAM = "api_key";

  /**
   * 인증 설정 한 건을 Python 실행기({@code apps/firehub-executor/app/services/api_call_executor.py} 의 {@code
   * _resolve_auth})와 같은 규칙으로 푼 결과(WD-647). 호출 전 검사·Java 실행기·미리보기가 모두 이것만 보고 판정·전송하므로 검사한 값과 보낸 값이
   * 어긋나지 않는다.
   *
   * @param authType 대문자 인증 방식(API_KEY·BEARER 등). 없으면 null
   * @param placement 대문자 위치(HEADER·QUERY). 없으면 HEADER
   * @param headerName API_KEY 헤더 이름(빈 값이면 기본 헤더)
   * @param paramName API_KEY 쿼리 파라미터 이름(빈 값이면 api_key)
   * @param secret 보낼 비밀 — BEARER 는 정리한 토큰(「Bearer 」 접두어·앞뒤 공백 뗌), API_KEY 는 원문. 어느 키에도 없으면 빈 문자열
   */
  public record ResolvedAuth(
      String authType, String placement, String headerName, String paramName, String secret) {

    /** API_KEY 를 쿼리 파라미터로 붙이는 설정인지. */
    public boolean isQueryApiKey() {
      return "API_KEY".equals(authType) && "QUERY".equals(placement);
    }

    /** API_KEY 를 헤더로 붙이는 설정인지 — Python 처럼 QUERY 가 아니면 모두 헤더다. */
    public boolean isHeaderApiKey() {
      return "API_KEY".equals(authType) && !"QUERY".equals(placement);
    }

    /** Authorization: Bearer 헤더를 붙이는 설정인지. */
    public boolean isBearer() {
      return "BEARER".equals(authType);
    }

    /** 실제로 요청에 실리는 비밀(헤더·쿼리·Bearer) — 응답 본문·오류 문구에서 가릴 값이다. 인증을 붙이지 않는 설정이면 null(가릴 것 없음). */
    public String sentSecret() {
      return isBearer() || "API_KEY".equals(authType) ? secret : null;
    }

    /**
     * 인증 헤더를 붙인다 — Java 실행기·미리보기가 같은 규칙을 따로 들고 있지 않도록 한 곳에 둔다. API_KEY 헤더는 정한 이름(비면 기본 헤더, WD-639)에
     * 키 원문, BEARER 는 정리한 토큰이다(WD-647). 쿼리 인증·인증 없음이면 아무것도 붙이지 않는다.
     *
     * @param header 헤더 이름·값을 받아 요청에 붙이는 함수(예: {@code spec::header})
     */
    public void applyHeaders(BiConsumer<String, String> header) {
      if (isHeaderApiKey()) {
        header.accept(headerName, secret);
      } else if (isBearer()) {
        header.accept("Authorization", "Bearer " + secret);
      }
    }

    /**
     * 인증이 실리는 헤더 이름(API_KEY 헤더 이름 또는 Authorization). 헤더로 인증하지 않는 설정이면 null.
     *
     * <p>다른 출처로 리다이렉트할 때(WD-657) 인증 헤더를 떼면서, 사용자 헤더에 같은 이름으로 넣은 값까지 함께 떼려고 쓴다 — 이름만 같으면 같은 비밀일 수
     * 있다.
     */
    public String authHeaderName() {
      if (isHeaderApiKey()) return headerName;
      if (isBearer()) return "Authorization";
      return null;
    }

    /**
     * 사용자 헤더가 자격 증명을 실을 수 있는 헤더인지(WD-657) — 다른 출처 홉에서 뺄 헤더다. 표준 자격 증명 헤더({@link
     * #CREDENTIAL_HEADERS})이거나 인증 헤더와 이름이 같으면(대소문자 무시) 그렇다. 쿼리 인증이어도 사용자가 Authorization 을 직접 넣었을 수
     * 있어 방식과 상관없이 본다. Python {@code _CREDENTIAL_HEADERS} 와 같은 규칙이다.
     */
    public boolean isCredentialHeader(String name) {
      if (name == null) return false;
      if (CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return true;
      String authHeader = authHeaderName();
      return authHeader != null && authHeader.equalsIgnoreCase(name);
    }

    /**
     * 호출용·표시용 URL 을 만든다 — 쿼리 인증 키를 붙이는 규칙을 실행기·미리보기·연결 테스트·리다이렉트 복원이 함께 쓰도록 한 곳에 둔다(WD-657).
     *
     * <p>{@code extraParams} 가 null 이고 쿼리 인증도 아니면 URL 을 손대지 않고 그대로 돌려준다(연결 테스트 — 빌더로 다시 만들면 경로의
     * 한글·공백에서 build(true) 가 실패해 지금 되는 연결이 깨진다). 그 밖에는 정적 파라미터 뒤에 쿼리 인증 키를 넣어 {@link
     * AuthQueryMasker#build} 로 만든다 — 키는 공용 규칙(QueryApiKeyEncoding)으로 한 번 인코딩되고 표시용 URL 에서는 가려진다.
     *
     * @param url 쿼리를 붙일 기준 URL
     * @param extraParams 붙일 정적·페이지네이션 파라미터(순서 유지). null 이면 없음
     * @throws AuthQueryMasker.InvalidRequestUrlException 기준 URL 을 만들 수 없을 때(문구에 원문 없음)
     */
    public AuthQueryMasker.AuthQueryUrls buildUrls(String url, Map<String, String> extraParams) {
      if (extraParams == null && !isQueryApiKey()) {
        return new AuthQueryMasker.AuthQueryUrls(url, url);
      }
      Map<String, String> params = new LinkedHashMap<>();
      if (extraParams != null) params.putAll(extraParams);
      String authParamName = null;
      if (isQueryApiKey()) {
        params.put(paramName, secret);
        authParamName = paramName;
      }
      return AuthQueryMasker.build(url, params, authParamName, sentSecret());
    }
  }

  /** 다른 출처 홉에서 인증 방식과 상관없이 빼는 표준 자격 증명 헤더(소문자, WD-657). */
  private static final Set<String> CREDENTIAL_HEADERS =
      Set.of("authorization", "proxy-authorization", "cookie");

  /** 인라인 인증·인증 없음 — 방식은 설정 안의 authType(→ type)이다. {@link #resolveWithAuthType} 참고. */
  public static ResolvedAuth resolve(Map<String, String> auth) {
    return resolveWithAuthType(null, auth);
  }

  /**
   * 스텝이 참조한 저장 연결(없으면 null)과 호출에 쓸 인증 설정을 푼다 — 방식은 연결의 auth_type 컬럼이 있으면 그것, 없으면 설정 안의 authType 이다.
   * 조회({@code ApiConnectionReader#getDecryptedAuthConfig})도 같은 규칙으로 컬럼을 덮어 싣으므로, 실행기가 그 Map 만으로 푼
   * 결과와 같다.
   */
  public static ResolvedAuth resolve(ApiConnectionResponse conn, Map<String, String> auth) {
    return resolveWithAuthType(conn != null ? conn.authType() : null, auth);
  }

  /**
   * 인증 설정을 Python {@code _resolve_auth} 와 같은 키·우선순위로 푼다(WD-647).
   *
   * <ul>
   *   <li>방식: authTypeOverride(저장 연결 컬럼) → authType → type (대문자로)
   *   <li>위치: placement → location → header (대문자로)
   *   <li>헤더 이름: headerName → key_name → X-API-Key
   *   <li>쿼리 이름: paramName → key_name → api_key
   *   <li>비밀: BEARER 는 token → value, API_KEY 는 apiKey → key_value → value
   * </ul>
   *
   * <p>비밀은 Python {@code or} 와 똑같이 null·빈 문자열만 건너뛰어 고른 뒤, BEARER 면 저장 검사와 같은 정리({@link
   * AuthConfigValidator#normalizeBearerToken})를 거친 값을 돌려준다 — 빈 비밀 검사도 이 값으로 하므로 「검사는 통과했는데 Bearer
   * Bearer abc 가 나가는」 일이 없다. 공백 값({@code " "})은 건너뛰지 않고 골라 빈 비밀 검사가 막게 둔다(건너뛰면 Python 과 다른 키를 고르게
   * 된다). 이름·방식·위치는 공백뿐이면 없는 것으로 보고 다음 키·기본값을 쓴다 — Python 도 같은 공백 판정으로 맞췄다. 저장 데이터를 표준 키로 옮기지 않고 읽는
   * 쪽이 대체 키를 함께 받는 이유는, AI 에이전트 create_pipeline 등이 {@code {"authType":"BEARER","value":"…"}} 로 저장한
   * 파이프라인이 이미 Python 실행기로 돌고 있어서다.
   *
   * <p>저장 전 연결 테스트처럼 연결 행은 없고 방식만 따로 정해진 경우(WD-657)에도 이것을 직접 부른다 — 방식을 저장 연결의 auth_type 컬럼처럼 본다.
   *
   * @param authTypeOverride 판정·전송 기준 방식(저장 연결의 auth_type 컬럼·저장 전 요청의 방식). null 이면 설정 안의 방식
   * @param auth 저장 연결의 복호화 authConfig 또는 인라인 인증. null 이면 인증 없음(방식이 덮여도 비밀은 빈 값)
   */
  public static ResolvedAuth resolveWithAuthType(
      String authTypeOverride, Map<String, String> auth) {
    Map<String, String> a = auth != null ? auth : Map.of();
    String authType =
        upper(authTypeOverride != null ? authTypeOverride : firstNonBlank(a, "authType", "type"));
    String placement = upper(firstNonBlank(a, "placement", "location"));
    // 헤더 이름은 앞뒤 공백을 떼고 쓴다(WD-657) — 인라인 key_name 끝 공백·검증 규칙 전에 저장된 「X-Key 」 때문에 예전엔 돌던 파이프라인이 헤더
    // 이름 검사(WD-647)로 실행 전에 실패하지 않게 한다. Python _resolve_auth 도 같은 공백 판정으로 뗀다.
    String headerName = strip(firstNonBlank(a, "headerName", "key_name"));
    String paramName = firstNonBlank(a, "paramName", "key_name");
    String secret;
    if ("BEARER".equals(authType)) {
      String raw = firstNonEmpty(a, "token", "value");
      secret = raw != null ? AuthConfigValidator.normalizeBearerToken(raw) : null;
    } else {
      secret = firstNonEmpty(a, "apiKey", "key_value", "value");
    }
    return new ResolvedAuth(
        authType,
        placement != null ? placement : "HEADER",
        headerName != null ? headerName : AuthConfigValidator.DEFAULT_API_KEY_HEADER,
        paramName != null ? paramName : DEFAULT_API_KEY_PARAM,
        secret != null ? secret : "");
  }

  /**
   * 모든 API_CALL 경로(미리보기·실행)가 부르는 호출 전 검사 진입점 — 저장 연결과 설정을 {@link #resolve(ApiConnectionResponse,
   * Map)} 로 풀어 {@link #describeProblem(ResolvedAuth, String)} 를 부른다.
   *
   * @param conn 스텝이 참조한 저장 연결. 인라인 인증·인증 없음이면 null
   * @param auth 호출에 쓸 인증 설정 — 저장 연결의 복호화 authConfig 또는 인라인 인증(null 가능)
   * @return 안내 문구 또는 문제가 없으면 null
   */
  public static String describeCallProblem(ApiConnectionResponse conn, Map<String, String> auth) {
    return describeProblem(resolve(conn, auth), conn != null ? conn.name() : null);
  }

  /**
   * 푼 인증 설정으로 호출하면 안 되는지 본다 — 문제가 있으면 호출하지 않고 이 문구로 실패시킨다.
   *
   * <ol>
   *   <li>비밀이 비었는지(WD-639) — 가장 먼저 본다. 연결 테스트·상태 점검(WD-623)과 같은 규칙이다.
   *   <li>API_KEY 헤더 이름을 쓸 수 있는지(WD-647) — 저장 연결과 같은 규칙({@link
   *       AuthConfigValidator#headerNameProblem}). 인라인 key_name 은 저장 검사를 거치지 않아 Host 같은 예약 헤더·공백 든
   *       이름이 그대로 나갈 수 있었다.
   * </ol>
   *
   * <p>운영자(실행 기록·미리보기 결과를 보는 사람)용 문구다 — 관리 화면 입력 칸 안내(「… 입력하세요 (authConfig.token)」)가 아니라 어디서 고치는지를
   * 알린다. 방식이 없거나 모르는 값이면 인증 헤더를 붙이지 않는 설정이라 검사하지 않는다.
   *
   * @param auth {@link #resolve} 결과 — 실제로 보낼 값
   * @param connectionName 저장 연결 이름. 인라인 인증이면 null
   * @return 안내 문구 또는 문제가 없으면 null
   */
  public static String describeProblem(ResolvedAuth auth, String connectionName) {
    // 원인 위치(WD-657): 인증 값은 저장 연결이 있으면 연결에서만, 없으면 스텝 설정(인라인 인증)에서만 온다 — 문구도 그 위치를 짚는다. 빈 비밀은
    // 「저장」, 헤더 이름은 「고치기」로 할 일을 나눈다(예전엔 헤더 이름 문제에도 「인증 정보를 저장하세요」였다).
    boolean saved = connectionName != null;
    String owner = saved ? "API 연결 「" + connectionName + "」의 " : "스텝 인증 설정의 ";
    String fixStep = "파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.";
    if (AuthConfigValidator.isSecretValueMissing(auth.authType(), auth.secret())) {
      String fixWhere = saved ? "API 연결 화면에서 인증 정보를 저장하세요." : fixStep;
      return owner + SECRET_SUBJECT_BY_AUTH_TYPE.get(auth.authType()) + " 비어 있습니다 — " + fixWhere;
    }
    if (auth.isHeaderApiKey()) {
      String problem = AuthConfigValidator.headerNameProblem(auth.headerName());
      if (problem != null) {
        String fixWhere = saved ? "API 연결 화면에서 이 연결의 헤더 이름을 고치세요." : fixStep;
        return owner + "API 키 헤더 이름을 쓸 수 없습니다(" + problem + ") — " + fixWhere;
      }
    }
    return null;
  }

  /**
   * 리다이렉트 대상({@code target})에 처음 요청({@code origin})의 인증을 실어도 되는지 — 인증 기준으로 같은 출처인지 본다(WD-657). 다른
   * 출처면 인증을 뗀다 — 예전에는 다른 호스트로 3xx 해도 API 키·Bearer 토큰을 그대로 보내, 리다이렉트를 거는 쪽(또는 그 너머)이 비밀을 받았다.
   *
   * <ul>
   *   <li>호스트는 대소문자를 무시하고 같아야 한다. 호스트를 알 수 없으면 다른 출처로 본다.
   *   <li>스킴이 같으면 포트도 같아야 한다 — 포트가 없으면 기본 포트(http 80·https 443)로 본다.
   *   <li>http→https 승격은 같은 출처로 본다 — 두 포트가 모두 스킴 기본값(80→443)이거나 같은 포트를 명시했을 때. 공공 API 의 흔한
   *       http→https 리다이렉트에서 serviceKey 가 따라가던 동작(WD-182)을 지킨다. 같은 호스트로 암호화가 강해질 뿐이라 비밀이 새지 않는다.
   *   <li>https→http 강등·그 밖의 호스트·포트 변경은 다른 출처다.
   * </ul>
   *
   * Python {@code _same_auth_origin} 과 같은 규칙이다. 처음 요청과만 비교하므로 방향이 있다(origin→target).
   */
  public static boolean isSameAuthOrigin(URI origin, URI target) {
    if (origin == null || target == null || origin.getHost() == null || target.getHost() == null) {
      return false;
    }
    if (!origin.getHost().equalsIgnoreCase(target.getHost())) return false;
    String from = upper(origin.getScheme());
    String to = upper(target.getScheme());
    if (from == null || to == null) return false;
    if (from.equals(to)) return effectivePort(origin) == effectivePort(target);
    if ("HTTP".equals(from) && "HTTPS".equals(to)) {
      boolean bothDefault = effectivePort(origin) == 80 && effectivePort(target) == 443;
      boolean sameExplicit = origin.getPort() != -1 && origin.getPort() == target.getPort();
      return bothDefault || sameExplicit;
    }
    return false;
  }

  /** 포트가 없으면 스킴의 기본 포트(http 80·https 443). 모르는 스킴이면 -1. */
  private static int effectivePort(URI uri) {
    if (uri.getPort() != -1) return uri.getPort();
    String scheme = upper(uri.getScheme());
    if ("HTTP".equals(scheme)) return 80;
    if ("HTTPS".equals(scheme)) return 443;
    return -1;
  }

  /**
   * URL 쿼리에서 이름이 {@code paramName} 인 파라미터를 모두 뺀다(WD-657) — 다른 출처 리다이렉트의 Location 이 보낸 쿼리 인증 키를 되울려
   * 담아 오면(예: 다른 호스트로 넘기면서 쿼리를 그대로 옮기는 리다이렉트) 그 키를 다른 출처로 보내지 않으려고 쓴다. 이름은 퍼센트 디코딩해 비교하고, 나머지
   * 쿼리·프래그먼트는 받은 그대로 둔다. Python {@code _strip_query_param} 과 같은 규칙이다.
   */
  public static String stripQueryParam(String url, String paramName) {
    if (url == null || paramName == null) return url;
    int hash = url.indexOf('#');
    String fragment = hash >= 0 ? url.substring(hash) : "";
    String base = hash >= 0 ? url.substring(0, hash) : url;
    int q = base.indexOf('?');
    if (q < 0) return url;
    List<String> kept = new ArrayList<>();
    for (String piece : base.substring(q + 1).split("&", -1)) {
      int eq = piece.indexOf('=');
      String rawName = eq >= 0 ? piece.substring(0, eq) : piece;
      if (!paramName.equals(decodeName(rawName))) kept.add(piece);
    }
    String query = String.join("&", kept);
    return base.substring(0, q) + (query.isEmpty() ? "" : "?" + query) + fragment;
  }

  /** URL 쿼리에 이름이 {@code paramName} 인 파라미터가 있는지(이름은 퍼센트 디코딩해 비교, WD-657). */
  public static boolean hasQueryParam(String url, String paramName) {
    return url != null && paramName != null && !stripQueryParam(url, paramName).equals(url);
  }

  /** 쿼리 파라미터 이름을 디코딩한다. 잘못된 % 시퀀스면 원문 그대로 비교한다. */
  private static String decodeName(String raw) {
    try {
      return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      return raw;
    }
  }

  /** 앞 키부터 null·빈 문자열이 아닌 첫 값(Python {@code a or b}). 없으면 null. */
  private static String firstNonEmpty(Map<String, String> auth, String... keys) {
    for (String key : keys) {
      String value = auth.get(key);
      if (value != null && !value.isEmpty()) return value;
    }
    return null;
  }

  /** 앞 키부터 공백뿐이 아닌 첫 값 — 이름·방식·위치용(Python {@code _first_non_blank} 와 같은 공백 판정). 없으면 null. */
  private static String firstNonBlank(Map<String, String> auth, String... keys) {
    for (String key : keys) {
      String value = auth.get(key);
      if (value != null && !value.isBlank()) return value;
    }
    return null;
  }

  /** 앞뒤 공백(Java Character.isWhitespace 기준)을 뗀다. null 이면 null. */
  private static String strip(String value) {
    return value != null ? value.strip() : null;
  }

  private static String upper(String value) {
    return value != null ? value.toUpperCase(Locale.ROOT) : null;
  }
}
