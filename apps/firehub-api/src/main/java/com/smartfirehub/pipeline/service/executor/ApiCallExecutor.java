package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.global.exception.ExternalServiceException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;

/**
 * @deprecated Use {@link ExecutorClient#executeApiCall} instead. Retained for
 *     ApiCallPreviewService.
 */
@Deprecated
@Slf4j
@Service
@RequiredArgsConstructor
public class ApiCallExecutor {

  private static final int DEFAULT_TIMEOUT_MS = 30_000;
  private static final int DEFAULT_MAX_DURATION_MS = 3_600_000;
  private static final int DEFAULT_MAX_RETRIES = 3;
  private static final int DEFAULT_INITIAL_BACKOFF = 1_000;
  private static final int DEFAULT_MAX_BACKOFF = 30_000;
  private static final int DEFAULT_PAGE_SIZE = 100;
  private static final int DEFAULT_MAX_RESPONSE_SIZE_MB = 10;
  private static final int MAX_REDIRECTS = 5;

  private final SsrfProtectionService ssrfProtectionService;
  private final JsonResponseParser jsonResponseParser;
  private final OffsetPaginationHandler offsetPaginationHandler;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final WebClient.Builder webClientBuilder;

  // -------------------------------------------------------------------------
  // Result type
  // -------------------------------------------------------------------------

  public record ApiCallResult(int totalRows, String log) {}

  // -------------------------------------------------------------------------
  // Public entry point
  // -------------------------------------------------------------------------

  /**
   * Executes an API_CALL pipeline step.
   *
   * @param config parsed step configuration
   * @param outputTableName target data-schema table name
   * @param decryptedAuthConfig auth key/value pairs (already decrypted), may be null
   * @param loadStrategy "APPEND" or "REPLACE" (REPLACE truncates first)
   * @param conn API 연결 엔티티 (apiConnectionId 설정 시 baseUrl 제공용). null이면 customUrl 또는 레거시 url 사용.
   * @return result with total rows inserted and an execution log string
   */
  public ApiCallResult execute(
      ApiCallConfig config,
      String outputTableName,
      Map<String, String> decryptedAuthConfig,
      String loadStrategy,
      Map<String, String> columnTypeMap,
      ApiConnectionResponse conn) {
    return execute(
        config,
        outputTableName,
        decryptedAuthConfig,
        loadStrategy,
        columnTypeMap,
        conn,
        ExecutionCancelCheck.NONE);
  }

  /**
   * 파이프라인 러너용 — 협조적 취소 확인({@code cancel})을 받는다(WD-153).
   *
   * <p>체크포인트: 각 페이지 요청 직전(= 페이지 사이 — 진행 중인 페이지는 끝까지 받고 적재한 뒤 멈춘다), 재시도 백오프 대기 중({@link
   * ExecutionCancelCheck#SLEEP_SLICE_MS} 마다 깨어 확인 — 대기 시간을 다 채우지 않는다), REPLACE 맞바꿈 직전. 모두 아래 try 안이라 취소
   * 예외가 실패와 같은 정리 경로를 타 REPLACE 임시 테이블이 지워지고 원본이 남는다. APPEND 는 이미 넣은 행이 남고 그 수를 예외에 싣는다.
   */
  public ApiCallResult execute(
      ApiCallConfig config,
      String outputTableName,
      Map<String, String> decryptedAuthConfig,
      String loadStrategy,
      Map<String, String> columnTypeMap,
      ApiConnectionResponse conn,
      ExecutionCancelCheck cancel) {

    // 1. URL 결정: apiConnectionId → baseUrl+path, customUrl, 또는 레거시 url 순으로 해석
    String resolvedUrl = resolveTargetUrl(config, conn);

    // 2. SSRF guard on the initial URL
    ssrfProtectionService.validateUrl(resolvedUrl);

    // 2. Apply load strategy — for REPLACE create a temp table and insert there;
    //    swap into place only after all pages succeed.
    boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy);
    boolean swapped = false;

    // REPLACE 면 이번 실행 전용 임시 테이블을 만든다(WD-151) — 이름이 실행마다 달라, 같은 출력에 겹친 실행과 생성·적재·정리가 서로 닿지 않는다.
    // 생성은 아래 try 밖이다: 생성이 실패하면 정리할 이름이 없다.
    String tempTable = isReplace ? dataTableService.createTempTable(outputTableName) : null;

    // The actual insert target: temp table for REPLACE, original for APPEND
    String insertTarget = isReplace ? tempTable : outputTableName;

    long startTime = System.currentTimeMillis();
    int maxDurationMs =
        config.maxDurationMs() != null ? config.maxDurationMs() : DEFAULT_MAX_DURATION_MS;
    int timeoutMs = config.timeoutMs() != null ? config.timeoutMs() : DEFAULT_TIMEOUT_MS;

    int totalRows = 0;
    int totalPages = 0;
    StringBuilder executionLog = new StringBuilder();

    try {
      // 3. Determine pagination mode
      String paginationType = (config.pagination() != null) ? config.pagination().type() : "NONE";

      if ("OFFSET".equalsIgnoreCase(paginationType)) {
        // Offset pagination loop
        ApiCallConfig.PaginationConfig pag = config.pagination();
        int pageSize = (pag.pageSize() != null) ? pag.pageSize() : DEFAULT_PAGE_SIZE;
        int offset = 0;
        Integer totalCount = null;

        while (true) {
          // 취소 체크포인트(WD-153) — 다음 페이지를 요청하기 전. APPEND 면 지금까지 넣은 행이 남는다.
          cancel.checkpoint(isReplace ? 0 : totalRows, isReplace);

          // Build pagination params
          Map<String, String> pagParams =
              offsetPaginationHandler.buildPaginationParams(
                  pag.offsetParam(), pag.limitParam(), offset, pageSize);

          // Fetch page
          String responseBody =
              executeRequest(
                  config,
                  resolvedUrl,
                  decryptedAuthConfig,
                  pagParams,
                  timeoutMs,
                  cancel,
                  isReplace ? 0 : totalRows,
                  isReplace);

          // Parse data
          List<Map<String, Object>> rows =
              jsonResponseParser.parseAndMap(
                  responseBody, config.dataPath(), config.fieldMappings(), columnTypeMap);

          // Extract totalCount from response if configured (only needed on first page)
          if (totalCount == null && pag.totalPath() != null && !pag.totalPath().isBlank()) {
            try {
              Object raw = jsonResponseParser.readPath(responseBody, pag.totalPath(), Object.class);
              if (raw instanceof Number n) {
                totalCount = n.intValue();
              }
            } catch (ApiCallException e) {
              log.debug(
                  "totalPath '{}' not found in response — using partial-page stop condition",
                  pag.totalPath());
            }
          }

          totalPages++;
          log.debug("Fetched page offset={} rows={} totalSoFar={}", offset, rows.size(), totalRows);

          // 빈 페이지이면 더 이상 진행하지 않는다 — 오프셋 초과 요청 차단 (일부 외부 API는 빈 오프셋에 400 반환)
          if (rows.isEmpty()) {
            break;
          }

          // Insert batch into the target table (tmp for REPLACE, original for APPEND)
          List<String> columns = extractColumns(rows);
          dataTableRowService.insertBatch(insertTarget, columns, rows);
          totalRows += rows.size();

          // Check duration
          long elapsed = System.currentTimeMillis() - startTime;
          if (elapsed >= maxDurationMs) {
            executionLog
                .append("[WARN] maxDurationMs (")
                .append(maxDurationMs)
                .append(" ms) exceeded after ")
                .append(totalPages)
                .append(" pages. Partial result.\n");
            break;
          }

          // Check for next page
          if (!offsetPaginationHandler.hasNextPage(offset, pageSize, totalCount, rows.size())) {
            break;
          }

          offset = offsetPaginationHandler.getNextOffset(offset, pageSize);
        }

      } else {
        // Single request (no pagination)
        // 취소 체크포인트(WD-153) — 요청 전.
        cancel.checkpoint(0, isReplace);
        String responseBody =
            executeRequest(
                config, resolvedUrl, decryptedAuthConfig, Map.of(), timeoutMs, cancel, 0, isReplace);
        List<Map<String, Object>> rows =
            jsonResponseParser.parseAndMap(
                responseBody, config.dataPath(), config.fieldMappings(), columnTypeMap);

        if (!rows.isEmpty()) {
          List<String> columns = extractColumns(rows);
          dataTableRowService.insertBatch(insertTarget, columns, rows);
          totalRows += rows.size();
        }
        totalPages = 1;
      }

      // 4. All pages succeeded — swap tmp -> original for REPLACE.
      // 적재된 행이 0이면 맞바꾸지 않는다(#685) — API 가 빈 페이지를 돌려줬다고 해서 기존 데이터를
      // 지울 이유는 없다. 판단은 finishReplace 가 단독으로 갖는다.
      if (isReplace) {
        // 취소 체크포인트(WD-153) — 맞바꾸기 직전. 마지막 페이지를 받는 동안 취소됐으면 임시 테이블을 버리고(아래 catch) 원본을 지킨다.
        cancel.checkpoint(0, true);
        dataTableService.finishReplace(outputTableName, tempTable, totalRows);
        swapped = true;
      }

    } catch (Exception e) {
      // On failure, clean up the temp table so original data is preserved
      if (isReplace && !swapped) {
        try {
          dataTableService.dropTempTable(outputTableName, tempTable);
        } catch (Exception dropEx) {
          log.warn("Failed to drop temp table after API call failure: {}", dropEx.getMessage());
        }
      }
      throw e;
    }

    long durationMs = System.currentTimeMillis() - startTime;

    executionLog.insert(
        0,
        "url="
            + resolvedUrl
            + " method="
            + config.method()
            + " pages="
            + totalPages
            + " rows="
            + totalRows
            + " duration="
            + durationMs
            + "ms\n");

    return new ApiCallResult(totalRows, executionLog.toString());
  }

  // -------------------------------------------------------------------------
  // HTTP execution with retry
  // -------------------------------------------------------------------------

  /**
   * 요청 한 번을 재시도 정책대로 보낸다. 재시도 사이 백오프는 {@code cancel} 이 조각내 자면서 취소를 확인한다(WD-153) — 취소되면 남은 재시도 없이
   * {@code PipelineExecutionCancelledException} 이 나간다(rowsKept·replaceDiscarded 는 그 예외 문구용).
   */
  private String executeRequest(
      ApiCallConfig config,
      String resolvedUrl,
      Map<String, String> decryptedAuthConfig,
      Map<String, String> paginationParams,
      int timeoutMs,
      ExecutionCancelCheck cancel,
      long rowsKept,
      boolean replaceDiscarded) {

    int maxRetries = DEFAULT_MAX_RETRIES;
    int initialBackoff = DEFAULT_INITIAL_BACKOFF;
    int maxBackoff = DEFAULT_MAX_BACKOFF;

    if (config.retry() != null) {
      if (config.retry().maxRetries() != null) maxRetries = config.retry().maxRetries();
      if (config.retry().initialBackoffMs() != null)
        initialBackoff = config.retry().initialBackoffMs();
      if (config.retry().maxBackoffMs() != null) maxBackoff = config.retry().maxBackoffMs();
    }

    int attempt = 0;
    int backoffMs = initialBackoff;
    Exception lastException = null;

    while (attempt <= maxRetries) {
      try {
        return doHttpRequest(config, resolvedUrl, decryptedAuthConfig, paginationParams, timeoutMs);
      } catch (ApiCallException | SsrfException e) {
        // 재시도 안 함: 고쳐지지 않는 4xx·응답 크기 초과 등(ApiCallException)과 리다이렉트 대상이 내부망인 경우(SsrfException).
        // 예전에는 SsrfException 이 아래 catch 로 떨어져 같은 차단을 N회 재시도하고 원인까지 묻혔다(WD-150).
        throw e;
      } catch (Exception e) {
        lastException = e;
        attempt++;
        if (attempt > maxRetries) {
          break;
        }
        int jitter = (int) (Math.random() * 1000);
        int sleepMs = Math.min(backoffMs + jitter, maxBackoff);
        log.warn(
            "Request failed (attempt {}/{}), retrying in {} ms: {}",
            attempt,
            maxRetries,
            sleepMs,
            e.getMessage());
        try {
          // 취소를 확인하며 잔다 — 예전 Thread.sleep 한 번은 취소해도 백오프(최대 maxBackoff)를 다 채웠다.
          cancel.sleep(sleepMs, rowsKept, replaceDiscarded);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new ApiCallException(ApiCallErrorMessages.interrupted(), ie);
        }
        backoffMs = Math.min(backoffMs * 2, maxBackoff);
      }
    }

    // 마지막 실패 원인을 문구에 담는다(WD-150) — 예전에는 원인이 cause 에만 있고 러너는 getMessage() 만 저장해
    // 타임아웃·연결 거부가 「API call failed after N retries」로만 보였다. lastException 은 doHttpRequest 가
    // 이미 사용자 문구(ApiCallErrorMessages)로 만든 예외다.
    throw new ApiCallException(
        ApiCallErrorMessages.retriesExhausted(
            maxRetries, lastException != null ? lastException.getMessage() : ""),
        lastException);
  }

  private String doHttpRequest(
      ApiCallConfig config,
      String resolvedUrl,
      Map<String, String> decryptedAuthConfig,
      Map<String, String> paginationParams,
      int timeoutMs) {

    // Merge static query params + pagination params
    Map<String, String> allQueryParams = new LinkedHashMap<>();
    if (config.queryParams() != null) {
      allQueryParams.putAll(config.queryParams());
    }
    if (paginationParams != null) {
      allQueryParams.putAll(paginationParams);
    }

    // Auth: API_KEY with query param goes into queryParams
    if (decryptedAuthConfig != null) {
      String authType = decryptedAuthConfig.get("authType");
      if ("API_KEY".equals(authType) && "query".equals(decryptedAuthConfig.get("placement"))) {
        String paramName = decryptedAuthConfig.get("paramName");
        String apiKey = decryptedAuthConfig.get("apiKey");
        if (paramName != null && apiKey != null) {
          allQueryParams.put(paramName, apiKey);
        }
      }
    }

    // Build the full URI with query params (resolvedUrl은 이미 SSRF 검증 완료)
    UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(resolvedUrl);
    allQueryParams.forEach(uriBuilder::queryParam);
    URI requestUri = uriBuilder.build(true).toUri();

    // Fix 1: enforce maxResponseSizeMb via ExchangeStrategies codec limit.
    // If the response body exceeds this limit WebClient throws DataBufferLimitException,
    // which is caught below and wrapped as a non-retryable ApiCallException.
    int maxResponseSizeMb =
        (config.maxResponseSizeMb() != null)
            ? config.maxResponseSizeMb()
            : DEFAULT_MAX_RESPONSE_SIZE_MB;
    ExchangeStrategies exchangeStrategies =
        ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseSizeMb * 1024 * 1024))
            .build();

    // Fix 2: disable automatic redirects so every hop is SSRF-validated before following.
    // Without this, a malicious server could 301-redirect to http://169.254.169.254/ and
    // bypass the initial validateUrl() check.
    HttpClient reactorHttpClient = HttpClient.create().followRedirect(false);

    WebClient client =
        webClientBuilder
            .clientConnector(new ReactorClientHttpConnector(reactorHttpClient))
            .exchangeStrategies(exchangeStrategies)
            .build();

    String method = (config.method() != null) ? config.method().toUpperCase() : "GET";

    try {
      return executeWithRedirects(
          client, method, requestUri, config, resolvedUrl, decryptedAuthConfig, timeoutMs, 0);
    } catch (DataBufferLimitException e) {
      throw new ApiCallException(ApiCallErrorMessages.responseTooLarge(maxResponseSizeMb), e);
    } catch (ApiCallException | SsrfException | ExternalServiceException e) {
      // 이미 사용자 문구를 담은 예외 — ExternalServiceException 은 재시도할 HTTP 상태(5xx·408·429)다.
      throw e;
    } catch (Exception e) {
      // 타임아웃·연결 거부 등 — 재시도 대상. 원인을 분류한 한국어 문구로 감싼다(WD-150). 예전 문구는 쿼리 포함
      // URL 을 담을 수 있어(customUrl 에 serviceKey 등) 원인을 묻고 키까지 남겼다.
      throw new ExternalServiceException(
          ApiCallErrorMessages.describe(e, timeoutMs, requestUri.getHost()), e);
    }
  }

  /**
   * Executes a single HTTP request hop and manually follows redirects after SSRF-validating each
   * Location URL. This prevents open-redirect SSRF attacks where an attacker's server issues a
   * 301/302 to an internal address (e.g. http://169.254.169.254/).
   *
   * <p>Redirect following uses GET semantics (standard browser behaviour for 301/302/303). POST
   * bodies are only sent on the initial request; redirects always use GET.
   *
   * @param redirectCount number of redirects followed so far (guards against infinite loops)
   */
  private String executeWithRedirects(
      WebClient client,
      String method,
      URI requestUri,
      ApiCallConfig config,
      String resolvedUrl,
      Map<String, String> decryptedAuthConfig,
      int timeoutMs,
      int redirectCount) {

    if (redirectCount > MAX_REDIRECTS) {
      throw new ApiCallException(ApiCallErrorMessages.tooManyRedirects(MAX_REDIRECTS));
    }

    WebClient.RequestHeadersSpec<?> requestSpec =
        buildRequestSpec(client, method, requestUri, config, decryptedAuthConfig);

    // Use exchangeToMono so we can inspect both status and headers before consuming the body.
    // We capture the Location header via an AtomicReference because the reactive callback
    // cannot return two values at once.
    AtomicReference<String> locationRef = new AtomicReference<>();

    String body =
        requestSpec
            .exchangeToMono(
                response -> {
                  HttpStatusCode status = response.statusCode();

                  if (status.is3xxRedirection()) {
                    // Capture Location header, drain body without buffering, signal redirect with
                    // null
                    String location = response.headers().asHttpHeaders().getFirst("Location");
                    locationRef.set(location);
                    return response
                        .releaseBody()
                        .thenReturn(""); // sentinel: empty string + locationRef set
                  }

                  if (status.is4xxClientError() || status.is5xxServerError()) {
                    // 상태별 한국어 안내(WD-150). 5xx·408·429 만 재시도(ExternalServiceException), 나머지 4xx 는
                    // 다시 보내도 같은 답이라 즉시 실패(ApiCallException) — 예전에는 400/401/403/404 만 즉시 실패라
                    // 405 같은 오류도 재시도하며 오래 걸렸다.
                    // 문구에 요청 URL 을 넣지 않는다 — requestUri 에는 query 로 붙인 API 키가 들어 있다.
                    // 본문은 읽지 않고 버린다 — 큰 오류 본문이 응답 크기 제한에 걸려 「응답 크기 초과」로 잘못 보이지 않게.
                    int code = status.value();
                    String message = ApiCallErrorMessages.httpStatus(code);
                    RuntimeException failure =
                        ApiCallErrorMessages.isRetryableStatus(code)
                            ? new ExternalServiceException(message)
                            : new ApiCallException(message);
                    return response
                        .releaseBody()
                        .then(reactor.core.publisher.Mono.<String>error(failure));
                  }

                  // 2xx — read the response body (size enforced by ExchangeStrategies codec limit)
                  return response.bodyToMono(String.class);
                })
            .timeout(Duration.ofMillis(timeoutMs))
            .block();

    // Handle redirect: locationRef is set only when a 3xx was received
    String location = locationRef.get();
    if (location != null) {
      log.debug(
          "Redirect {}/{}: {} -> {}",
          redirectCount + 1,
          MAX_REDIRECTS,
          ApiCallErrorMessages.redactUrl(requestUri),
          ApiCallErrorMessages.redactUrl(location));

      // SSRF-validate the redirect target before following it
      ssrfProtectionService.validateUrl(location);

      URI redirectUri = URI.create(location);
      // Redirects always use GET (standard 301/302/303 behaviour)
      return executeWithRedirects(
          client,
          "GET",
          redirectUri,
          config,
          resolvedUrl,
          decryptedAuthConfig,
          timeoutMs,
          redirectCount + 1);
    }

    return body;
  }

  /**
   * Builds a {@link WebClient.RequestHeadersSpec} for the given method and URI, applying configured
   * headers and auth.
   */
  private WebClient.RequestHeadersSpec<?> buildRequestSpec(
      WebClient client,
      String method,
      URI requestUri,
      ApiCallConfig config,
      Map<String, String> decryptedAuthConfig) {

    WebClient.RequestHeadersSpec<?> requestSpec;

    if ("POST".equals(method)) {
      WebClient.RequestBodySpec bodySpec = client.post().uri(requestUri);
      applyHeaders(bodySpec, config, decryptedAuthConfig);
      if (config.body() != null && !config.body().isBlank()) {
        requestSpec = bodySpec.header("Content-Type", "application/json").bodyValue(config.body());
      } else {
        requestSpec = bodySpec;
      }
    } else {
      // GET (default) and redirect follow-ups
      WebClient.RequestHeadersSpec<?> getSpec = client.get().uri(requestUri);
      applyHeaders(getSpec, config, decryptedAuthConfig);
      requestSpec = getSpec;
    }

    return requestSpec;
  }

  @SuppressWarnings("unchecked")
  private void applyHeaders(
      WebClient.RequestHeadersSpec<?> spec,
      ApiCallConfig config,
      Map<String, String> decryptedAuthConfig) {

    // Custom headers from config
    if (config.headers() != null) {
      config.headers().forEach(spec::header);
    }

    // Auth headers
    if (decryptedAuthConfig != null) {
      String authType = decryptedAuthConfig.get("authType");
      String placement = decryptedAuthConfig.getOrDefault("placement", "header");
      if ("API_KEY".equals(authType) && "header".equals(placement)) {
        String headerName = decryptedAuthConfig.get("headerName");
        String apiKey = decryptedAuthConfig.get("apiKey");
        if (headerName != null && apiKey != null) {
          spec.header(headerName, apiKey);
        }
      } else if ("BEARER".equals(authType)) {
        String token = decryptedAuthConfig.get("token");
        if (token != null) {
          spec.header("Authorization", "Bearer " + token);
        }
      }
    }
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  /**
   * 하위 호환용 오버로드 — conn 없이 호출하면 customUrl 또는 레거시 url 필드를 사용한다. ApiCallPreviewService 등 conn 정보가 없는
   * 호출부에서 사용.
   */
  public ApiCallResult execute(
      ApiCallConfig config,
      String outputTableName,
      Map<String, String> decryptedAuthConfig,
      String loadStrategy,
      Map<String, String> columnTypeMap) {
    return execute(config, outputTableName, decryptedAuthConfig, loadStrategy, columnTypeMap, null);
  }

  /**
   * API_CALL 스텝의 호출 대상 URL을 계산한다.
   *
   * <p>우선순위:
   *
   * <ol>
   *   <li>conn != null → conn.baseUrl() + config.path() (path 필수)
   *   <li>config.customUrl() → customUrl 그대로 사용
   *   <li>하위 호환: config.url() (deprecated, 이전 설정 호환)
   * </ol>
   *
   * @param config API_CALL 스텝 설정
   * @param conn API 연결 정보 (null이면 customUrl 또는 레거시 url 사용)
   * @throws ApiCallException 필수 파라미터 누락 시
   */
  private String resolveTargetUrl(ApiCallConfig config, ApiConnectionResponse conn) {
    if (conn != null) {
      // apiConnectionId 기반: connection.baseUrl + path
      String path = config.path();
      if (path == null || path.isBlank()) {
        throw new ApiCallException("API_CALL: apiConnectionId 설정 시 path가 필수입니다");
      }
      return UrlUtils.joinUrl(conn.baseUrl(), path);
    }

    // customUrl 우선 사용 (Phase 9 신규 필드)
    String customUrl = config.customUrl();
    if (customUrl != null && !customUrl.isBlank()) {
      return customUrl;
    }

    // 하위 호환: 레거시 url 필드
    String legacyUrl = config.url();
    if (legacyUrl != null && !legacyUrl.isBlank()) {
      return legacyUrl;
    }

    throw new ApiCallException("API_CALL: apiConnectionId 없이 호출하려면 customUrl(또는 레거시 url)이 필수입니다");
  }

  private List<String> extractColumns(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return List.of();
    return new ArrayList<>(rows.get(0).keySet());
  }
}
