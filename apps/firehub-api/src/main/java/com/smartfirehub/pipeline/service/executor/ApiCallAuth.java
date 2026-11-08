package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.AuthConfigValidator;
import java.util.Map;

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
 */
public final class ApiCallAuth {

  private ApiCallAuth() {}

  /** 안내 문구의 비밀 필드 이름(주격 조사 포함) — 관리 화면 칸 이름과 같다. */
  private static final Map<String, String> SECRET_SUBJECT_BY_AUTH_TYPE =
      Map.of("API_KEY", "API 키가", "BEARER", "Bearer 토큰이");

  /**
   * API_KEY + placement=header 의 헤더 이름. 비어 있으면 연결 테스트({@code
   * ApiConnectionService#buildAuthHeaders})·Python 실행기와 같은 기본 헤더({@link
   * AuthConfigValidator#DEFAULT_API_KEY_HEADER})를 쓴다.
   */
  public static String apiKeyHeaderName(Map<String, String> auth) {
    return auth.getOrDefault("headerName", AuthConfigValidator.DEFAULT_API_KEY_HEADER);
  }

  /**
   * 모든 API_CALL 경로(미리보기·실행)가 부르는 빈 비밀 검사 진입점. 저장 연결이면 연결의 auth_type 컬럼과 이름으로, 아니면 인라인 인증의 authType
   * 으로 {@link #describeMissingSecret(String, Map, String)} 을 부른다. 저장 연결의 authConfig JSON 에 다른
   * authType 키가 들어 있어도(조회가 putIfAbsent 로 덧붙여 JSON 쪽이 이긴다) 판정은 컬럼 기준이다 — 연결 화면·연결 테스트가 보는 방식과 같게.
   *
   * @param conn 스텝이 참조한 저장 연결. 인라인 인증·인증 없음이면 null
   * @param auth 호출에 쓸 인증 설정 — 저장 연결의 복호화 authConfig 또는 인라인 인증(null 가능)
   * @return 안내 문구 또는 문제가 없으면 null
   */
  public static String describeMissingSecret(ApiConnectionResponse conn, Map<String, String> auth) {
    if (conn != null) return describeMissingSecret(conn.authType(), auth, conn.name());
    return auth != null ? describeMissingSecret(auth.get("authType"), auth, null) : null;
  }

  /**
   * 호출에 쓸 인증 설정의 비밀 필드가 비었는지(정리한 뒤 기준) 본다 — 비었으면 호출하지 않고 이 문구로 실패시킨다.
   *
   * <p>운영자(실행 기록·미리보기 결과를 보는 사람)용 문구다 — 관리 화면 입력 칸 안내(「… 입력하세요 (authConfig.token)」)가 아니라 어디서 고치는지를
   * 알린다. 헤더 이름 등 다른 필드는 보지 않는다(이미 저장된 파이프라인을 새 사유로 막지 않는다). 방식이 없거나 모르는 값이면 인증 헤더를 붙이지 않는 설정이라 검사하지
   * 않는다.
   *
   * @param authType 판정 기준 인증 방식 — 저장 연결이면 연결의 auth_type 컬럼(authConfig JSON 안의 authType 키가 아니다), 인라인
   *     인증이면 스텝 설정의 authType
   * @param auth 호출에 쓸 인증 설정(null 이면 비밀 없음으로 본다)
   * @param connectionName 저장 연결 이름. 인라인 인증이면 null
   * @return 안내 문구 또는 문제가 없으면 null
   */
  public static String describeMissingSecret(
      String authType, Map<String, String> auth, String connectionName) {
    if (!AuthConfigValidator.isSecretMissing(authType, auth)) return null;
    String subject = SECRET_SUBJECT_BY_AUTH_TYPE.get(authType);
    if (connectionName != null) {
      return "API 연결 「" + connectionName + "」의 " + subject + " 비어 있습니다 — API 연결 화면에서 인증 정보를 저장하세요.";
    }
    return "스텝 인증 설정의 " + subject + " 비어 있습니다 — 파이프라인 편집에서 이 스텝의 인증 설정을 고치세요.";
  }
}
