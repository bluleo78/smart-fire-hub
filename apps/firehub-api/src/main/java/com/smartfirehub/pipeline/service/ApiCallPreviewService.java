package com.smartfirehub.pipeline.service;

import com.smartfirehub.apiconnection.service.ApiConnectionService;
import com.smartfirehub.pipeline.dto.ApiCallPreviewRequest;
import com.smartfirehub.pipeline.dto.ApiCallPreviewResponse;
import com.smartfirehub.pipeline.service.executor.ApiCallConfig;
import com.smartfirehub.pipeline.service.executor.JsonResponseParser;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

@Service
public class ApiCallPreviewService {

  private static final int DEFAULT_TIMEOUT_MS = 30_000;
  private static final int MAX_RAW_JSON_BYTES = 10 * 1024; // 10 KB
  private static final int MAX_PREVIEW_ROWS = 5;

  private final SsrfProtectionService ssrfProtectionService;
  private final JsonResponseParser jsonResponseParser;
  private final ApiConnectionService apiConnectionService;
  private final WebClient.Builder webClientBuilder;

  public ApiCallPreviewService(
      SsrfProtectionService ssrfProtectionService,
      JsonResponseParser jsonResponseParser,
      ApiConnectionService apiConnectionService,
      WebClient.Builder webClientBuilder) {
    this.ssrfProtectionService = ssrfProtectionService;
    this.jsonResponseParser = jsonResponseParser;
    this.apiConnectionService = apiConnectionService;
    this.webClientBuilder = webClientBuilder;
  }

  public ApiCallPreviewResponse preview(ApiCallPreviewRequest request) {
    try {
      // 1. SSRF guard
      ssrfProtectionService.validateUrl(request.url());

      // 2. Resolve auth config
      Map<String, String> authConfig = resolveAuthConfig(request);

      // 3. Build query params (static + auth query-param)
      Map<String, String> allQueryParams = new LinkedHashMap<>();
      if (request.queryParams() != null) {
        allQueryParams.putAll(request.queryParams());
      }
      if (authConfig != null) {
        String authType = authConfig.get("authType");
        if ("API_KEY".equals(authType) && "query".equals(authConfig.get("placement"))) {
          String paramName = authConfig.get("paramName");
          String apiKey = authConfig.get("apiKey");
          if (paramName != null && apiKey != null) {
            allQueryParams.put(paramName, apiKey);
          }
        }
      }

      // 4. Build URI
      UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(request.url());
      allQueryParams.forEach(uriBuilder::queryParam);
      URI requestUri = uriBuilder.build(true).toUri();

      // 5. Execute HTTP request
      int timeoutMs = request.timeoutMs() != null ? request.timeoutMs() : DEFAULT_TIMEOUT_MS;
      String responseBody = executeRequest(requestUri, request, authConfig, timeoutMs);

      // 6. Truncate raw JSON
      String rawJson = truncate(responseBody, MAX_RAW_JSON_BYTES);

      // 7. Convert field mappings
      List<ApiCallConfig.FieldMapping> fieldMappings = toFieldMappings(request.fieldMappings());

      // 8. Parse response
      List<Map<String, Object>> allRows =
          jsonResponseParser.parseAndMap(responseBody, request.dataPath(), fieldMappings, null);

      List<String> columns =
          request.fieldMappings() != null
              ? request.fieldMappings().stream()
                  .map(ApiCallPreviewRequest.FieldMappingPreview::targetColumn)
                  .toList()
              : List.of();

      List<Map<String, Object>> previewRows =
          allRows.size() > MAX_PREVIEW_ROWS ? allRows.subList(0, MAX_PREVIEW_ROWS) : allRows;

      return new ApiCallPreviewResponse(true, rawJson, previewRows, columns, allRows.size(), null);

    } catch (Exception e) {
      return new ApiCallPreviewResponse(false, null, List.of(), List.of(), 0, e.getMessage());
    }
  }

  // ── private helpers ────────────────────────────────────────────────────────

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

  private String truncate(String s, int maxBytes) {
    if (s == null) return null;
    byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    if (bytes.length <= maxBytes) return s;
    return new String(bytes, 0, maxBytes, java.nio.charset.StandardCharsets.UTF_8);
  }
}
