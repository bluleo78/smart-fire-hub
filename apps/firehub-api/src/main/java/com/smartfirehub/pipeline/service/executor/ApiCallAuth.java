package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.AuthConfigValidator;
import java.util.Locale;
import java.util.Map;
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
 * 검사({@link #describeProblem})와 전송(Java 실행기·미리보기)을 모두 한다. Python 실행기({@code _apply_auth})도 같은
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
   * _apply_auth})와 같은 규칙으로 푼 결과(WD-647). 호출 전 검사·Java 실행기·미리보기가 모두 이것만 보고 판정·전송하므로 검사한 값과 보낸 값이
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
  }

  /** 인라인 인증·인증 없음 — 방식은 설정 안의 authType(→ type)이다. {@link #resolve(Map, String)} 참고. */
  public static ResolvedAuth resolve(Map<String, String> auth) {
    return resolve(auth, null);
  }

  /**
   * 스텝이 참조한 저장 연결(없으면 null)과 호출에 쓸 인증 설정을 푼다 — 방식은 연결의 auth_type 컬럼이 있으면 그것, 없으면 설정 안의 authType 이다.
   * 조회({@code ApiConnectionReader#getDecryptedAuthConfig})도 같은 규칙으로 컬럼을 덮어 싣으므로, 실행기가 그 Map 만으로 푼
   * 결과와 같다.
   */
  public static ResolvedAuth resolve(ApiConnectionResponse conn, Map<String, String> auth) {
    return resolve(auth, conn != null ? conn.authType() : null);
  }

  /**
   * 인증 설정을 Python {@code _apply_auth} 와 같은 키·우선순위로 푼다(WD-647).
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
   * @param auth 저장 연결의 복호화 authConfig 또는 인라인 인증. null 이면 인증 없음(방식이 덮여도 비밀은 빈 값)
   * @param authTypeOverride 판정·전송 기준 방식(저장 연결의 auth_type 컬럼). null 이면 설정 안의 방식
   */
  private static ResolvedAuth resolve(Map<String, String> auth, String authTypeOverride) {
    Map<String, String> a = auth != null ? auth : Map.of();
    String authType =
        upper(authTypeOverride != null ? authTypeOverride : firstNonBlank(a, "authType", "type"));
    String placement = upper(firstNonBlank(a, "placement", "location"));
    String headerName = firstNonBlank(a, "headerName", "key_name");
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
    String fixWhere =
        connectionName != null ? "API 연결 화면에서 인증 정보를 저장하세요." : "파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.";
    String owner = connectionName != null ? "API 연결 「" + connectionName + "」의 " : "스텝 인증 설정의 ";
    if (AuthConfigValidator.isSecretValueMissing(auth.authType(), auth.secret())) {
      return owner + SECRET_SUBJECT_BY_AUTH_TYPE.get(auth.authType()) + " 비어 있습니다 — " + fixWhere;
    }
    if (auth.isHeaderApiKey()) {
      String problem = AuthConfigValidator.headerNameProblem(auth.headerName());
      if (problem != null) return owner + "API 키 헤더 이름을 쓸 수 없습니다(" + problem + ") — " + fixWhere;
    }
    return null;
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

  private static String upper(String value) {
    return value != null ? value.toUpperCase(Locale.ROOT) : null;
  }
}
