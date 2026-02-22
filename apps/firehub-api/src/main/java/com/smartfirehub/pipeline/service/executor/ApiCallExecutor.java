package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.dataset.service.DataTableService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ApiCallExecutor {

    private static final Logger log = LoggerFactory.getLogger(ApiCallExecutor.class);

    private static final int DEFAULT_TIMEOUT_MS           = 30_000;
    private static final int DEFAULT_MAX_DURATION_MS      = 3_600_000;
    private static final int DEFAULT_MAX_RETRIES          = 3;
    private static final int DEFAULT_INITIAL_BACKOFF      = 1_000;
    private static final int DEFAULT_MAX_BACKOFF          = 30_000;
    private static final int DEFAULT_PAGE_SIZE            = 100;
    private static final int DEFAULT_MAX_RESPONSE_SIZE_MB = 10;
    private static final int MAX_REDIRECTS                = 5;

    private final SsrfProtectionService   ssrfProtectionService;
    private final JsonResponseParser      jsonResponseParser;
    private final OffsetPaginationHandler offsetPaginationHandler;
    private final DataTableService        dataTableService;
    private final WebClient.Builder       webClientBuilder;

    public ApiCallExecutor(
            SsrfProtectionService ssrfProtectionService,
            JsonResponseParser jsonResponseParser,
            OffsetPaginationHandler offsetPaginationHandler,
            DataTableService dataTableService,
            WebClient.Builder webClientBuilder) {

        this.ssrfProtectionService   = ssrfProtectionService;
        this.jsonResponseParser      = jsonResponseParser;
        this.offsetPaginationHandler = offsetPaginationHandler;
        this.dataTableService        = dataTableService;
        this.webClientBuilder        = webClientBuilder;
    }

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
     * @param config              parsed step configuration
     * @param outputTableName     target data-schema table name
     * @param decryptedAuthConfig auth key/value pairs (already decrypted), may be null
     * @param loadStrategy        "APPEND" or "REPLACE" (REPLACE truncates first)
     * @return result with total rows inserted and an execution log string
     */
    public ApiCallResult execute(
            ApiCallConfig config,
            String outputTableName,
            Map<String, String> decryptedAuthConfig,
            String loadStrategy,
            Map<String, String> columnTypeMap) {

        // 1. SSRF guard on the initial URL
        ssrfProtectionService.validateUrl(config.url());

        // 2. Apply load strategy — for REPLACE create a temp table and insert there;
        //    swap into place only after all pages succeed.
        boolean isReplace = "REPLACE".equalsIgnoreCase(loadStrategy);
        boolean swapped   = false;

        if (isReplace) {
            dataTableService.createTempTable(outputTableName);
        }

        // The actual insert target: temp table for REPLACE, original for APPEND
        String insertTarget = isReplace ? outputTableName + "_tmp" : outputTableName;

        long startTime     = System.currentTimeMillis();
        int  maxDurationMs = config.maxDurationMs() != null ? config.maxDurationMs() : DEFAULT_MAX_DURATION_MS;
        int  timeoutMs     = config.timeoutMs()      != null ? config.timeoutMs()     : DEFAULT_TIMEOUT_MS;

        int           totalRows  = 0;
        int           totalPages = 0;
        StringBuilder executionLog = new StringBuilder();

        try {
            // 3. Determine pagination mode
            String paginationType = (config.pagination() != null) ? config.pagination().type() : "NONE";

            if ("OFFSET".equalsIgnoreCase(paginationType)) {
                // Offset pagination loop
                ApiCallConfig.PaginationConfig pag = config.pagination();
                int     pageSize   = (pag.pageSize() != null) ? pag.pageSize() : DEFAULT_PAGE_SIZE;
                int     offset     = 0;
                Integer totalCount = null;

                while (true) {
                    // Build pagination params
                    Map<String, String> pagParams = offsetPaginationHandler.buildPaginationParams(
                            pag.offsetParam(), pag.limitParam(), offset, pageSize);

                    // Fetch page
                    String responseBody = executeRequest(config, decryptedAuthConfig, pagParams, timeoutMs);

                    // Parse data
                    List<Map<String, Object>> rows = jsonResponseParser.parseAndMap(
                            responseBody, config.dataPath(), config.fieldMappings(), columnTypeMap);

                    // Extract totalCount from response if configured (only needed on first page)
                    if (totalCount == null && pag.totalPath() != null && !pag.totalPath().isBlank()) {
                        try {
                            Object raw = jsonResponseParser.readPath(responseBody, pag.totalPath(), Object.class);
                            if (raw instanceof Number n) {
                                totalCount = n.intValue();
                            }
                        } catch (ApiCallException e) {
                            log.debug("totalPath '{}' not found in response — using partial-page stop condition", pag.totalPath());
                        }
                    }

                    // Insert batch into the target table (tmp for REPLACE, original for APPEND)
                    if (!rows.isEmpty()) {
                        List<String> columns = extractColumns(rows);
                        dataTableService.insertBatch(insertTarget, columns, rows);
                        totalRows += rows.size();
                    }
                    totalPages++;

                    log.debug("Fetched page offset={} rows={} totalSoFar={}", offset, rows.size(), totalRows);

                    // Check duration
                    long elapsed = System.currentTimeMillis() - startTime;
                    if (elapsed >= maxDurationMs) {
                        executionLog.append("[WARN] maxDurationMs (").append(maxDurationMs)
                                .append(" ms) exceeded after ").append(totalPages).append(" pages. Partial result.\n");
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
                String responseBody = executeRequest(config, decryptedAuthConfig, Map.of(), timeoutMs);
                List<Map<String, Object>> rows = jsonResponseParser.parseAndMap(
                        responseBody, config.dataPath(), config.fieldMappings(), columnTypeMap);

                if (!rows.isEmpty()) {
                    List<String> columns = extractColumns(rows);
                    dataTableService.insertBatch(insertTarget, columns, rows);
                    totalRows += rows.size();
                }
                totalPages = 1;
            }

            // 4. All pages succeeded — atomically swap tmp -> original for REPLACE
            if (isReplace) {
                dataTableService.swapTable(outputTableName);
                swapped = true;
            }

        } catch (Exception e) {
            // On failure, clean up the temp table so original data is preserved
            if (isReplace && !swapped) {
                try {
                    dataTableService.dropTempTable(outputTableName);
                } catch (Exception dropEx) {
                    log.warn("Failed to drop temp table after API call failure: {}", dropEx.getMessage());
                }
            }
            throw e;
        }

        long durationMs = System.currentTimeMillis() - startTime;

        executionLog.insert(0,
                "url=" + config.url() +
                " method=" + config.method() +
                " pages=" + totalPages +
                " rows=" + totalRows +
                " duration=" + durationMs + "ms\n");

        return new ApiCallResult(totalRows, executionLog.toString());
    }

    // -------------------------------------------------------------------------
    // HTTP execution with retry
    // -------------------------------------------------------------------------

    private String executeRequest(
            ApiCallConfig config,
            Map<String, String> decryptedAuthConfig,
            Map<String, String> paginationParams,
            int timeoutMs) {

        int maxRetries     = DEFAULT_MAX_RETRIES;
        int initialBackoff = DEFAULT_INITIAL_BACKOFF;
        int maxBackoff     = DEFAULT_MAX_BACKOFF;

        if (config.retry() != null) {
            if (config.retry().maxRetries()       != null) maxRetries     = config.retry().maxRetries();
            if (config.retry().initialBackoffMs() != null) initialBackoff = config.retry().initialBackoffMs();
            if (config.retry().maxBackoffMs()     != null) maxBackoff     = config.retry().maxBackoffMs();
        }

        int       attempt       = 0;
        int       backoffMs     = initialBackoff;
        Exception lastException = null;

        while (attempt <= maxRetries) {
            try {
                return doHttpRequest(config, decryptedAuthConfig, paginationParams, timeoutMs);
            } catch (ApiCallException e) {
                // Non-retryable: propagate immediately
                throw e;
            } catch (Exception e) {
                lastException = e;
                attempt++;
                if (attempt > maxRetries) {
                    break;
                }
                int jitter  = (int) (Math.random() * 1000);
                int sleepMs = Math.min(backoffMs + jitter, maxBackoff);
                log.warn("Request failed (attempt {}/{}), retrying in {} ms: {}", attempt, maxRetries, sleepMs, e.getMessage());
                try {
                    Thread.sleep(sleepMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiCallException("Interrupted during retry backoff", ie);
                }
                backoffMs = Math.min(backoffMs * 2, maxBackoff);
            }
        }

        throw new ApiCallException("API call failed after " + maxRetries + " retries", lastException);
    }

    private String doHttpRequest(
            ApiCallConfig config,
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
                String apiKey    = decryptedAuthConfig.get("apiKey");
                if (paramName != null && apiKey != null) {
                    allQueryParams.put(paramName, apiKey);
                }
            }
        }

        // Build the full URI with query params
        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromUriString(config.url());
        allQueryParams.forEach(uriBuilder::queryParam);
        URI requestUri = uriBuilder.build(true).toUri();

        // Fix 1: enforce maxResponseSizeMb via ExchangeStrategies codec limit.
        // If the response body exceeds this limit WebClient throws DataBufferLimitException,
        // which is caught below and wrapped as a non-retryable ApiCallException.
        int maxResponseSizeMb = (config.maxResponseSizeMb() != null)
                ? config.maxResponseSizeMb()
                : DEFAULT_MAX_RESPONSE_SIZE_MB;
        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(maxResponseSizeMb * 1024 * 1024))
                .build();

        // Fix 2: disable automatic redirects so every hop is SSRF-validated before following.
        // Without this, a malicious server could 301-redirect to http://169.254.169.254/ and
        // bypass the initial validateUrl() check.
        HttpClient reactorHttpClient = HttpClient.create().followRedirect(false);

        WebClient client = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(reactorHttpClient))
                .exchangeStrategies(exchangeStrategies)
                .build();

        String method = (config.method() != null) ? config.method().toUpperCase() : "GET";

        try {
            return executeWithRedirects(client, method, requestUri, config, decryptedAuthConfig, timeoutMs, 0);
        } catch (DataBufferLimitException e) {
            throw new ApiCallException(
                    "Response exceeded maxResponseSizeMb limit (" + maxResponseSizeMb + " MB) for: " + config.url(), e);
        } catch (ApiCallException e) {
            throw e;
        } catch (Exception e) {
            // timeout, connection refused, etc. — retryable
            throw new RuntimeException("Request error for " + config.url() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Executes a single HTTP request hop and manually follows redirects after SSRF-validating
     * each Location URL. This prevents open-redirect SSRF attacks where an attacker's server
     * issues a 301/302 to an internal address (e.g. http://169.254.169.254/).
     *
     * <p>Redirect following uses GET semantics (standard browser behaviour for 301/302/303).
     * POST bodies are only sent on the initial request; redirects always use GET.
     *
     * @param redirectCount number of redirects followed so far (guards against infinite loops)
     */
    private String executeWithRedirects(
            WebClient client,
            String method,
            URI requestUri,
            ApiCallConfig config,
            Map<String, String> decryptedAuthConfig,
            int timeoutMs,
            int redirectCount) {

        if (redirectCount > MAX_REDIRECTS) {
            throw new ApiCallException("Too many redirects (max " + MAX_REDIRECTS + ") for: " + config.url());
        }

        WebClient.RequestHeadersSpec<?> requestSpec = buildRequestSpec(client, method, requestUri, config, decryptedAuthConfig);

        // Use exchangeToMono so we can inspect both status and headers before consuming the body.
        // We capture the Location header via an AtomicReference because the reactive callback
        // cannot return two values at once.
        AtomicReference<String> locationRef = new AtomicReference<>();

        String body = requestSpec
                .exchangeToMono(response -> {
                    HttpStatusCode status = response.statusCode();

                    if (status.is3xxRedirection()) {
                        // Capture Location header, drain body without buffering, signal redirect with null
                        String location = response.headers().asHttpHeaders().getFirst("Location");
                        locationRef.set(location);
                        return response.releaseBody().thenReturn("");  // sentinel: empty string + locationRef set
                    }

                    if (status.value() == 401) {
                        return response.releaseBody().then(reactor.core.publisher.Mono.error(
                                new ApiCallException("Authentication failed (401): " + requestUri)));
                    }

                    if (status.value() == 400 || status.value() == 403 || status.value() == 404) {
                        return response.releaseBody().then(reactor.core.publisher.Mono.error(
                                new ApiCallException("Non-retryable HTTP error " + status.value() + " for: " + requestUri)));
                    }

                    if (status.is4xxClientError() || status.is5xxServerError()) {
                        // 5xx and other 4xx are retryable — surface as plain RuntimeException
                        return response.bodyToMono(String.class)
                                .flatMap(b -> reactor.core.publisher.Mono.error(
                                        new RuntimeException("HTTP " + status.value() + " from " + requestUri + ": " + b)));
                    }

                    // 2xx — read the response body (size enforced by ExchangeStrategies codec limit)
                    return response.bodyToMono(String.class);
                })
                .timeout(Duration.ofMillis(timeoutMs))
                .block();

        // Handle redirect: locationRef is set only when a 3xx was received
        String location = locationRef.get();
        if (location != null) {
            log.debug("Redirect {}/{}: {} -> {}", redirectCount + 1, MAX_REDIRECTS, requestUri, location);

            // SSRF-validate the redirect target before following it
            ssrfProtectionService.validateUrl(location);

            URI redirectUri = URI.create(location);
            // Redirects always use GET (standard 301/302/303 behaviour)
            return executeWithRedirects(client, "GET", redirectUri, config, decryptedAuthConfig, timeoutMs, redirectCount + 1);
        }

        return body;
    }

    /**
     * Builds a {@link WebClient.RequestHeadersSpec} for the given method and URI,
     * applying configured headers and auth.
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
                requestSpec = bodySpec
                        .header("Content-Type", "application/json")
                        .bodyValue(config.body());
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
                String apiKey     = decryptedAuthConfig.get("apiKey");
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

    private List<String> extractColumns(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) return List.of();
        return new ArrayList<>(rows.get(0).keySet());
    }
}
