package com.smartfirehub.pipeline.service;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.pipeline.dto.ApiCallPreviewRequest;
import com.smartfirehub.pipeline.dto.ApiCallPreviewResponse;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class ApiCallPreviewService {

  private static final int DEFAULT_TIMEOUT_MS = 30_000;
  private static final int MAX_RAW_JSON_BYTES = 10 * 1024; // 10 KB
  private static final int MAX_PREVIEW_ROWS = 5;

  private final SsrfProtectionService ssrfProtectionService;
  private final JsonResponseParser jsonResponseParser;
  private final ApiConnectionService apiConnectionService;
  private final WebClient.Builder webClientBuilder;

  public ApiCallPreviewResponse preview(ApiCallPreviewRequest request) {
    String resolvedRequestUrl = null;
    // 오류 문구 분류용(WD-150) — 타임아웃 문구의 초, DNS 실패 문구의 호스트.
    int timeoutMs = request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;
    String targetHost = null;
    // 오류 문구에서 가릴 인증 쿼리 키(WD-177) — 인증 설정을 읽은 뒤에 채운다.
    String authSecret = null;
    try {
      // 1. URL 결정: apiConnectionId가 있으면 connection.baseUrl + request.url()을 path로 해석
      String resolvedUrl = resolvePreviewUrl(request);

      // 2. SSRF guard
      ssrfProtectionService.validateUrl(resolvedUrl);

      // 3. Resolve auth config
      Map<String, String> authConfig = resolveAuthConfig(request);

      // 4. Build query params (static + auth query-param)
      Map<String, String> allQueryParams = new LinkedHashMap<>();
      if (request.queryParams() != null) {
        allQueryParams.putAll(request.queryParams());
      }
      String authParamName = null;
      if (authConfig != null) {
        String authType = authConfig.get("authType");
        if ("API_KEY".equals(authType) && "query".equals(authConfig.get("placement"))) {
          String paramName = authConfig.get("paramName");
          String apiKey = authConfig.get("apiKey");
          if (paramName != null && apiKey != null) {
            allQueryParams.put(paramName, apiKey);
            authParamName = paramName;
            authSecret = apiKey;
          }
        }
      }

      // 5. Build URI — 호출용(키 원문)과 응답용(키 가림)을 함께 만든다(WD-177). 응답의 resolvedUrl 은 화면·AI 챗으로 나가므로
      //    인증 쿼리 키를 ****XXXX 로 가린 표시용 URL 이다. 키에 쿼리에 쓸 수 없는 문자가 있으면 원문 없는 고정 문구로 실패한다.
      AuthQueryMasker.AuthQueryUrls urls =
          AuthQueryMasker.build(resolvedUrl, allQueryParams, authParamName, authSecret);
      URI requestUri = urls.callUri();
      resolvedRequestUrl = urls.displayUrl();
      targetHost = requestUri.getHost();

      // 6. Execute HTTP request
      String responseBody = executeRequest(requestUri, request, authConfig, timeoutMs);

      // 7. Truncate raw JSON — (WD-177) 응답이 요청 파라미터를 되울려 인증 키가 담겨 올 수 있어 먼저 가린 뒤 자른다.
      String rawJson =
          truncate(AuthQueryMasker.scrubSecret(responseBody, authSecret), MAX_RAW_JSON_BYTES);

      // 8. Convert field mappings
      List<ApiCallConfig.FieldMapping> fieldMappings = toFieldMappings(request.fieldMappings());

      // 9. Parse response — dataPath가 비어있으면 루트($)로 대체
      String effectiveDataPath =
          (request.dataPath() == null || request.dataPath().isBlank()) ? "$" : request.dataPath();
      List<Map<String, Object>> allRows =
          jsonResponseParser.parseAndMap(responseBody, effectiveDataPath, fieldMappings, null);

      List<String> columns =
          request.fieldMappings() != null
              ? request.fieldMappings().stream()
                  .map(ApiCallPreviewRequest.FieldMappingPreview::targetColumn)
                  .toList()
              : List.of();

      List<Map<String, Object>> previewRows =
          allRows.size() > MAX_PREVIEW_ROWS ? allRows.subList(0, MAX_PREVIEW_ROWS) : allRows;

      return new ApiCallPreviewResponse(
          true, rawJson, previewRows, columns, allRows.size(), null, resolvedRequestUrl);

    } catch (Exception e) {
      // 실행(ApiCallExecutor)과 같은 분류·문구를 쓴다(WD-150) — 예전에는 e.getMessage() 를 그대로 돌려줘 같은 연결 거부가
      // 실행 상세와 다르게(「Connection refused: …」) 보였고, 4xx 는 WebClientResponseException 메시지에 쿼리로 붙인
      // API 키가 담긴 URL 이 그대로 노출됐다. 로그에도 원문 메시지 대신 같은 문구를 남긴다(스택은 debug 로만).
      // 분류하지 못한 원문 메시지에 키가 실려 올 수 있어 인증 쿼리 키(원문·인코딩형)도 가린다(WD-177).
      String message =
          AuthQueryMasker.scrubSecret(
              ApiCallErrorMessages.describe(e, timeoutMs, targetHost), authSecret);
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
  private String resolvePreviewUrl(ApiCallPreviewRequest request) {
    if (request.apiConnectionId() != null) {
      ApiConnectionResponse conn = apiConnectionService.getById(request.apiConnectionId());
      return UrlUtils.joinUrl(conn.baseUrl(), request.url());
    }
    return request.url();
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

  private String executeRequest(
      URI uri, ApiCallPreviewRequest request, Map<String, String> authConfig, int timeoutMs) {

    WebClient client = webClientBuilder.build();
    String method = request.method() != null ? request.method().toUpperCase() : "GET";

    WebClient.RequestHeadersSpec<?> requestSpec;

    if ("POST".equals(method)) {
      WebClient.RequestBodySpec bodySpec = client.post().uri(uri);
      applyHeaders(bodySpec, request, authConfig);
      if (request.body() != null && !request.body().isBlank()) {
        requestSpec = bodySpec.header("Content-Type", "application/json").bodyValue(request.body());
      } else {
        requestSpec = bodySpec;
      }
    } else {
      WebClient.RequestHeadersSpec<?> getSpec = client.get().uri(uri);
      applyHeaders(getSpec, request, authConfig);
      requestSpec = getSpec;
    }

    return requestSpec
        .retrieve()
        .bodyToMono(String.class)
        .timeout(Duration.ofMillis(timeoutMs))
        .block();
  }

  private void applyHeaders(
      WebClient.RequestHeadersSpec<?> spec,
      ApiCallPreviewRequest request,
      Map<String, String> authConfig) {

    if (request.headers() != null) {
      request.headers().forEach(spec::header);
    }

    if (authConfig != null) {
      String authType = authConfig.get("authType");
      String placement = authConfig.getOrDefault("placement", "header");
      if ("API_KEY".equals(authType) && "header".equals(placement)) {
        String headerName = authConfig.get("headerName");
        String apiKey = authConfig.get("apiKey");
        if (headerName != null && apiKey != null) {
          spec.header(headerName, apiKey);
        }
      } else if ("BEARER".equals(authType)) {
        String token = authConfig.get("token");
        if (token != null) {
          spec.header("Authorization", "Bearer " + token);
        }
      }
    }
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
