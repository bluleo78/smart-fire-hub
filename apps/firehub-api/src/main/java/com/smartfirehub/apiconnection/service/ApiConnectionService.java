package com.smartfirehub.apiconnection.service;

import static org.jooq.impl.DSL.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.apiconnection.dto.ApiConnectionReferencesResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.dto.ApiConnectionSelectableResponse;
import com.smartfirehub.apiconnection.dto.CreateApiConnectionRequest;
import com.smartfirehub.apiconnection.dto.TestConnectionResponse;
import com.smartfirehub.apiconnection.dto.UpdateApiConnectionRequest;
import com.smartfirehub.apiconnection.exception.ApiConnectionException;
import com.smartfirehub.apiconnection.exception.ApiConnectionNameAlreadyExistsException;
import com.smartfirehub.apiconnection.repository.ApiConnectionRepository;
import com.smartfirehub.job.service.AsyncJobService;
import com.smartfirehub.pipeline.service.executor.ApiCallErrorMessages;
import com.smartfirehub.pipeline.service.executor.SsrfException;
import com.smartfirehub.pipeline.service.executor.SsrfProtectionService;
import java.net.URI;
import java.time.Duration;
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

/**
 * API 연결(ApiConnection) 비즈니스 로직 서비스. Phase 9: baseUrl 정규화/SSRF 검증, 헬스체크(testConnection), slim
 * 목록(findSelectable), 전체 헬스체크 비동기 Job(refreshAllAsync) 제공.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiConnectionService {

  private final ApiConnectionRepository repository;

  /**
   * 조회 전용 협력자. testConnection 이 조회를 자기호출하면 프록시가 우회돼 트랜잭션이 열리지 않으므로 별도 빈으로 분리해 두고 반드시 이 참조를 통해 호출한다.
   */
  private final ApiConnectionReader reader;

  private final EncryptionService encryptionService;
  private final ObjectMapper objectMapper;
  private final SsrfProtectionService ssrfProtectionService;
  private final AsyncJobService asyncJobService;

  @Qualifier("pipelineExecutor")
  private final Executor pipelineExecutor;

  /** WebClient는 프로젝트 관행에 따라 빌더를 직접 생성한다 (별도 Bean 없음). */
  private final WebClient webClient = WebClient.builder().build();

  /** 헬스체크 HTTP 호출 타임아웃(초). 메시지 문구("응답 시간 초과 (N초)")와 값이 어긋나지 않도록 한 곳에서 관리한다. */
  private static final int HEALTH_CHECK_TIMEOUT_SECONDS = 5;

  // ── 공개 API ────────────────────────────────────────────────────────────────

  @Transactional
  public ApiConnectionResponse create(CreateApiConnectionRequest request, Long userId) {
    validateAuthType(request.authType());
    validateNameNotDuplicated(request.name());
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

    return reader.getById(id);
  }

  @Transactional(readOnly = true)
  public List<ApiConnectionResponse> getAll() {
    return repository.findAll().stream().map(reader::toResponse).toList();
  }

  /** 단건 조회. 실제 구현은 {@link ApiConnectionReader} 에 있고 여기서는 기존 호출자를 위해 위임만 한다. */
  @Transactional(readOnly = true)
  public ApiConnectionResponse getById(Long id) {
    return reader.getById(id);
  }

  /** 복호화된 authConfig 조회. 구현은 {@link ApiConnectionReader} 에 있고 여기서는 위임만 한다. */
  @Transactional(readOnly = true)
  public Map<String, String> getDecryptedAuthConfig(Long id) {
    return reader.getDecryptedAuthConfig(id);
  }

  @Transactional
  public ApiConnectionResponse update(Long id, UpdateApiConnectionRequest request) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));

    // 이름을 변경하는 경우에만 중복 검사 — 자기 자신은 제외 (#647)
    if (request.name() != null && !request.name().isBlank()) {
      if (repository.existsByNameExcludingId(request.name(), id)) {
        throw new ApiConnectionNameAlreadyExistsException(request.name());
      }
    }

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

    return reader.getById(id);
  }

  @Transactional
  public void delete(Long id) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    repository.deleteById(id);
  }

  /**
   * 이 API 연결을 참조하는 파이프라인을 집계한다. 삭제 전 영향 범위 확인용(#605).
   *
   * <p>{@code pipeline_step.api_connection_id}는 FK(ON DELETE 절 없음 → 기본 RESTRICT)로 실제 삭제 시 참조가 있으면
   * 409로 거부되지만, 그 전에는 확인할 방법이 없어 사전 고지가 정적 문구로만 이루어지던 문제(dataset-manager의 {@code
   * get_dataset_references}와 동일 패턴)를 해결한다.
   */
  @Transactional(readOnly = true)
  public ApiConnectionReferencesResponse getReferences(Long id) {
    repository
        .findById(id)
        .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));

    List<ApiConnectionReferencesResponse.ReferenceItem> pipelines =
        repository.findReferencingPipelines(id).stream()
            .map(
                r ->
                    new ApiConnectionReferencesResponse.ReferenceItem(
                        r.get(field(name("pipeline", "id"), Long.class)),
                        r.get(field(name("pipeline", "name"), String.class))))
            .toList();

    return new ApiConnectionReferencesResponse(id, pipelines, pipelines.size());
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
   *
   * <p><b>이 메서드에 {@code @Transactional} 을 붙이면 안 된다.</b> 붙이는 순간 최대 5초짜리 외부 HTTP 호출이 트랜잭션(=DB 커넥션)
   * 안으로 들어와 커넥션 풀을 점유한다. 대신 조회는 별도 빈인 {@link ApiConnectionReader} 를 통해 수행해 프록시를 실제로 통과시킨다(자기호출이면
   * 프록시가 우회돼 트랜잭션이 열리지 않는다). DB 쓰기는 {@code repository.updateHealthStatus} 가 리포지토리 자신의 트랜잭션을 열어
   * 처리하므로, HTTP 구간은 어떤 트랜잭션 안에도 들어가지 않는다.
   *
   * <p>화면의 「테스트」뿐 아니라 10분 주기 상태 점검({@link ApiConnectionHealthCheckScheduler})과 일괄 새로고침({@link
   * #refreshAllAsync})도 이 메서드를 부른다. 호출 직전 주소 검사는 {@link #callHealthCheck} 가 맡는다(WD-178).
   */
  public TestConnectionResponse testConnection(Long id) {
    // ── 여기까지가 트랜잭션 구간(각 호출이 reader 프록시에서 자체 트랜잭션을 연다) ──
    ApiConnectionResponse conn = reader.getById(id);
    Map<String, String> rawConfig = reader.getDecryptedAuthConfig(id);
    // ── 아래 HTTP 호출은 트랜잭션 밖이다 ──
    TestConnectionResponse result =
        callHealthCheck(
            UrlUtils.joinUrl(conn.baseUrl(), conn.healthCheckPath()), conn.authType(), rawConfig);
    // 성공·HTTP 오류·연결 실패·주소 차단 모두 같은 규칙으로 저장한다 — 상태 전환 알림은 스케줄러가 이 결과의 ok 로 판단한다.
    repository.updateHealthStatus(
        id, result.ok() ? "UP" : "DOWN", result.latencyMs(), result.errorMessage());
    return result;
  }

  /**
   * (#90) 저장 전 dry-run 헬스체크. CreateApiConnectionRequest의 payload를 가지고 외부 API 호출만 수행하고 DB는 변경하지 않는다.
   * authType 검증/baseUrl SSRF 검증은 동일하게 수행하므로 잘못된 입력은 즉시 ApiConnectionException으로 차단된다.
   */
  public TestConnectionResponse testConnectionPayload(CreateApiConnectionRequest request) {
    validateAuthType(request.authType());
    String normalizedBaseUrl = validateAndNormalizeBaseUrl(request.baseUrl());
    String normalizedPath = normalizeHealthCheckPath(request.healthCheckPath());
    Map<String, String> rawConfig = request.authConfig() != null ? request.authConfig() : Map.of();
    return callHealthCheck(
        UrlUtils.joinUrl(normalizedBaseUrl, normalizedPath), request.authType(), rawConfig);
  }

  /**
   * 헬스체크 GET 한 번을 보내고 결과를 만든다 — 저장된 연결 테스트·저장 전 테스트·상태 점검·일괄 새로고침이 모두 여기로 모인다. DB 는 건드리지 않는다(저장은
   * 호출자 몫).
   *
   * <p><b>호출 직전 주소 검사(WD-178).</b> 저장할 때 막는 내부 주소라도, 이미 저장된 연결은 그 뒤에 내부를 가리키게 될 수 있다 — 차단 규칙이 강화되기
   * 전에 저장된 연결(WD-148 IPv6 ULA 등), DB 직접 변경, DNS 재바인딩. 예전에는 여기서 주소를 다시 보지 않아 서버가 내부 주소로 인증 헤더까지 실어
   * 요청하고 그 응답 본문을 결과로 보여 줬다. 같은 연결로 하는 API_CALL 미리보기·실행은 호출 시점에 막혀 수준이 달랐다. 그래서 API_CALL 과 같은
   * {@link SsrfProtectionService#validateUrl} 로 같은 판정을 한다.
   *
   * <p>검사 대상은 인증 쿼리 키를 붙이기 <b>전</b> URL(기준 URL + 헬스체크 경로)이다. 키는 쿼리에만 붙어 호스트를 바꾸지 못하고, API_CALL 도
   * 쿼리를 붙이기 전 URL 을 검사한다. 키 붙은 URL 을 넘기면 검사 실패 문구에 키가 실릴 위험만 커진다.
   *
   * <p>리다이렉트: 이 WebClient 는 Reactor Netty 기본값(리다이렉트를 따라가지 않음)이라 3xx 응답을 그대로 결과로 돌려줄 뿐 다른 주소로 다시
   * 요청하지 않는다. DNS: API_CALL 과 마찬가지로 검사 때 해석한 IP 로 고정하지는 않는다(검사 후 WebClient 가 다시 해석).
   *
   * @param targetUrl 인증 쿼리를 붙이기 전 호출 URL(기준 URL + 헬스체크 경로)
   */
  private TestConnectionResponse callHealthCheck(
      String targetUrl, String authType, Map<String, String> rawConfig) {
    // 응답의 requestUrl 은 인증 쿼리 키를 가린 표시용 URL 이다(WD-177). URL 을 만들기 전에 실패하면 키 없는 기준 URL 을 보인다.
    String displayUrl = targetUrl;

    long start = System.currentTimeMillis();
    try {
      // 호출 직전 주소 검사(WD-178) — 내부망·로컬이면 SsrfException 으로 아래에서 DOWN 결과가 되고, 외부 호출은 한 건도 나가지 않는다.
      ssrfProtectionService.validateUrl(targetUrl);

      // placement=query 인 경우 URL 에 인증 파라미터 부착 (#113). 호출용·표시용을 함께 만든다(WD-177) — try 안에서 만들어,
      // 키에 쓸 수 없는 문자가 있어 실패해도 400 응답에 키가 실리지 않고 DOWN 결과(고정 문구)로 돌아가게 한다.
      AuthQueryMasker.AuthQueryUrls urls =
          AuthQueryMasker.buildForAuth(targetUrl, authType, rawConfig);
      displayUrl = urls.displayUrl();
      String url = urls.callUrl();
      // 응답 본문/헤더를 함께 받아 디버깅용으로 노출 (#76).
      var resp =
          webClient
              .get()
              .uri(url)
              .headers(h -> buildAuthHeaders(authType, rawConfig).forEach(h::set))
              .retrieve()
              .toEntity(String.class)
              .block(Duration.ofSeconds(HEALTH_CHECK_TIMEOUT_SECONDS));

      long latency = System.currentTimeMillis() - start;
      Integer status = resp != null ? resp.getStatusCode().value() : null;
      boolean ok = resp != null && resp.getStatusCode().is2xxSuccessful();
      String err = ok ? null : ("HTTP " + status);
      String body = resp != null ? truncateBody(resp.getBody(), rawConfig) : null;
      Map<String, String> headers =
          resp != null ? sanitizeHeaders(resp.getHeaders(), rawConfig) : Map.of();
      String contentType =
          resp != null && resp.getHeaders().getContentType() != null
              ? resp.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(
          ok, status, latency, err, displayUrl, body, headers, contentType);

    } catch (SsrfException e) {
      long latency = System.currentTimeMillis() - start;
      // 차단 사유는 SsrfProtectionService 의 한국어 문구를 그대로 쓴다(「내부망 주소는 호출할 수 없습니다(루프백): 127.0.0.1」) —
      // 저장 시 검사·API_CALL 실행·미리보기(ApiCallErrorMessages.describe)와 같은 문장이다(WD-150). 호출하지 않았으므로 본문·헤더는
      // 비운다.
      return new TestConnectionResponse(
          false, null, latency, e.getMessage(), displayUrl, null, Map.of(), null);

    } catch (WebClientResponseException e) {
      long latency = System.currentTimeMillis() - start;
      String err = "HTTP " + e.getStatusCode().value();
      // 4xx/5xx 응답 본문도 운영자가 봐야 디버깅 가능하므로 함께 노출.
      String body = truncateBody(e.getResponseBodyAsString(), rawConfig);
      Map<String, String> headers = sanitizeHeaders(e.getHeaders(), rawConfig);
      String contentType =
          e.getHeaders().getContentType() != null
              ? e.getHeaders().getContentType().toString()
              : null;
      return new TestConnectionResponse(
          false, e.getStatusCode().value(), latency, err, displayUrl, body, headers, contentType);

    } catch (Exception e) {
      long latency = System.currentTimeMillis() - start;
      // 예외 메시지에 URL·키가 실릴 수 있어 가린 문구만 응답·lastErrorMessage·알림으로 보낸다(WD-177).
      String msg = toUserFacingErrorMessage(e, rawConfig);
      return new TestConnectionResponse(
          false, null, latency, msg, displayUrl, null, Map.of(), null);
    }
  }

  /**
   * (#532) 헬스체크 catch-all 예외를 감사 로그·UI에 노출 가능한 메시지로 변환한다.
   *
   * <p>Reactor의 {@code Mono#block(Duration)}은 타임아웃 시 "Timeout on blocking read for 5000000000
   * NANOSECONDS" 같은 저수준 구현 디테일을 담은 {@link IllegalStateException}을 던진다. 관리자가 나노초 단위를 초로 환산해야만 의미를
   * 파악할 수 있으므로, 타임아웃으로 판별되면 사람이 읽을 수 있는 고정 문구로 치환한다. 그 외 예외는 원래 메시지를 그대로 사용하되, 메시지가 없는
   * 경우(getMessage()==null) 클래스 simple name으로 대체한다.
   *
   * <p>(WD-177) 원문 메시지는 응답·{@code lastErrorMessage}·상태 알림으로 나가므로, 메시지 안 URL 의 쿼리·userinfo 를 지우고 인증
   * 쿼리 키(원문·인코딩형)를 {@code ****XXXX} 로 가린다. API_KEY 를 쿼리로 보내는 연결에서 예외 메시지에 호출 URL 이 실리면 키가 그대로 남기
   * 때문이다.
   */
  private static String toUserFacingErrorMessage(Exception e, Map<String, String> authConfig) {
    if (isTimeoutException(e)) {
      return "응답 시간 초과 (" + HEALTH_CHECK_TIMEOUT_SECONDS + "초)";
    }
    String raw = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    String redacted = ApiCallErrorMessages.redactUrlsInText(raw);
    return AuthQueryMasker.scrubSecret(redacted, authSecretOf(authConfig));
  }

  /**
   * 예외가 {@code .block(Duration.ofSeconds(5))} 타임아웃으로 인한 것인지 판별한다. Reactor-core는 전용 예외 타입 대신 {@link
   * IllegalStateException}에 "Timeout on blocking read" 접두 메시지를 담아 던지므로 메시지 패턴으로 판별한다. Netty의 {@code
   * ReadTimeoutException}은 클래스명 기준으로 판별하되, WebClient가 이를 {@code WebClientRequestException}으로 감싸 던지는
   * 경우가 있어 원인 체인(getCause)까지 한 단계 확인한다.
   */
  private static boolean isTimeoutException(Exception e) {
    for (Throwable t = e; t != null; t = t.getCause()) {
      String msg = t.getMessage();
      if (msg != null && msg.startsWith("Timeout on blocking read")) {
        return true;
      }
      if (t.getClass().getSimpleName().contains("TimeoutException")) {
        return true;
      }
      if (t == t.getCause()) break; // 자기참조 순환 방지
    }
    return false;
  }

  /** 응답 본문을 최대 4KB로 잘라 반환 (UI 노출용). null/빈값은 그대로 반환. */
  private static final int MAX_BODY_PREVIEW_BYTES = 4096;

  /**
   * 응답 본문 미리보기. (WD-177) 오류 본문이 요청 파라미터를 되울려 쿼리 인증 키가 담겨 올 수 있어 키(원문·인코딩형)를 먼저 가린 뒤 자른다 — 자른 뒤 가리면
   * 경계에 걸친 키 앞부분이 남는다.
   */
  private static String truncateBody(String body, Map<String, String> authConfig) {
    if (body == null || body.isEmpty()) return body;
    String masked = AuthQueryMasker.scrubSecret(body, authSecretOf(authConfig));
    if (masked.length() <= MAX_BODY_PREVIEW_BYTES) return masked;
    return masked.substring(0, MAX_BODY_PREVIEW_BYTES) + "\n... (truncated)";
  }

  /** 응답·오류 문구에서 가릴 인증 키(API_KEY 의 apiKey). 없으면 null. */
  private static String authSecretOf(Map<String, String> authConfig) {
    return authConfig != null ? authConfig.get("apiKey") : null;
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

  /**
   * 응답 헤더를 UI 노출용으로 옮긴다. 민감 헤더는 통째로 가리고, (WD-177) 나머지 헤더 값에서도 쿼리 인증 키를 가린다 — WebClient 가 리다이렉트를
   * 따라가지 않아 3xx 의 {@code Location: https://…?serviceKey=<키>} 가 그대로 결과에 실리기 때문이다.
   */
  private static Map<String, String> sanitizeHeaders(
      org.springframework.http.HttpHeaders headers, Map<String, String> authConfig) {
    String secret = authSecretOf(authConfig);
    Map<String, String> out = new HashMap<>();
    headers.forEach(
        (k, v) -> {
          String joined = String.join(", ", v);
          if (SENSITIVE_HEADER_NAMES.contains(k.toLowerCase())) {
            out.put(k, "****");
          } else {
            out.put(k, AuthQueryMasker.scrubSecret(joined, secret));
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
      // SsrfProtectionService 문구가 이미 원인을 담은 한국어 안내다(WD-150, 「내부망 주소는 호출할 수 없습니다(루프백): …」) —
      // 예전 「SSRF 보호: 」 접두는 영어 원문을 감싸던 용도라 사용자에게는 뜻 모를 용어만 더해서 뺀다.
      throw new ApiConnectionException(e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ApiConnectionException("유효하지 않은 baseUrl: " + e.getMessage());
    }
  }

  /**
   * authType과 authConfig를 기반으로 HTTP 인증 헤더를 생성한다. API_KEY + placement=query 인 경우는 URL query param 으로
   * 전송해야 하므로 헤더에 포함하지 않는다(AuthQueryMasker.buildForAuth 가 처리). (#113)
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
   * 동일 테넌트 내 이름 중복을 막는다 (#647). {@code api_connection.name}에는 유니크 제약이 없어(V93 주석 참고) 같은 이름의 연결이 여러 개
   * 생성될 수 있었고, 목록/삭제 확인 다이얼로그가 이름만으로 항목을 구분해 오삭제 위험이 있었다. RLS 정책이 테넌트 경계를 자동으로 걸어주므로 여기서는 이름만 검사하면
   * 된다.
   */
  private void validateNameNotDuplicated(String name) {
    if (repository.existsByName(name)) {
      throw new ApiConnectionNameAlreadyExistsException(name);
    }
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

  private String fetchAuthType(Long id) {
    Record record =
        repository
            .findById(id)
            .orElseThrow(() -> new ApiConnectionException("ApiConnection not found: " + id));
    return record.get(field(name("api_connection", "auth_type"), String.class));
  }
}
