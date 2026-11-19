package com.smartfirehub.global.util;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.smartfirehub.global.config.JacksonCompatConfig;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직접 만드는 WebClient 의 공유 코덱 매퍼({@link AppJsonWebClients})가 Boot 가 HTTP 변환기·주입에 쓰는 앱 매퍼와 같은 바이트를 쓰고
 * 같게 읽는지 못 박는다(WD-793 코드 리뷰 지적 1·2).
 *
 * <p>왜: 두 매퍼는 {@link JacksonCompatConfig} 의 같은 규칙에서 나오지만, Boot 쪽은 {@code application.yml} 의 {@code
 * spring.jackson.*} 도 탄다. yml 에 무엇이 더해지면 직접 빌더만 조용히 갈라지므로, 실제 {@code application.yml} 을 읽은 Boot 매퍼와
 * 바이트로 대조한다. 시간대는 골든 시험과 같이 비-UTC(Asia/Seoul)로 고정한다 — java.sql.Date 가 하루 어긋나는 문제가 비-UTC 에서만 드러난다.
 */
class AppJsonWebClientsEquivalenceTest {

  private static TimeZone originalTimeZone;
  private static JsonMapper bootMapper;
  private static WireMockServer wireMock;

  /** 날짜·시각·숫자·null 이 섞인 값 — 직접 빌더가 ai-agent 로 보내는 행 Map 과 같은 모양을 DTO 로도 대조한다. */
  record Sample(
      LocalDate localDate,
      LocalDateTime localDateTime,
      OffsetDateTime offsetDateTime,
      Instant instant,
      java.sql.Date sqlDate,
      Timestamp timestamp,
      BigDecimal decimal,
      String nullField,
      int count) {}

  /** 원시 타입 자리 null·모르는 속성을 받는 응답 DTO(OntologyService 의 GraphResponse 같은 모양). */
  record Counted(String name, int sourceChunkCount, long total) {}

  @BeforeAll
  static void setUp() {
    originalTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    // 실제 application.yml 을 읽어 앱과 같은 spring.jackson.* 로 Boot 매퍼를 만든다.
    new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
        .withUserConfiguration(JacksonCompatConfig.class)
        .run(
            ctx -> {
              assertThat(ctx.getEnvironment().getProperty("spring.jackson.use-jackson2-defaults"))
                  .isEqualTo("true");
              bootMapper = ctx.getBean(JsonMapper.class);
            });
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  @AfterAll
  static void tearDown() {
    TimeZone.setDefault(originalTimeZone);
    wireMock.stop();
  }

  private static Sample sample() {
    return new Sample(
        LocalDate.of(2024, 2, 29),
        LocalDateTime.of(2024, 2, 29, 10, 30, 15, 123_000_000),
        OffsetDateTime.of(2024, 2, 29, 10, 30, 0, 0, ZoneOffset.ofHours(9)),
        Instant.parse("2024-02-29T01:30:00Z"),
        java.sql.Date.valueOf("2024-02-29"),
        Timestamp.from(Instant.parse("2024-02-29T06:00:00.250Z")),
        new BigDecimal("12.50"),
        null,
        3);
  }

  /** 행 Map 모양(AiAgentClient 의 rows) — 같은 값들을 순서 있는 Map 에 담는다. */
  private static Map<String, Object> sampleRow() {
    Sample s = sample();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("localDate", s.localDate());
    row.put("localDateTime", s.localDateTime());
    row.put("offsetDateTime", s.offsetDateTime());
    row.put("instant", s.instant());
    row.put("sqlDate", s.sqlDate());
    row.put("timestamp", s.timestamp());
    row.put("decimal", s.decimal());
    row.put("nullField", null);
    return row;
  }

  @Test
  void sharedMapper_writesSameBytesAsBootMapper() throws Exception {
    JsonMapper shared = AppJsonWebClients.sharedMapper();
    assertThat(new String(shared.writeValueAsBytes(sample()), StandardCharsets.UTF_8))
        .isEqualTo(new String(bootMapper.writeValueAsBytes(sample()), StandardCharsets.UTF_8));
    assertThat(shared.writeValueAsString(List.of(sampleRow())))
        .isEqualTo(bootMapper.writeValueAsString(List.of(sampleRow())));
    // 지적 1 의 핵심: java.sql.Date 는 하루 어긋난 UTC 시각이 아니라 yyyy-MM-dd 다.
    assertThat(shared.writeValueAsString(sampleRow())).contains("\"sqlDate\":\"2024-02-29\"");
  }

  @Test
  void sharedMapper_readsSameAsBootMapper_includingPrimitiveNullAndUnknownProperty()
      throws Exception {
    String json = bootMapper.writeValueAsString(sample());
    assertThat(AppJsonWebClients.sharedMapper().readValue(json, Sample.class))
        .isEqualTo(bootMapper.readValue(json, Sample.class));

    // 원시 타입 자리 null(지적 2 — 예전엔 502)·모르는 속성은 둘 다 받아 기본값으로 둔다.
    String primitiveNull = "{\"name\":\"g\",\"sourceChunkCount\":null,\"total\":null,\"extra\":1}";
    Counted fromShared = AppJsonWebClients.sharedMapper().readValue(primitiveNull, Counted.class);
    assertThat(fromShared).isEqualTo(bootMapper.readValue(primitiveNull, Counted.class));
    assertThat(fromShared).isEqualTo(new Counted("g", 0, 0L));
    // 대조군: Spring 기본(Jackson 3 기본값) 매퍼는 이 입력을 거부한다 — 공유 매퍼가 필요한 이유.
    assertThatThrownBy(() -> new JsonMapper().readValue(primitiveNull, Counted.class))
        .isInstanceOf(tools.jackson.databind.DatabindException.class);
  }

  /** 팩토리 WebClient 가 실제로 공유 매퍼 코덱을 쓰는지 — 나가는 본문 바이트가 Boot 매퍼와 같고, 응답을 같게 읽는다. */
  @Test
  void webClientBuilder_encodesAndDecodesWithAppMapper() throws Exception {
    wireMock.resetAll();
    wireMock.stubFor(
        post(urlEqualTo("/echo"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    wireMock.stubFor(
        get(urlEqualTo("/counted"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"name\":\"g\",\"sourceChunkCount\":null,\"total\":2}")));
    WebClient client = AppJsonWebClients.webClientBuilder().baseUrl(wireMock.baseUrl()).build();

    client.post().uri("/echo").bodyValue(sampleRow()).retrieve().toBodilessEntity().block();
    String sent = wireMock.getAllServeEvents().get(0).getRequest().getBodyAsString();
    assertThat(sent).isEqualTo(bootMapper.writeValueAsString(sampleRow()));

    Counted got = client.get().uri("/counted").retrieve().bodyToMono(Counted.class).block();
    assertThat(got).isEqualTo(new Counted("g", 0, 2L));
  }

  /** 교환 전략도 같은 코덱을 쓰고, 크기 상한이 그 디코더에 걸린다. */
  @Test
  void exchangeStrategies_usesAppMapperAndAppliesSizeLimit() {
    wireMock.resetAll();
    wireMock.stubFor(
        get(urlEqualTo("/counted"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"name\":\"" + "x".repeat(200) + "\",\"sourceChunkCount\":null}")));
    WebClient roomy =
        WebClient.create(wireMock.baseUrl())
            .mutate()
            .exchangeStrategies(AppJsonWebClients.exchangeStrategies(1024))
            .build();
    assertThat(roomy.get().uri("/counted").retrieve().bodyToMono(Counted.class).block())
        .extracting(Counted::sourceChunkCount)
        .isEqualTo(0);

    WebClient tight =
        WebClient.create(wireMock.baseUrl())
            .mutate()
            .exchangeStrategies(AppJsonWebClients.exchangeStrategies(64))
            .build();
    assertThatThrownBy(
            () -> tight.get().uri("/counted").retrieve().bodyToMono(Counted.class).block())
        .hasRootCauseInstanceOf(org.springframework.core.io.buffer.DataBufferLimitException.class);
  }
}
