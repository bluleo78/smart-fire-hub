package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * API 연결(ApiConnection) 비즈니스 로직 서비스. Phase 9: baseUrl 정규화/SSRF 검증, 헬스체크(testConnection), slim
 * 목록(findSelectable), 전체 헬스체크 비동기 Job(refreshAllAsync) 제공.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiConnectionService {

  private static final Set<String> SENSITIVE_KEY_PARTS =
      Set.of("key", "token", "secret", "password");

  private final ApiConnectionRepository repository;
  private final EncryptionService encryptionService;
  private final ObjectMapper objectMapper;
  private final SsrfProtectionService ssrfProtectionService;
  private final AsyncJobService asyncJobService;

  @Qualifier("pipelineExecutor")
  private final Executor pipelineExecutor;

  /** WebClient는 프로젝트 관행에 따라 빌더를 직접 생성한다 (별도 Bean 없음). */
  private final WebClient webClient = WebClient.builder().build();

  // ── 공개 API ────────────────────────────────────────────────────────────────

  @Transactional
  public ApiConnectionResponse create(CreateApiConnectionRequest request, Long userId) {
    validateAuthType(request.authType());
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());

    String encryptedConfig = serializeAndEncrypt(request.authConfig());
    Long id =
        repository.save(
            request.name(),
            request.description(),
            request.authType(),
            encryptedConfig,
            userId,
            normalizedBaseUrl,
            normalizeHealthCheckPath(request.healthCheckPath()));

    return getById(id);
  }

  @Transactional(readOnly = true)
  public List<ApiConnectionResponse> getAll() {
    return repository.findAll().stream().map(this::toResponse).toList();
  }

  @Transactional(readOnly = true)
  public ApiConnectionResponse getById(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    return toResponse(record);
  }

  @Transactional(readOnly = true)
  public Map<String, String> getDecryptedAuthConfig(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    String encryptedConfig = record.get(field(name("api_connection", "auth_config"), String.class));
    Map<String, String> config = decryptToMap(encryptedConfig);
    // authType 은 별도 컬럼이라 복호화된 Map 에 들어있지 않다. 호출자(Preview/Executor 등)가
    // authType 으로 분기(API_KEY + placement=query 등)할 수 있도록 함께 합쳐 반환한다. (#113)
    String authType = record.get(field(name("api_connection", "auth_type"), String.class));
    if (authType != null) {
      config = new HashMap<>(config);
      config.putIfAbsent("authType", authType);
    }
    return config;
  }

  @Transactional
  public ApiConnectionResponse update(Long id, UpdateApiConnectionRequest request) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));

    String encryptedConfig = null;
    if (request.authConfig() != null) {
      if (request.authType() != null) {
        validateAuthType(request.authType());
      }
      encryptedConfig = serializeAndEncrypt(request.authConfig());
    }

    // baseUrl이 있으면 정규화 및 SSRF 검증
    String normalizedBaseUrl = null;
    if (request.baseUrl() != null && !request.baseUrl().isBlank()) {
      normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());
    }

    String authType = request.authType() != null ? request.authType() : fetchAuthType(id);
    repository.update(
        id,
        request.name(),
        request.description(),
        authType,
        encryptedConfig,
        normalizedBaseUrl,
        normalizeHealthCheckPath(request.healthCheckPath()));

    return getById(id);
  }

  @Transactional
  public void delete(Long id) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    repository.deleteById(id);
  }

  /** 일반 사용자가 파이프라인 스텝에서 선택할 수 있는 slim 목록을 반환. 민감한 authConfig, healthCheckPath, last* 필드는 제외. */
  @Transactional(readOnly = true)
  public List<ApiConnectionSelectableResponse> findSelectable() {
    return repository.findAll().stream()
        .map(
            r ->
                new ApiConnectionSelectableResponse(
                    r.get(field(name("api_connection", "id"), Long.class)),
                    r.get(field(name("api_connection", "name"), String.class)),
                    r.get(field(name("api_connection", "auth_type"), String.class)),
                    r.get(field(name("api_connection", "base_url"), String.class))))
        .toList();
  }

  /**
   * 저장된 API 연결의 헬스체크 경로로 GET 호출하여 상태를 반환하고 DB에 반영한다. healthCheckPath가 없으면 baseUrl 자체를 GET. 5초 타임아웃.
   * DB 쓰기(updateHealthStatus)는 내부적으로 자체 트랜잭션을 사용하므로 본 메서드는 트랜잭션 밖에서 HTTP를 수행하여 커넥션 풀 점유를 피한다.
   */
  public TestConnectionResponse testConnection(Long id) {
    ApiConnectionResponse conn = getById(id);
    Map<String, String> rawConfig = getDecryptedAuthConfig(id);
    String baseUrl = UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath());
    // placement=query 인 경우 URL 에 인증 파라미터 부착 (#113)
    String url = applyAuthQueryParams(baseUrl, conn.authType(), rawConfig);

    long start = System.currentTimeMillis();
    try {
      // 응답 본문/헤더를 함께 받아 디버깅용으로 노출 (#76).
      var resp =
          webClient
              .get()
              .uri(url)
              .headers(h -> buildAuthHeaders(conn.authType(), rawConfig).forEach(h::set))
              .retrieve()
              .toEntity(String.class)
              .block(Duration.ofSeconds(5));

      long latency = System.currentTimeMillis() - start;
      Integer status = resp != null ? resp.getStatusCode().value() : null;
      boolean ok = resp != null && resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      String body = resp != null ? truncateBody(resp.getBody()) : null;
      Map<String, String> headers = resp != null ? sanitizeHeaders(resp.getHeaders()) : Map.of();
      String contentType =
          resp != null && resp.getHeaders().getContentType() != null
              ? resp.getHeaders().getContentType().toString()
              : null;
      repository.updateHealthStatus(id, ok ? "UP" : "DOWN", latency, err);
      return new TestConnectionResponse(ok, status, latency, err, url, body, headers, contentType);

    } catch (WebClientResponseException e) {
      long latency = System.currentTimeMillis() - start;
      String err = "HTTP " + e.getStatusCode().value();
      repository.updateHealthStatus(id, "DOWN", latency, err);
      // 4xx/5xx 응답 본문도 운영자가 봐야 디버깅 가능하므로 함께 노출.
      String body = truncateBody(e.getResponseBodyAsString());
      Map<String, String> headers = sanitizeHeaders(e.getHeaders());
      String contentType =
          e.getHeaders().getContentType() != null ? e.getHeaders().getContentType().toString() : null;
      return new TestConnectionResponse(
          false, e.getStatusCode().value(), latency, err, url, body, headers, contentType);

    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      String msg = e.getMessage();
      repository.updateHealthStatus(id, "DOWN", latency, msg);
      return new TestConnectionResponse(false, null, latency, msg, url, null, Map.of(), null);
    }
  }

  /**
   * (#90) 저장 전 dry-run 헬스체크. CreateApiConnectionRequest의 payload를 가지고 외부 API 호출만 수행하고 DB는 변경하지 않는다.
   * authType 검증/baseUrl SSRF 검증은 동일하게 수행하므로 잘못된 입력은 즉시 ApiConnectionException으로 차단된다.
   */
  public TestConnectionResponse testConnectionPayload(CreateApiConnectionRequest request) {
    validateAuthType(request.authType());
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());
    String normalizedPath = normalizeHealthCheckPath(request.healthCheckPath());
    String joinedUrl = UrlUtils.joinUrl(normalizedBaseUrl, normalizedPath);
    Map<String, String> rawConfig =
        request.authConfig() != null ? request.authConfig() : Map.of();
    // placement=query 인 경우 URL 에 인증 파라미터 부착 (#113)
    String url = applyAuthQueryParams(joinedUrl, request.authType(), rawConfig);

    long start = System.currentTimeMillis();
    try {
      var resp =
          webClient
              .get()
              .uri(url)
              .headers(h -> buildAuthHeaders(request.authType(), rawConfig).forEach(h::set))
              .retrieve()
              .toEntity(String.class)
              .block(Duration.ofSeconds(5));

      long latency = System.currentTimeMillis() - start;
      Integer status = resp != null ? resp.getStatusCode().value() : null;
      boolean ok = resp != null && resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      String body = resp != null ? truncateBody(resp.getBody()) : null;
      Map<String, String> headers = resp != null ? sanitizeHeaders(resp.getHeaders()) : Map.of();
      String contentType =
          resp != null && resp.getHeaders().getContentType() != null
              ? resp.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(ok, status, latency, err, url, body, headers, contentType);
    } catch (WebClientResponseException e) {
      long latency = System.currentTimeMillis() - start;
      String err = "HTTP " + e.getStatusCode().value();
      String body = truncateBody(e.getResponseBodyAsString());
      Map<String, String> headers = sanitizeHeaders(e.getHeaders());
      String contentType =
          e.getHeaders().getContentType() != null ? e.getHeaders().getContentType().toString() : null;
      return new TestConnectionResponse(
          false, e.getStatusCode().value(), latency, err, url, body, headers, contentType);
    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      String msg = e.getMessage();
      return new TestConnectionResponse(false, null, latency, msg, url, null, Map.of(), null);
    }
  }

  /** 응답 본문을 최대 4KB로 잘라 반환 (UI 노출용). null/빈값은 그대로 반환. */
  private static final int MAX_BODY_PREVIEW_BYTES = 4096;

  private static String truncateBody(String body) {
    if (body == null || body.isEmpty()) return body;
    if (body.length() <= MAX_BODY_PREVIEW_BYTES) return body;
    return body.substring(0, MAX_BODY_PREVIEW_BYTES) + "\n... (truncated)";
  }

  /** 민감 헤더 마스킹 — 인증/세션/쿠키 헤더 값은 노출하지 않는다. */
  private static final Set<String> SENSITIVE_HEADER_NAMES =
      Set.of(
          "authorization",
          "proxy-authorization",
          "set-cookie",
          "cookie",
          "x-api-key",
          "x-auth-token");

  private static Map<String, String> sanitizeHeaders(
      org.springframework.http.HttpHeaders headers) {
    Map<String, String> out = new HashMap<>();
    headers.forEach(
        (k, v) -> {
          String joined = String.join(", ", v);
          if (SENSITIVE_HEADER_NAMES.contains(k.toLowerCase())) {
            out.put(k, "****");
          } else {
            out.put(k, joined);
          }
        });
    return out;
  }

  /**
   * 헬스체크 가능한 모든 API 연결을 비동기 Job으로 점검한다.
   *
   * <p>AsyncJobService로 Job을 생성(DB 추적)하고 pipelineExecutor 스레드풀에서 실행한다. 진행률은 SSE로 실시간 스트리밍된다. 클라이언트는
   * 반환된 jobId로 GET /api/v1/jobs/{jobId}/status 폴링 또는 SSE 구독 가능.
   *
   * @return AsyncJob ID (문자열 UUID)
   */
  public String refreshAllAsync(Long userId) {
    // 요청자 userId로 AsyncJob 생성 — 0L로 생성 시 user FK 위반 (#59)
    String jobId =
        asyncJobService.createJob(
            "API_CONNECTION_REFRESH_ALL", "api_connection", "all", userId, null);

    pipelineExecutor.execute(
        () -> {
          try {
            List<Record> targets = repository.findHealthCheckable();
            int total = targets.size();
            log.info("refreshAllAsync job {}: {} 대상 헬스체크 시작", jobId, total);

            for (int i = 0; i < total; i++) {
              Record r = targets.get(i);
              Long id = r.get(field(name("api_connection", "id"), Long.class));
              String connName = r.get(field(name("api_connection", "name"), String.class));

              try {
                testConnection(id);
              } catch (Exception e) {
                log.warn(
                    "refreshAllAsync job {}: connection id={} 실패 — {}", jobId, id, e.getMessage());
              }

              // 진행률 보고: 현재 처리 수 / 전체 대상 수
              int progress = total > 0 ? (int) ((i + 1) * 100.0 / total) : 100;
              asyncJobService.updateProgress(
                  jobId,
                  "CHECKING",
                  progress,
                  String.format("처리 중: %s (%d/%d)", connName, i + 1, total),
                  null);
            }

            asyncJobService.completeJob(jobId, Map.of("total", total, "checked", total));
            log.info("refreshAllAsync job {} 완료: {} 대상 처리", jobId, total);

          } catch (Exception e) {
            log.error("refreshAllAsync job {} 실패: {}", jobId, e.getMessage());
            asyncJobService.failJob(jobId, e.getMessage());
          }
        });

    return jobId;
  }

  // ── private helpers ────────────────────────────────────────────────────────

  /**
   * Base URL을 정규화하고 SSRF 보호 정책에 맞게 검증한다. SsrfProtectionService.validateUrl()을 활용하여 스킴 및 IP 대역 차단.
   *
   * @throws ApiConnectionException URL이 유효하지 않거나 내부 네트워크로의 요청인 경우
   */
  /**
   * healthCheckPath를 정규화한다. (#115)
   *
   * <ul>
   *   <li>{@code null} → null (request 에 필드 미포함 = 미변경)
   *   <li>빈 문자열/공백 → 빈 문자열 sentinel ("") (사용자가 명시적으로 비움 = 명시적 clear)
   *   <li>그 외 → 원본 값
   * </ul>
   *
   * <p>repository.update 가 sentinel "" 을 보면 컬럼을 SQL NULL 로 설정한다.
   */
  private String normalizeHealthCheckPath(String raw) {
    if (raw == null) return null;
    if (raw.isBlank()) return "";
    return raw;
  }

  private String validateAndNormalizeBaseUrl(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ApiConnectionException("baseUrl은 필수입니다");
    }
    try {
      URI uri = URI.create(raw);
      String scheme = uri.getScheme();
      if (scheme == null
          || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
        throw new ApiConnectionException("baseUrl은 http 또는 https 스킴이어야 합니다");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new ApiConnectionException("baseUrl에 호스트가 없습니다");
      }
      // SSRF 방어: 사설 IP/예약 IP 차단 (DNS 해석 포함)
      ssrfProtectionService.validateUrl(raw);
      return UrlUtils.normalizeBaseUrl(raw);
    } catch (SsrfException e) {
      throw new ApiConnectionException("SSRF 보호: " + e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ApiConnectionException("유효하지 않은 baseUrl: " + e.getMessage());
    }
  }

  /**
   * authType과 authConfig를 기반으로 HTTP 인증 헤더를 생성한다. API_KEY + placement=query 인 경우는 URL query param 으로
   * 전송해야 하므로 헤더에 포함하지 않는다(applyAuthQueryParams 가 처리). (#113)
   */
  private Map<String, String> buildAuthHeaders(String authType, Map<String, String> config) {
    Map<String, String> headers = new HashMap<>();
    if (config == null) return headers;

    if ("API_KEY".equals(authType)) {
      String placement = config.getOrDefault("placement", "header");
      if ("query".equals(placement)) {
        // placement=query 는 URL 에 붙이므로 헤더 미생성
        return headers;
      }
      String headerName = config.getOrDefault("headerName", "X-API-Key");
      String apiKey = config.get("apiKey");
      if (apiKey != null) headers.put(headerName, apiKey);
    } else if ("BEARER".equals(authType)) {
      String token = config.get("token");
      if (token != null) headers.put("Authorization", "Bearer " + token);
    }
    return headers;
  }

  /**
   * API_KEY + placement=query 인 경우 URL 에 인증 query parameter(예: serviceKey=...) 를 부착해 반환한다. 그 외에는 원본
   * URL 을 그대로 반환. (#113)
   */
  private String applyAuthQueryParams(String url, String authType, Map<String, String> config) {
    if (config == null || !"API_KEY".equals(authType)) return url;
    String placement = config.getOrDefault("placement", "header");
    if (!"query".equals(placement)) return url;
    String paramName = config.get("paramName");
    String apiKey = config.get("apiKey");
    if (paramName == null || paramName.isBlank() || apiKey == null) return url;
    return UriComponentsBuilder.fromUriString(url)
        .queryParam(paramName, apiKey)
        .build(true)
        .toUriString();
  }

  private void validateAuthType(String authType) {
    if (authType == null || (!authType.equals("API_KEY") && !authType.equals("BEARER"))) {
      throw new ApiConnectionException(
          "Unsupported authType: " + authType + ". Supported: API_KEY, BEARER");
    }
  }

  private String serializeAndEncrypt(Map<String, String> authConfig) {
    try {
      String json = objectMapper.writeValueAsString(authConfig);
      return encryptionService.encrypt(json);
    } catch (JsonProcessingException e) {
      throw new ApiConnectionException("Failed to serialize authConfig: " + e.getMessage());
    }
  }

  private Map<String, String> decryptToMap(String encryptedConfig) {
    try {
      String json = encryptionService.decrypt(encryptedConfig);
      return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
    } catch (JsonProcessingException e) {
      throw new ApiConnectionException("Failed to deserialize authConfig: " + e.getMessage());
    }
  }

  private Map<String, String> maskAuthConfig(Map<String, String> authConfig) {
    Map<String, String> masked = new HashMap<>();
    for (Map.Entry<String, String> entry : authConfig.entrySet()) {
      String key = entry.getKey().toLowerCase();
      boolean isSensitive = SENSITIVE_KEY_PARTS.stream().anyMatch(key::contains);
      masked.put(
          entry.getKey(),
          isSensitive ? encryptionService.maskValue(entry.getValue()) : entry.getValue());
    }
    return masked;
  }

  private String fetchAuthType(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    return record.get(field(name("api_connection", "auth_type"), String.class));
  }

  private ApiConnectionResponse toResponse(Record r) {
    String encryptedConfig = r.get(field(name("api_connection", "auth_config"), String.class));
    Map<String, String> plainConfig = decryptToMap(encryptedConfig);
    Map<String, String> masked = maskAuthConfig(plainConfig);

    return new ApiConnectionResponse(
        r.get(field(name("api_connection", "id"), Long.class)),
        r.get(field(name("api_connection", "name"), String.class)),
        r.get(field(name("api_connection", "description"), String.class)),
        r.get(field(name("api_connection", "auth_type"), String.class)),
        masked,
        r.get(field(name("api_connection", "base_url"), String.class)),
        r.get(field(name("api_connection", "health_check_path"), String.class)),
        r.get(field(name("api_connection", "last_status"), String.class)),
        r.get(field(name("api_connection", "last_checked_at"), LocalDateTime.class)),
        r.get(field(name("api_connection", "last_latency_ms"), Long.class)),
        r.get(field(name("api_connection", "last_error_message"), String.class)),
        r.get(field(name("api_connection", "created_by"), Long.class)),
        r.get(field(name("api_connection", "created_at"), LocalDateTime.class)),
        r.get(field(name("api_connection", "updated_at"), LocalDateTime.class)));
  }
}
