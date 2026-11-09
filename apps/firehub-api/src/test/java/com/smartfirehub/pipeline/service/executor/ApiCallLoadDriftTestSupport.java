package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.dataset.service.DatasetDataWriteLock;
import com.smartfirehub.dataset.service.DatasetService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WD-670 API_CALL 적재 중 구조 변경 테스트들의 공용 조립 — 실제 DB 빈(데이터셋·테이블·잠금)과 WireMock 두 페이지 API(둘째 페이지 지연)로
 * {@link ApiCallExecutor} 를 직접 만든다. SSRF 검사(사전·연결 시점)만 끈다(localhost WireMock).
 */
final class ApiCallLoadDriftTestSupport {

  private ApiCallLoadDriftTestSupport() {}

  /** SSRF 검사를 끈 실행기 — 나머지는 실제 빈. */
  static ApiCallExecutor executor(
      DataTableService dataTableService,
      DataTableRowService dataTableRowService,
      DatasetDataWriteLock writeLock) {
    return new ApiCallExecutor(
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {
            // 테스트가 띄운 WireMock(localhost)이다.
          }
        },
        new JsonResponseParser(),
        new OffsetPaginationHandler(),
        dataTableService,
        dataTableRowService,
        WebClient.builder(),
        writeLock,
        // 연결 시점 SSRF 검사(WD-613)도 끈다 — WireMock 이 루프백이라 사전 검사와 함께 우회해야 한다.
        new SsrfSafeHttpClientFactory(address -> {}));
  }

  /** 두 페이지(각 2행, code·name) API — 둘째 페이지는 {@code delayMs} 늦게 답한다. */
  static void stubTwoPages(WireMockServer wireMock, int delayMs) {
    wireMock.stubFor(
        get(urlPathEqualTo("/api/paged"))
            .withQueryParam("offset", equalTo("0"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total\":4,\"items\":[{\"code\":\"n1\",\"name\":\"N1\"},"
                            + "{\"code\":\"n2\",\"name\":\"N2\"}]}")));
    wireMock.stubFor(
        get(urlPathEqualTo("/api/paged"))
            .withQueryParam("offset", equalTo("2"))
            .willReturn(
                aResponse()
                    .withFixedDelay(delayMs)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"total\":4,\"items\":[{\"code\":\"n3\",\"name\":\"N3\"},"
                            + "{\"code\":\"n4\",\"name\":\"N4\"}]}")));
  }

  /** 둘째 페이지 요청이 도착할 때까지(= 첫 페이지 적재가 끝남) 기다린다. */
  static void awaitSecondPageRequested(WireMockServer wireMock) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      if (!wireMock
          .findAll(
              getRequestedFor(urlPathEqualTo("/api/paged")).withQueryParam("offset", equalTo("2")))
          .isEmpty()) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("둘째 페이지 요청이 오지 않았다");
  }

  /** code·name 매핑, 페이지 크기 2 의 OFFSET 설정. */
  static ApiCallConfig config(String baseUrl) {
    return new ApiCallConfig(
        baseUrl + "/api/paged",
        "GET",
        null,
        null,
        null,
        "JSON",
        "$.items",
        List.of(
            new ApiCallConfig.FieldMapping("code", "code", "TEXT", null, null, null),
            new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null)),
        "UTC",
        new ApiCallConfig.PaginationConfig("OFFSET", 2, "offset", "limit", "$.total"),
        new ApiCallConfig.RetryConfig(0, 100, 1000),
        30000,
        60000,
        100,
        null,
        null,
        null,
        null);
  }

  /** code·name(TEXT, NULL 허용) 데이터셋을 만든다. */
  static Long createDataset(DatasetService datasetService, String table, Long userId) {
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("code", "Code", "TEXT", null, true, false, null, false),
            new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null, false));
    return datasetService
        .createDataset(
            new CreateDatasetRequest(
                "WD670 " + table, table, null, null, "TABLE", "SOURCE", columns, null),
            userId)
        .id();
  }
}
