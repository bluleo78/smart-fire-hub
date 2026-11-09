package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.global.exception.ExternalServiceException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

  /** 출력 테이블 쓰기 잠금(WD-666) — APPEND 페이지 쓰기와 REPLACE 맞바꿈을 데이터셋 행 잠금과 같은 트랜잭션에서 한다. */
  private final DatasetDataWriteLock writeLock;

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
   * ExecutionCancelCheck#SLEEP_SLICE_MS} 마다 깨어 확인 — 대기 시간을 다 채우지 않는다), REPLACE 맞바꿈 직전. 모두 아래 try
   * 안이라 취소 예외가 실패와 같은 정리 경로를 타 REPLACE 임시 테이블이 지워지고 원본이 남는다. APPEND 는 이미 넣은 행이 남고 그 수를 예외에 싣는다.
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

    // 인증 설정은 실행마다 한 번만 푼다(WD-647) — 페이지·재시도·리다이렉트마다 다시 풀지 않고 이 값을 요청 조립까지 넘긴다. 러너가 호출 전 검사에
    // 쓴 것과 같은 진입점(ApiCallAuth.resolve(conn, …))이라 검사한 값과 보내는 값이 같다(WD-657). 예전에는 conn 없이 풀어, 둘이 같은
    // 것은
    // 조회(ApiConnectionReader)가 Map 의 authType 을 컬럼으로 덮어 준 덕분일 뿐이었다.
    ApiCallAuth.ResolvedAuth auth = ApiCallAuth.resolve(conn, decryptedAuthConfig);

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
                  auth,
                  pagParams,
                  timeoutMs,
                  cancel,
                  isReplace ? 0 : totalRows,
                  isReplace);

          // Parse data — 변환 실패 문구의 행 번호가 페이지를 넘어 이어지도록 지금까지 받은 응답 원소 수를 넘긴다(WD-431). 이 경로는
          // 원소마다 한 행을 만들므로(객체가 아닌 원소도 모든 칸 NULL 행) totalRows 가 곧 원소 수다 — Python 실행기도 원소 수로 센다.
          List<Map<String, Object>> rows =
              jsonResponseParser.parseAndMap(
                  responseBody,
                  config.dataPath(),
                  config.fieldMappings(),
                  columnTypeMap,
                  totalRows);

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
          writeRows(isReplace, insertTarget, columns, rows);
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
            executeRequest(config, resolvedUrl, auth, Map.of(), timeoutMs, cancel, 0, isReplace);
        List<Map<String, Object>> rows =
            jsonResponseParser.parseAndMap(
                responseBody, config.dataPath(), config.fieldMappings(), columnTypeMap, 0);

        if (!rows.isEmpty()) {
          List<String> columns = extractColumns(rows);
          writeRows(isReplace, insertTarget, columns, rows);
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
        // WD-666: 맞바꿈은 출력 테이블을 바꾸는 쓰기다 — 데이터셋 행 잠금과 같은 트랜잭션에서 해 구조 변경·삭제와 줄 선다.
        final int rowsForSwap = totalRows;
        writeLock.writeToOutput(
            outputTableName,
            () -> dataTableService.finishReplace(outputTableName, tempTable, rowsForSwap));
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
      ApiCallAuth.ResolvedAuth auth,
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
        return doHttpRequest(config, resolvedUrl, auth, paginationParams, timeoutMs);
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
      ApiCallAuth.ResolvedAuth auth,
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

    // 오류 문구에서 가릴 값 — 쿼리 키뿐 아니라 실제로 보내는 비밀 전부다(헤더 키·Bearer 토큰 포함).
    String authSecret = auth.sentSecret();

    // Build the full URI with query params (resolvedUrl은 이미 SSRF 검증 완료)
    // 쿼리 인증 키(API_KEY + placement=query)는 execute 가 한 번 푼 값으로, 연결 테스트·미리보기와 같은 공용
    // 빌더(ResolvedAuth.buildUrls,
    // WD-657)가 붙인다. (WD-182) 키는 공용 규칙(QueryApiKeyEncoding)으로 한 번만 인코딩한다 — 인코딩 키·디코딩 키 모두 같은 바이트가
    // 나간다. (WD-177) 정적 쿼리 값에 쿼리에 쓸 수 없는 문자(=·공백·한글 등)가 있으면 Spring 이 원문을 담은 메시지로 실패하는데, 공용
    // 빌더가 원문 없는 고정 문구로 바꿔 주고, 다시 보내도 같으므로 재시도하지 않는 ApiCallException 으로 낸다.
    URI requestUri;
    try {
      requestUri = auth.buildUrls(resolvedUrl, allQueryParams).callUri();
    } catch (AuthQueryMasker.InvalidRequestUrlException e) {
      throw new ApiCallException(e.getMessage());
    }

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

    String method = (config.method() != null) ? config.method().toUpperCase(Locale.ROOT) : "GET";

    try {
      // 첫 요청의 출처가 리다이렉트마다 인증을 붙일지 정하는 기준이다(WD-657).
      RedirectContext ctx =
          new RedirectContext(client, requestUri, config, resolvedUrl, auth, timeoutMs);
      return executeWithRedirects(ctx, method, requestUri, 0, false);
    } catch (DataBufferLimitException e) {
      throw new ApiCallException(ApiCallErrorMessages.responseTooLarge(maxResponseSizeMb), e);
    } catch (ApiCallException | SsrfException | ExternalServiceException e) {
      // 이미 사용자 문구를 담은 예외 — ExternalServiceException 은 재시도할 HTTP 상태(5xx·408·429)다.
      throw e;
    } catch (Exception e) {
      // 타임아웃·연결 거부 등 — 재시도 대상. 원인을 분류한 한국어 문구로 감싼다(WD-150). 예전 문구는 쿼리 포함
      // URL 을 담을 수 있어(customUrl 에 serviceKey 등) 원인을 묻고 키까지 남겼다.
      // (WD-177) 분류하지 못한 원문 메시지에 쿼리 인증 키가 실려 올 수 있어 키도 가린다 — 이 문구는 재시도 로그·실행 기록으로 간다.
      throw new ExternalServiceException(
          AuthQueryMasker.scrubSecret(
              ApiCallErrorMessages.describe(e, timeoutMs, requestUri.getHost()), authSecret),
          e);
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
   * <p>인증은 처음 요청과 같은 출처(스킴·호스트·포트)로 가는 홉에만 붙인다(WD-657) — 다른 출처로 3xx 하면 인증 헤더·쿼리 인증 키를 떼고 따라간다. 판단
   * 기준은 언제나 처음 요청의 출처라, A→B→A 면 B 에는 떼고 다시 A 로 올 때는 붙인다. 리다이렉트 자체는 따른다(막으면 기존 파이프라인이 깨진다).
   *
   * @param ctx 홉마다 바뀌지 않는 값(클라이언트·처음 요청 URI·설정·인증·시간 제한)
   * @param redirectCount number of redirects followed so far (guards against infinite loops)
   * @param hasLeftOrigin 이 홉까지 오는 동안 리다이렉트가 처음 출처를 벗어난 적이 있는지 — 처음 출처로 돌아올 때 뗐던 쿼리 인증 키를 되붙이는 데 쓴다
   */
  private String executeWithRedirects(
      RedirectContext ctx,
      String method,
      URI requestUri,
      int redirectCount,
      boolean hasLeftOrigin) {
    WebClient client = ctx.client();
    URI originUri = ctx.originUri();
    ApiCallConfig config = ctx.config();
    ApiCallAuth.ResolvedAuth auth = ctx.auth();

    if (redirectCount > MAX_REDIRECTS) {
      throw new ApiCallException(ApiCallErrorMessages.tooManyRedirects(MAX_REDIRECTS));
    }

    // 이 홉이 처음 요청과 같은 출처일 때만 인증 헤더를 붙인다(WD-657). 첫 홉은 판정 없이 붙인다 — java.net.URI 가 호스트를 못 읽는 주소(밑줄 든
    // 호스트 이름 등)도 예전처럼 인증해 보내야 한다.
    boolean sendAuth = redirectCount == 0 || ApiCallAuth.isSameAuthOrigin(originUri, requestUri);
    WebClient.RequestHeadersSpec<?> requestSpec =
        buildRequestSpec(client, method, requestUri, config, auth, sendAuth);

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
            .timeout(Duration.ofMillis(ctx.timeoutMs()))
            .block();

    // Handle redirect: locationRef is set only when a 3xx was received
    String location = locationRef.get();
    if (location != null) {
      // Location 은 무엇보다 먼저 지금 요청 기준 절대 주소로 푼다(WD-657) — 주소 검사(SSRF)·출처 판단·다음 요청이 모두 같은 절대 주소를 본다.
      // 예전에는 상대 주소(「/v2/items?page=2」)를 그대로 써 검사·요청이 실패했다.
      URI target = requestUri.resolve(location);
      log.debug(
          "Redirect {}/{}: {} -> {}",
          redirectCount + 1,
          MAX_REDIRECTS,
          ApiCallErrorMessages.redactUrl(requestUri),
          ApiCallErrorMessages.redactUrl(target));

      // SSRF-validate the redirect target before following it
      ssrfProtectionService.validateUrl(target.toString());

      boolean sameOrigin = ApiCallAuth.isSameAuthOrigin(originUri, target);
      if (auth.isQueryApiKey()) {
        target = adjustQueryAuth(target, auth, sameOrigin, hasLeftOrigin);
      }
      // Redirects always use GET (standard 301/302/303 behaviour)
      return executeWithRedirects(
          ctx, "GET", target, redirectCount + 1, hasLeftOrigin || !sameOrigin);
    }

    return body;
  }

  /**
   * 리다이렉트 대상의 쿼리 인증 키를 출처에 맞춘다(WD-657).
   *
   * <ul>
   *   <li>다른 출처: Location 이 되울려 담아 온 키를 뺀다 — 헤더만 떼고 쿼리 키를 남기면 같은 비밀이 그대로 나간다.
   *   <li>처음 출처로 돌아옴(A→B→A): 앞에서 뗀 키가 빠져 있으면 되붙인다 — 헤더 인증은 같은 출처 홉에 다시 붙으므로 쿼리 인증도 같게 한다.
   *   <li>처음부터 같은 출처 안에서만 옮겨 다니면 Location 을 받은 그대로 쓴다(WD-182).
   * </ul>
   */
  static URI adjustQueryAuth(
      URI target, ApiCallAuth.ResolvedAuth auth, boolean sameOrigin, boolean hasLeftOrigin) {
    String url = target.toString();
    if (!sameOrigin) {
      return URI.create(ApiCallAuth.stripQueryParam(url, auth.paramName()));
    }
    if (hasLeftOrigin && !ApiCallAuth.hasQueryParam(url, auth.paramName())) {
      try {
        // 처음 요청과 같은 공용 빌더(QueryApiKeyEncoding 으로 한 번만 인코딩)로 붙인다.
        return auth.buildUrls(url, Map.of()).callUri();
      } catch (AuthQueryMasker.InvalidRequestUrlException e) {
        throw new ApiCallException(e.getMessage());
      }
    }
    return target;
  }

  /**
   * 리다이렉트를 따라가는 동안 홉마다 바뀌지 않는 값(WD-657) — 홉마다 바뀌는 메서드·URI·횟수·출처 이탈 여부만 인자로 넘긴다.
   *
   * @param originUri 처음 요청 URI — 인증을 붙일지 판단하는 출처 기준
   */
  private record RedirectContext(
      WebClient client,
      URI originUri,
      ApiCallConfig config,
      String resolvedUrl,
      ApiCallAuth.ResolvedAuth auth,
      int timeoutMs) {}

  /**
   * Builds a {@link WebClient.RequestHeadersSpec} for the given method and URI, applying configured
   * headers and auth.
   *
   * @param sendAuth 인증 헤더를 붙일지 — 처음 요청과 다른 출처로 가는 리다이렉트 홉이면 false(WD-657)
   */
  private WebClient.RequestHeadersSpec<?> buildRequestSpec(
      WebClient client,
      String method,
      URI requestUri,
      ApiCallConfig config,
      ApiCallAuth.ResolvedAuth auth,
      boolean sendAuth) {

    WebClient.RequestHeadersSpec<?> requestSpec;

    if ("POST".equals(method)) {
      WebClient.RequestBodySpec bodySpec = client.post().uri(requestUri);
      applyHeaders(bodySpec, config, auth, sendAuth);
      if (config.body() != null && !config.body().isBlank()) {
        requestSpec = bodySpec.header("Content-Type", "application/json").bodyValue(config.body());
      } else {
        requestSpec = bodySpec;
      }
    } else {
      // GET (default) and redirect follow-ups
      WebClient.RequestHeadersSpec<?> getSpec = client.get().uri(requestUri);
      applyHeaders(getSpec, config, auth, sendAuth);
      requestSpec = getSpec;
    }

    return requestSpec;
  }

  /**
   * 사용자 헤더와 인증 헤더를 붙인다.
   *
   * <p>{@code sendAuth} 가 false(다른 출처 리다이렉트, WD-657)면 인증 헤더를 붙이지 않고, 사용자 헤더 가운데 자격 증명 헤더도 뺀다 —
   * Authorization·Proxy-Authorization·Cookie 와 인증 헤더와 같은 이름({@link
   * ApiCallAuth.ResolvedAuth#isCredentialHeader}). 비밀을 사용자 헤더로 넣어 둔 경우까지 다른 출처로 나가지 않게 한다. Python
   * {@code _do_request} 도 같은 규칙이다.
   */
  private void applyHeaders(
      WebClient.RequestHeadersSpec<?> spec,
      ApiCallConfig config,
      ApiCallAuth.ResolvedAuth auth,
      boolean sendAuth) {

    // Custom headers from config — 다른 출처 홉이면 자격 증명 헤더를 뺀다(WD-657).
    if (config.headers() != null) {
      config
          .headers()
          .forEach(
              (name, value) -> {
                if (sendAuth || !auth.isCredentialHeader(name)) {
                  spec.header(name, value);
                }
              });
    }

    // 인증 헤더 — execute 가 한 번 푼 값(ApiCallAuth.resolve)을 미리보기와 같은 규칙(ResolvedAuth.applyHeaders)으로
    // 붙인다(WD-647). 다른 출처 홉에는 붙이지 않는다(WD-657).
    if (sendAuth) {
      auth.applyHeaders(spec::header);
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

  /**
   * 페이지 행을 적재 대상에 쓴다. REPLACE 면 대상이 이번 실행의 임시 테이블이라 임시 테이블 전용 창구로 쓴다 — Tibero 의 임시 테이블은 스테이징({@code
   * STG_…}: 사용자 컬럼 + {@code _SEQ})이라 실제 테이블용 {@code insertBatch}({@code ID}·{@code _UPDATED_AT} 을
   * 씀)로는 쓸 수 없다(WD-77). PG 에서는 예전 {@code insertBatch} 호출과 똑같이 위임된다. APPEND 는 실제 출력 테이블에 직접 쓴다(PG 와
   * 같은 커밋 특성).
   */
  private void writeRows(
      boolean isReplace, String target, List<String> columns, List<Map<String, Object>> rows) {
    if (isReplace) {
      dataTableRowService.insertTempBatch(target, columns, rows, null);
    } else {
      // WD-666: 실제 출력 테이블 쓰기 — 페이지마다 짧은 트랜잭션에서 데이터셋 행을 잡고 쓰고 커밋한다. 다음 페이지를 받는(외부 API) 동안에는
      // 잠금을 쥐지 않는다.
      writeLock.writeToOutput(target, () -> dataTableRowService.insertBatch(target, columns, rows));
    }
  }

  private List<String> extractColumns(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return List.of();
    return new ArrayList<>(rows.get(0).keySet());
  }
}
