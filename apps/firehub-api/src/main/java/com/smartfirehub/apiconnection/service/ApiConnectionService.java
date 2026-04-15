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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Qualifier;
import java.util.concurrent.Executor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;


/**
 * API 연결(ApiConnection) 비즈니스 로직 서비스.
 * Phase 9: baseUrl 정규화/SSRF 검증, 헬스체크(testConnection), slim 목록(findSelectable) 추가.
 * Task 9-1-6: refreshAllAsync — pipelineExecutor + AsyncJobService로 전체 헬스체크 비동기 실행.
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
  @Qualifier("pipelineExecutor") private final Executor pipelineExecutor;

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
            request.healthCheckPath());

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
    return decryptToMap(encryptedConfig);
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
        request.healthCheckPath());

    return getById(id);
  }

  @Transactional
  public void delete(Long id) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    repository.deleteById(id);
  }

  /**
   * 일반 사용자가 파이프라인 스텝에서 선택할 수 있는 slim 목록을 반환.
   * 민감한 authConfig, healthCheckPath, last* 필드는 제외.
   */
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
   * 저장된 API 연결의 헬스체크 경로로 GET 호출하여 상태를 반환하고 DB에 반영한다.
   * healthCheckPath가 없으면 baseUrl 자체를 GET.
   * 5초 타임아웃 적용. 결과는 last_status, last_checked_at 등에 저장된다.
   */
  @Transactional
  public TestConnectionResponse testConnection(Long id) {
    ApiConnectionResponse conn = getById(id);
    Map<String, String> rawConfig = getDecryptedAuthConfig(id);
    String url = UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath());

    long start = System.currentTimeMillis();
    try {
      var resp =
          webClient
              .get()
              .uri(url)
              .headers(h -> buildAuthHeaders(conn.authType(), rawConfig).forEach(h::set))
              .retrieve()
              .toBodilessEntity()
              .block(Duration.ofSeconds(5));

      long latency = System.currentTimeMillis() - start;
      Integer status = resp != null ? resp.getStatusCode().value() : null;
      boolean ok = resp != null && resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      repository.updateHealthStatus(id, ok ? "UP" : "DOWN", latency, err);
      return new TestConnectionResponse(ok, status, latency, err);

    } catch (WebClientResponseException e) {
      long latency = System.currentTimeMillis() - start;
      String err = "HTTP " + e.getStatusCode().value();
      repository.updateHealthStatus(id, "DOWN", latency, err);
      return new TestConnectionResponse(false, e.getStatusCode().value(), latency, err);

    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      String msg = e.getMessage();
      repository.updateHealthStatus(id, "DOWN", latency, msg);
      return new TestConnectionResponse(false, null, latency, msg);
    }
  }

  /**
   * 헬스체크 가능한 모든 API 연결을 비동기 Job으로 점검한다.
   *
   * <p>AsyncJobService로 Job을 생성(DB 추적)하고 pipelineExecutor 스레드풀에서 실행한다.
   * 진행률은 SSE로 실시간 스트리밍된다. 클라이언트는 반환된 jobId로
   * GET /api/v1/jobs/{jobId}/status 폴링 또는 SSE 구독 가능.
   *
   * @return AsyncJob ID (문자열 UUID)
   */
  public String refreshAllAsync() {
    // 시스템 기동 Job — userId는 0L(시스템)으로 생성한다
    String jobId = asyncJobService.createJob(
        "API_CONNECTION_REFRESH_ALL",
        "api_connection",
        "all",
        0L,
        null);

    pipelineExecutor.execute(() -> {
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
            log.warn("refreshAllAsync job {}: connection id={} 실패 — {}", jobId, id, e.getMessage());
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

        asyncJobService.completeJob(
            jobId, Map.of("total", total, "checked", total));
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
   * Base URL을 정규화하고 SSRF 보호 정책에 맞게 검증한다.
   * SsrfProtectionService.validateUrl()을 활용하여 스킴 및 IP 대역 차단.
   *
   * @throws ApiConnectionException URL이 유효하지 않거나 내부 네트워크로의 요청인 경우
   */
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
   * authType과 authConfig를 기반으로 HTTP 인증 헤더를 생성한다.
   * API_KEY: headerName + apiKey, BEARER: Authorization Bearer.
   */
  private Map<String, String> buildAuthHeaders(String authType, Map<String, String> config) {
    Map<String, String> headers = new HashMap<>();
    if (config == null) return headers;

    if ("API_KEY".equals(authType)) {
      String headerName = config.getOrDefault("headerName", "X-API-Key");
      String apiKey = config.get("apiKey");
      if (apiKey != null) headers.put(headerName, apiKey);
    } else if ("BEARER".equals(authType)) {
      String token = config.get("token");
      if (token != null) headers.put("Authorization", "Bearer " + token);
    }
    return headers;
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
