package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.apiconnection.dto.ApiConnectionResponse;
import com.smartfirehub.apiconnection.service.AuthQueryMasker;
import com.smartfirehub.apiconnection.service.BaseUrlUserInfo;
import com.smartfirehub.apiconnection.service.UrlUtils;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

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
  private final SsrfProtectionService ssrfProtectionService;
  private final JsonResponseParser jsonResponseParser;
  private final OffsetPaginationHandler offsetPaginationHandler;
  private final DataTableService dataTableService;
  private final DataTableRowService dataTableRowService;
  private final WebClient.Builder webClientBuilder;

  /** 출력 테이블 쓰기 잠금(WD-666) — APPEND 페이지 쓰기와 REPLACE 맞바꿈을 데이터셋 행 잠금과 같은 트랜잭션에서 한다. */
  private final DatasetDataWriteLock writeLock;

  /**
   * 접속 시점 주소 검사가 걸린 HTTP 클라이언트(WD-613). validateUrl 은 호출 전 한 번만 해석하므로, 접속 때 DNS 가 내부 주소로 바뀌는 재바인딩을
   * 이 클라이언트의 해석기가 막는다(첫 요청·리다이렉트 홉 모두).
   */
  private final SsrfSafeHttpClientFactory ssrfSafeHttpClientFactory;

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
    // 응답 크기 제한(maxResponseSizeMb)을 건 클라이언트는 실행마다 한 번만 만든다(WD-693) — 페이지·재시도마다 다시 만들지 않는다.
    int maxResponseSizeMb =
        (config.maxResponseSizeMb() != null)
            ? config.maxResponseSizeMb()
            : DEFAULT_MAX_RESPONSE_SIZE_MB;
    HttpTarget http = new HttpTarget(noRedirectClient(maxResponseSizeMb), maxResponseSizeMb);

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
                  http,
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
            executeRequest(
                config, http, resolvedUrl, auth, Map.of(), timeoutMs, cancel, 0, isReplace);
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
        // 실행 기록에 남는 줄이라 주소의 사용자 정보(아이디:비밀번호@)는 비밀번호를 가린다(WD-681).
        "url="
            + BaseUrlUserInfo.mask(resolvedUrl)
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
      HttpTarget http,
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
        return doHttpRequest(config, http, resolvedUrl, auth, paginationParams, timeoutMs);
      } catch (ApiCallException | SsrfException e) {
        // 재시도 안 함: 고쳐지지 않는 4xx·응답 크기 초과 등(ApiCallException)과 리다이렉트 대상이 내부망인 경우(SsrfException).
        // 예전에는 SsrfException 이 아래 catch 로 떨어져 같은 차단을 N회 재시도하고 원인까지 묻혔다(WD-150).
        // 접속 시점 주소 검사(WD-613, DNS 재바인딩)에 막힌 홉도 RedirectFollower 가 같은 SsrfException 으로 꺼내 주므로 여기서
        // 끝난다.
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
      HttpTarget http,
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

    String method = (config.method() != null) ? config.method().toUpperCase(Locale.ROOT) : "GET";

    try {
      // 홉마다 SSRF 검사·메서드/본문 규칙·다른 출처 인증 떼기는 RedirectFollower 한 곳에 있다(WD-693 — 미리보기·연결 테스트도
      // 같은 경로). 전송 오류는 RedirectFollower 가 실패한 홉의 호스트로 분류·가린 재시도 예외(RetryableApiCallException)로 낸다.
      return RedirectFollower.follow(
              new RedirectFollower.Request(
                  http.client(),
                  ssrfProtectionService,
                  method,
                  requestUri,
                  config.headers(),
                  config.body(),
                  auth,
                  timeoutMs,
                  // 홉마다 timeoutMs — Python 실행기(httpx)와 같고, 전체 시간은 재시도·maxDurationMs 가 따로 다룬다
                  true,
                  // 4xx·5xx 는 예외(재시도 판단은 예외 타입으로)
                  true))
          .getBody();
    } catch (DataBufferLimitException e) {
      // Fix 1: 응답이 maxResponseSizeMb 코덱 한도를 넘었다 — 재시도해도 같으므로 재시도하지 않는 ApiCallException 으로 낸다.
      throw new ApiCallException(
          ApiCallErrorMessages.responseTooLarge(http.maxResponseSizeMb()), e);
    }
  }

  /**
   * 한 실행이 쓰는 HTTP 클라이언트와 그 응답 크기 제한(WD-693) — 실행마다 한 번 만들어 페이지·재시도에 그대로 넘긴다.
   *
   * @param client 자동 리다이렉트를 끄고 응답 크기 제한을 건 클라이언트({@link #noRedirectClient})
   * @param maxResponseSizeMb 응답 크기 제한(MB) — 초과 문구용
   */
  private record HttpTarget(WebClient client, int maxResponseSizeMb) {}

  /**
   * 응답 크기 제한을 건, 자동 리다이렉트를 끈 클라이언트를 만든다.
   *
   * <p>Fix 1: maxResponseSizeMb 는 ExchangeStrategies 코덱 상한으로 강제한다 — 넘으면 WebClient 가
   * DataBufferLimitException 을 낸다. Fix 2: 자동 리다이렉트를 꺼 홉마다 SSRF 를 검사한다 — 그렇지 않으면 악성 서버가
   * http://169.254.169.254/ 로 301 해 처음 validateUrl() 을 우회한다. withoutAutoRedirect 는 주입받은 공유 빌더를 복제해
   * 쓴다(WD-693) — 응답 크기 제한이 다른 파이프라인이 동시에 돌아도 서로의 코덱 설정을 덮어쓰지 않는다.
   */
  private WebClient noRedirectClient(int maxResponseSizeMb) {
    ExchangeStrategies exchangeStrategies =
        ExchangeStrategies.builder()
            .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseSizeMb * 1024 * 1024))
            .build();
    return RedirectFollower.withoutAutoRedirect(webClientBuilder, ssrfSafeHttpClientFactory)
        .exchangeStrategies(exchangeStrategies)
        .build();
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
      // 잠금을 쥐지 않는다. WD-670: 그래서 페이지 사이에 필드가 지워질 수 있다 — 잠금 뒤 이 페이지의 컬럼이 아직 있는지 보고, 없으면 그 필드를
      // 짚어 깔끔하게 실패한다(DB 원문 「column does not exist」 대신).
      writeLock.writeToOutput(
          target, columns, () -> dataTableRowService.insertBatch(target, columns, rows));
    }
  }

  private List<String> extractColumns(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return List.of();
    return new ArrayList<>(rows.get(0).keySet());
  }
}
