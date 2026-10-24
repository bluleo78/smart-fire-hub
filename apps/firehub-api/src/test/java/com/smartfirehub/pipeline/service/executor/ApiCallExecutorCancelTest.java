package com.smartfirehub.pipeline.service.executor;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.dataset.service.DataTableRowService;
import com.smartfirehub.dataset.service.DataTableService;
import com.smartfirehub.pipeline.exception.PipelineExecutionCancelledException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * API_CALL 실행기의 협조적 취소 체크포인트(WD-153) — 페이지 사이·재시도 백오프 중·REPLACE 맞바꿈 직전.
 *
 * <p>취소 판정은 테스트가 직접 뒤집는 플래그로 준다(결정적). DB 판정 배선은 통합 테스트(PipelineExecutionCancelIntegrationTest)가 본다.
 * WireMock 으로 외부 API 를 흉내 낸다(ApiCallExecutorTest 와 같은 구성).
 */
@ExtendWith(MockitoExtension.class)
class ApiCallExecutorCancelTest {

  static WireMockServer wireMock;

  @BeforeAll
  static void startWireMock() {
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void stopWireMock() {
    wireMock.stop();
  }

  @Mock DataTableService dataTableService;
  @Mock DataTableRowService dataTableRowService;

  ApiCallExecutor executor;

  @BeforeEach
  void setUp() {
    wireMock.resetAll();
    // SSRF 검사는 DNS 를 풀어 localhost(WireMock)를 막는다 — 테스트에서는 우회한다.
    SsrfProtectionService ssrf =
        new SsrfProtectionService() {
          @Override
          public void validateUrl(String url) {}
        };
    executor =
        new ApiCallExecutor(
            ssrf,
            new JsonResponseParser(),
            new OffsetPaginationHandler(),
            dataTableService,
            dataTableRowService,
            WebClient.builder());
  }

  private String url(String path) {
    return "http://localhost:" + wireMock.port() + path;
  }

  /** OFFSET 페이지네이션(페이지 2행) 설정. */
  private ApiCallConfig pagedConfig(String path, ApiCallConfig.RetryConfig retry) {
    return new ApiCallConfig(
        url(path),
        "GET",
        null,
        null,
        null,
        "JSON",
        "$.items",
        List.of(new ApiCallConfig.FieldMapping("name", "name", "TEXT", null, null, null)),
        "UTC",
        new ApiCallConfig.PaginationConfig("OFFSET", 2, "offset", "limit", null),
        retry,
        5000,
        60000,
        10,
        null,
        null,
        null,
        null);
  }

  private void stubPage(int offset, String body) {
    wireMock.stubFor(
        get(urlPathEqualTo("/items"))
            .withQueryParam("offset", equalTo(String.valueOf(offset)))
            .willReturn(okJson(body)));
  }

  /** 3페이지(2+2+2행)를 내주는 API. */
  private void stubThreePages() {
    stubPage(0, "{\"items\":[{\"name\":\"a\"},{\"name\":\"b\"}]}");
    stubPage(2, "{\"items\":[{\"name\":\"c\"},{\"name\":\"d\"}]}");
    stubPage(4, "{\"items\":[{\"name\":\"e\"},{\"name\":\"f\"}]}");
    // 꽉 찬 페이지 뒤에는 다음 페이지를 한 번 더 묻는다 — 빈 페이지로 끝낸다.
    stubPage(6, "{\"items\":[]}");
  }

  /** 페이지 사이: 첫 페이지를 적재한 뒤 취소되면 다음 페이지를 요청하지 않고, APPEND 는 넣은 2행을 예외에 싣는다. */
  @Test
  void APPEND_첫_페이지_적재_후_취소되면_다음_페이지를_요청하지_않고_넣은_행_수를_싣는다() {
    stubThreePages();
    AtomicBoolean cancelled = new AtomicBoolean(false);
    // 첫 페이지 적재가 끝나는 순간 취소 요청이 들어온 것으로 만든다.
    doAnswer(
            inv -> {
              cancelled.set(true);
              return null;
            })
        .when(dataTableRowService)
        .insertBatch(eq("out_t"), anyList(), anyList());

    assertThatThrownBy(
            () ->
                executor.execute(
                    pagedConfig("/items", new ApiCallConfig.RetryConfig(0, 100, 100)),
                    "out_t",
                    null,
                    "APPEND",
                    null,
                    null,
                    cancelled::get))
        .isInstanceOfSatisfying(
            PipelineExecutionCancelledException.class,
            e -> {
              assertThat(e.rowsKept()).isEqualTo(2);
              assertThat(e.replaceDiscarded()).isFalse();
              assertThat(e.stepMessage()).contains("취소 전까지 2행 적재됨");
            });

    // 진행 중이던 첫 페이지만 받았다 — 두 번째 페이지는 요청조차 하지 않았다.
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/items")));
    verify(dataTableRowService, times(1)).insertBatch(eq("out_t"), anyList(), anyList());
  }

  /** REPLACE 도중 취소되면 임시 테이블을 버리고 맞바꾸지 않는다 — 원본이 남는다. */
  @Test
  void REPLACE_도중_취소되면_임시_테이블을_버리고_맞바꾸지_않는다() {
    stubThreePages();
    when(dataTableService.createTempTable("out_t")).thenReturn("rtmp_run_out_t");
    AtomicBoolean cancelled = new AtomicBoolean(false);
    doAnswer(
            inv -> {
              cancelled.set(true);
              return null;
            })
        .when(dataTableRowService)
        .insertTempBatch(eq("rtmp_run_out_t"), anyList(), anyList(), isNull());

    assertThatThrownBy(
            () ->
                executor.execute(
                    pagedConfig("/items", new ApiCallConfig.RetryConfig(0, 100, 100)),
                    "out_t",
                    null,
                    "REPLACE",
                    null,
                    null,
                    cancelled::get))
        .isInstanceOfSatisfying(
            PipelineExecutionCancelledException.class,
            e -> {
              assertThat(e.replaceDiscarded()).isTrue();
              assertThat(e.stepMessage()).contains("기존 데이터를 그대로 유지");
            });

    verify(dataTableService).dropTempTable("out_t", "rtmp_run_out_t");
    verify(dataTableService, never()).finishReplace(anyString(), anyString(), anyLong());
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/items")));
  }

  /**
   * 재시도 백오프 중 취소: 백오프가 20초여도 취소 후 곧바로 깨어난다(대기를 다 채우지 않는다) — 예전 {@code Thread.sleep} 한 번은 백오프를 다
   * 채웠다(이슈의 「실행 중 95초」).
   */
  @Test
  void 재시도_백오프_중_취소되면_대기를_채우지_않고_곧바로_멈춘다() {
    wireMock.stubFor(get(urlPathEqualTo("/items")).willReturn(serverError()));
    long start = System.currentTimeMillis();
    // 첫 실패 뒤 백오프에 들어간 지 약 300ms 뒤 취소 요청이 들어온다.
    ExecutionCancelCheck cancelAfter300ms = () -> System.currentTimeMillis() - start > 300;

    assertThatThrownBy(
            () ->
                executor.execute(
                    pagedConfig("/items", new ApiCallConfig.RetryConfig(2, 20_000, 30_000)),
                    "out_t",
                    null,
                    "APPEND",
                    null,
                    null,
                    cancelAfter300ms))
        .isInstanceOf(PipelineExecutionCancelledException.class);

    long elapsed = System.currentTimeMillis() - start;
    // 백오프(20초+)를 기다리지 않았다 — 조각 간격(200ms) 안에 깨어난다. 여유를 두어 3초 미만으로 본다.
    assertThat(elapsed).isLessThan(3_000);
    // 재시도 요청은 나가지 않았다(첫 요청 1회).
    wireMock.verify(1, getRequestedFor(urlPathEqualTo("/items")));
  }

  /** 취소 확인을 넘기지 않는 기존 오버로드(미리보기 등)는 취소를 보지 않는다 — 끝까지 3페이지를 받는다. */
  @Test
  void 취소_확인_없는_기존_오버로드는_끝까지_돈다() {
    stubThreePages();
    ApiCallExecutor.ApiCallResult result =
        executor.execute(
            pagedConfig("/items", new ApiCallConfig.RetryConfig(0, 100, 100)),
            "out_t",
            null,
            "APPEND",
            null,
            null);
    assertThat(result.totalRows()).isEqualTo(6);
  }
}
