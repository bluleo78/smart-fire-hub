package com.smartfirehub.pipeline.service;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.pipeline.dto.ApiCallPreviewRequest;
import com.smartfirehub.pipeline.dto.ApiCallPreviewResponse;
import com.smartfirehub.pipeline.service.executor.ApiCallAuth;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import com.smartfirehub.pipeline.service.executor.RedirectFollower;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import com.smartfirehub.pipeline.service.executor.SsrfSafeHttpClientFactory;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@lombok.extern.slf4j.Slf4j
public class ApiCallPreviewService {

  private static final int DEFAULT_TIMEOUT_MS = 30_000;
  private static final int MAX_RAW_JSON_BYTES = 10 * 1024; // 10 KB
  private static final int MAX_PREVIEW_ROWS = 5;

  private final SsrfProtectionService ssrfProtectionService;
  private final JsonResponseParser jsonResponseParser;
  private final ApiConnectionService apiConnectionService;

  /**
   * 자동 리다이렉트를 끈 클라이언트 — 따라가기는 {@link RedirectFollower} 가 홉마다 검사하며 한다(WD-693). 설정이 요청마다 같아(응답 크기 제한
   * 없음) 서비스가 만들어질 때 한 번만 만든다. withoutAutoRedirect 가 주입받은 공유 빌더를 복제해 쓴다.
   *
   * <p>커넥터는 {@link SsrfSafeHttpClientFactory} 의 HttpClient 다(WD-613) — 첫 요청과 모든 리다이렉트 홉에서 접속할 IP 를
   * 다시 검사해, 사전 검사(validateUrl) 뒤 DNS 재바인딩으로 내부 주소가 나와도 접속하지 않는다. 막히면 RedirectFollower 가 사전 검사와 같은
   * 문구의 SsrfException 으로 낸다.
   */
  private final WebClient client;

  public ApiCallPreviewService(
      SsrfProtectionService ssrfProtectionService,
      JsonResponseParser jsonResponseParser,
      ApiConnectionService apiConnectionService,
      WebClient.Builder webClientBuilder,
      SsrfSafeHttpClientFactory ssrfSafeHttpClientFactory) {
    this.ssrfProtectionService = ssrfProtectionService;
    this.jsonResponseParser = jsonResponseParser;
    this.apiConnectionService = apiConnectionService;
    this.client =
        RedirectFollower.withoutAutoRedirect(webClientBuilder, ssrfSafeHttpClientFactory).build();
  }

  public ApiCallPreviewResponse preview(ApiCallPreviewRequest request) {
    String resolvedRequestUrl = null;
    // 오류 문구 분류용(WD-150) — 타임아웃 문구의 초. 전송 오류의 호스트는 RedirectFollower 가 실패한 홉의 것으로 문구에 넣어 둔다(WD-693).
    int timeoutMs = request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;
    // 오류 문구에서 가릴 비밀(WD-177) — 인증 설정을 읽은 뒤에 채운다. BASIC 은 비밀번호와 Base64 덩어리 둘이다(WD-681).
    AuthQueryMasker.SentSecrets authSecret = AuthQueryMasker.SentSecrets.NONE;
    try {
      // 1. URL 결정: apiConnectionId가 있으면 connection.baseUrl + request.url()을 path로 해석
      // 연결은 한 번만 읽어 URL·빈 비밀 검사(방식·이름)에 함께 쓴다.
      ApiConnectionResponse conn =
          request.apiConnectionId() != null
              // 호출 URL 은 저장 원문 baseUrl 로 만든다(WD-681) — 표시용 resolvedUrl 은 아래에서 사용자 정보를 가린다.
              ? apiConnectionService.getByIdForCall(request.apiConnectionId())
              : null;
      String resolvedUrl = resolvePreviewUrl(request, conn);

      // 2. SSRF guard
      ssrfProtectionService.validateUrl(resolvedUrl);

      // 3. Resolve auth config — 연결·설정을 한 번만 풀어(ApiCallAuth.resolve, WD-647) 검사·쿼리·헤더·가림 모두 이 값으로
      //    한다. 방식은 연결의 auth_type 컬럼(없으면 설정 안의 authType), 키·우선순위·정리는 실행(Java·Python 실행기)과 같다.
      Map<String, String> authConfig = resolveAuthConfig(request);
      ApiCallAuth.ResolvedAuth auth = ApiCallAuth.resolve(conn, authConfig);
      // 응답 본문·오류 문구에서 가릴 값은 실제로 보내는 비밀 전부다(WD-647) — 예전에는 쿼리 키만 가려, 헤더 키·Bearer 토큰을 되울리는 대상이면
      // 화면·AI 챗으로 원문이 나갔다.
      authSecret = auth.sentSecrets();

      // 비밀이 비었거나(WD-639) 헤더 이름을 쓸 수 없으면(WD-647) 대상 서버를 부르지 않고 안내한다 — URL 조립보다 먼저 본다. 예전에는 URL
      // 조립 오류가 먼저 나 빈 비밀 안내가 가려졌다. 다른 실패처럼 표시 URL 도 돌려주되, 만들 수 없는 경우만 null 이다.
      String authProblem = ApiCallAuth.describeProblem(auth, conn != null ? conn.name() : null);
      if (authProblem != null) {
        return new ApiCallPreviewResponse(
            false,
            null,
            List.of(),
            List.of(),
            0,
            authProblem,
            displayUrlOrNull(resolvedUrl, request.queryParams()));
      }

      // 4~5. Build URI(정적 쿼리 + 쿼리 인증 키) — 호출용(키 원문)과 응답용(키 가림)을 함께 만든다(WD-177). 응답의 resolvedUrl 은
      //    화면·AI 챗으로 나가므로 인증 쿼리 키를 ****XXXX 로 가린 표시용 URL 이다. 키는 연결 테스트·실행과 같은 공용 규칙
      //    (ResolvedAuth.buildUrls, WD-657)으로 한 번만 인코딩한다(WD-182). 정적 쿼리 값에 쿼리에 쓸 수 없는 문자가 있으면 원문 없는
      //    고정 문구로 실패한다.
      AuthQueryMasker.AuthQueryUrls urls =
          auth.buildUrls(
              resolvedUrl, request.queryParams() != null ? request.queryParams() : Map.of());
      URI requestUri = urls.callUri();
      // 표시용 URL 은 옛 연결·주소에 직접 쓴 사용자 정보(아이디:비밀번호@)의 비밀번호도 가려져 있다(WD-681, AuthQueryMasker.build).
      resolvedRequestUrl = urls.displayUrl();

      // 6. Execute HTTP request — Netty 가 못 부르는 호스트(밑줄 등)는 RedirectFollower 가 첫 홉부터 홉마다 요청 전에 고정
      // 문구로
      //    막는다(WD-676·WD-693).
      String responseBody = executeRequest(requestUri, request, auth, timeoutMs);

      // 7. 응답을 가린다 — (WD-177) 응답이 요청 파라미터·헤더를 되울려 보낸 비밀이 담겨 올 수 있다. rawJson 과 결과 행(previewRows)을 이
      //    가린 본문 하나로 만든다(WD-657) — 예전에는 rawJson 만 가리고 행은 원문 본문으로 만들어, httpbin /headers 같은 echo 응답에서
      //    「Authorization: Bearer <원문>」 이 행으로 화면·AI 챗에 나갔다. 가린 뒤 자른다(자른 뒤 가리면 경계에 걸친 앞부분이 남는다).
      //    BASIC 비밀번호(WD-681 리뷰)는 SMTP 와 같은 규칙으로 화면 원문(rawJson)에서만 지운다 — 결과 행 본문에서 지우면
      //    「admin」 같은 짧은 낱말 비밀번호가 응답의 같은 글자를 모두 바꿔 행이 망가진다. 결과 행에서는 값 전체가 비밀번호인 칸만 가린다(maskRows).
      //    rawJson 은 이미 가린 본문에 비밀번호 규칙만 더한다 — 본문을 두 번 가리지 않는다.
      String maskedBody = authSecret.maskBody(responseBody);
      String rawJson =
          truncate(authSecret.maskPasswordInMaskedBody(maskedBody), MAX_RAW_JSON_BYTES);

      // 8. Convert field mappings
      List<ApiCallConfig.FieldMapping> fieldMappings = toFieldMappings(request.fieldMappings());

      // 9. Parse response — dataPath가 비어있으면 루트($)로 대체
      String effectiveDataPath =
          (request.dataPath() == null || request.dataPath().isBlank()) ? "$" : request.dataPath();
      List<Map<String, Object>> allRows =
          jsonResponseParser.parseAndMap(maskedBody, effectiveDataPath, fieldMappings, null, 0);

      List<String> columns =
          request.fieldMappings() != null
              ? request.fieldMappings().stream()
                  .map(ApiCallPreviewRequest.FieldMappingPreview::targetColumn)
                  .toList()
              : List.of();

      List<Map<String, Object>> previewRows =
          authSecret.maskRows(
              allRows.size() > MAX_PREVIEW_ROWS ? allRows.subList(0, MAX_PREVIEW_ROWS) : allRows);

      return new ApiCallPreviewResponse(
          true, rawJson, previewRows, columns, allRows.size(), null, resolvedRequestUrl);

    } catch (Exception e) {
      // 실행(ApiCallExecutor)과 같은 분류·문구를 쓴다(WD-150) — 예전에는 e.getMessage() 를 그대로 돌려줘 같은 연결 거부가
      // 실행 상세와 다르게(「Connection refused: …」) 보였고, 4xx 는 WebClientResponseException 메시지에 쿼리로 붙인
      // API 키가 담긴 URL 이 그대로 노출됐다. 로그에도 원문 메시지 대신 같은 문구를 남긴다(스택은 debug 로만).
      // 분류하지 못한 원문 메시지에 키가 실려 올 수 있어 인증 쿼리 키(원문·인코딩형)도 가린다(WD-177).
      String message = authSecret.scrubText(ApiCallErrorMessages.describe(e, timeoutMs));
      log.warn("[Preview] 실패: {} ({})", message, e.getClass().getSimpleName());
      log.debug("[Preview] 실패 상세", e);
      return new ApiCallPreviewResponse(
          false, null, List.of(), List.of(), 0, message, resolvedRequestUrl);
    }
  }

  // ── private helpers ────────────────────────────────────────────────────────

  /**
   * 프리뷰 요청의 최종 URL을 결정한다.
   *
   * <p>apiConnectionId가 있으면 connection.baseUrl + request.url()을 path로 결합한다. 없으면 request.url()을 그대로
   * 사용한다.
   */
  private String resolvePreviewUrl(ApiCallPreviewRequest request, ApiConnectionResponse conn) {
    if (conn != null) {
      return UrlUtils.joinUrl(conn.baseUrl(), request.url());
    }
    return request.url();
  }

  /**
   * 인증 문제로 부르지 않을 때 돌려줄 표시용 URL — 정적 쿼리만 붙인다(인증 키는 붙이지 않는다). 기준 URL 을 만들 수 없으면(공백·한글 등) null — 그
   * 경우에도 인증 문제 안내가 URL 오류보다 먼저다(WD-647).
   */
  private String displayUrlOrNull(String resolvedUrl, Map<String, String> queryParams) {
    try {
      // 사용자 정보의 비밀번호는 build 가 가린다(WD-681).
      return AuthQueryMasker.build(resolvedUrl, queryParams, null, null).displayUrl();
    } catch (AuthQueryMasker.InvalidRequestUrlException e) {
      return null;
    }
  }

  private Map<String, String> resolveAuthConfig(ApiCallPreviewRequest request) {
    if (request.apiConnectionId() != null) {
      return apiConnectionService.getDecryptedAuthConfig(request.apiConnectionId());
    }
    if (request.inlineAuth() != null) {
      return request.inlineAuth();
    }
    return null;
  }

  /**
   * 대상 서버를 부른다 — 리다이렉트는 실행(ApiCallExecutor)과 같은 {@link RedirectFollower} 로 따라간다(WD-693): 홉마다 SSRF
   * 검사, 메서드·본문 규칙(RedirectLocations.keepsMethodAndBody), 다른 출처 홉의 인증 떼기, 최대 홉 수·프래그먼트 제거. 예전에는 기본
   * WebClient(따라가지 않음)라 3xx 본문을 미리보기로 보여 실행 결과와 달랐다. 응답 표시 URL 은 처음 요청 주소 그대로다(마지막 홉 주소는 보이지 않는다).
   *
   * <p>시간 제한은 모든 홉을 합쳐 timeoutMs 한 번이다(WD-693) — 홉마다 걸면 사용자가 기다리는 한 번의 미리보기가 (최대 홉 수+1)×timeoutMs
   * 동안 붙잡힌다. 넘으면 예전과 같은 「응답 시간 초과(n초)」 문구다. 4xx·5xx 는 실행과 같은 예외·문구다.
   */
  private String executeRequest(
      URI uri, ApiCallPreviewRequest request, ApiCallAuth.ResolvedAuth auth, int timeoutMs) {
    String method = request.method() != null ? request.method().toUpperCase(Locale.ROOT) : "GET";
    return RedirectFollower.follow(
            new RedirectFollower.Request(
                client,
                ssrfProtectionService,
                method,
                uri,
                request.headers(),
                request.body(),
                auth,
                timeoutMs,
                // 모든 홉을 합쳐 timeoutMs 한 번
                false,
                // 4xx·5xx 는 실행과 같은 예외·문구
                true))
        .getBody();
  }

  private List<ApiCallConfig.FieldMapping> toFieldMappings(
      List<ApiCallPreviewRequest.FieldMappingPreview> previews) {
    if (previews == null) return List.of();
    return previews.stream()
        .map(
            p ->
                new ApiCallConfig.FieldMapping(
                    p.sourceField(), p.targetColumn(), p.dataType(), null, null, null))
        .toList();
  }

  /**
   * UTF-8 바이트 경계에서 안전하게 문자열을 자른다.
   *
   * <p>단순히 maxBytes 위치에서 자르면 한글 등 멀티바이트 문자의 중간을 잘라 깨진 문자(\uFFFD)가 생성될 수 있다. 이를 방지하기 위해 maxBytes
   * 위치부터 뒤로 이동하여 UTF-8 선행 바이트(문자 시작점, 0x80~0xBF 범위가 아닌 바이트)를 찾아 그 직전까지만 포함한다.
   *
   * <p>UTF-8 인코딩 규칙: - 1바이트 문자: 0x00~0x7F (ASCII) - 멀티바이트 연속 바이트: 0x80~0xBF (선행 바이트가 아님, 중간/끝 바이트)
   * - 멀티바이트 선행 바이트: 0xC0~0xFF (문자 시작점)
   *
   * @param s 원본 문자열 (null 허용)
   * @param maxBytes 최대 바이트 크기
   * @return maxBytes 이내로 안전하게 잘린 UTF-8 문자열
   */
  private String truncate(String s, int maxBytes) {
    if (s == null) return null;
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) return s;

    // maxBytes 위치에서 뒤로 이동하며 UTF-8 문자 시작점을 찾는다.
    // 0x80~0xBF 범위의 바이트는 멀티바이트 문자의 연속 바이트이므로 건너뛴다.
    int end = maxBytes;
    while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
      end--;
    }

    return new String(bytes, 0, end, java.nio.charset.StandardCharsets.UTF_8);
  }
}
