package com.smartfirehub.global.json;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.smartfirehub.dataimport.service.export.GeoJsonExportWriter;
import com.smartfirehub.global.exception.SerializationException;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.util.AppJsonWebClients;
import com.smartfirehub.global.util.ExactJsonMapper;
import com.smartfirehub.global.validation.MaxJsonSize;
import com.smartfirehub.global.validation.MaxJsonSizeValidator;
import com.smartfirehub.graphreview.service.GraphMutationClient;
import com.smartfirehub.notification.Payload;
import com.smartfirehub.notification.channels.slack.SlackApiClient;
import com.smartfirehub.notification.channels.slack.SlackBlockKitRenderer;
import com.smartfirehub.settings.model.AiCredentialDocument;
import com.smartfirehub.support.AppBootJsonMapper;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 직접 만든 매퍼·저장(jsonb·DB 문자열)·외부 전송 JSON 의 골든 특성 시험(WD-793) — Jackson 2→3 전환 전후로 저장 JSON 이 바이트 단위로
 * 같은지 못 박는다.
 *
 * <p>각 대상은 (a) 대표 값을 쓴 결과 문자열 = 골든, (b) 골든을 읽어 다시 쓴 결과 = 골든({@code ..._roundTrip_isByteIdentical})을
 * 확인한다. 골든은 {@code src/test/resources/jackson-golden/stored/<케이스>.json}(문자열 결과도 확장자 .json 으로 통일, 끝
 * 줄바꿈 없음)이고, 쓰기 모드는 {@link JacksonGoldenFiles} 의 {@code jackson.golden.write} 스위치다.
 *
 * <p><b>대상 전수 목록(main 에서 뽑음)</b> — 이 시험이 무엇으로 고정하는지:
 *
 * <pre>
 * [직접 만든 매퍼 · JsonMapper.builderWithJackson2Defaults().build()/JsonMapper.builder()]
 *  GeoJsonExportWriter        generator 스트리밍 출력             geoJsonExportWriter_*
 *  EmbeddingConfigDocument    toJson/parse (패키지 전용→리플렉션)  embeddingConfigDocument_*
 *  ExactJsonMapper            BigDecimal 정확 읽기·쓰기           exactJsonMapper_*
 *  MaxJsonSizeValidator       FALLBACK_MAPPER 크기 판정           maxJsonSizeValidator_*
 *  GraphMutationClient        요청 본문(WireMock)·409 사유 파싱   graphMutationClient_*
 *  AiCredentialDocument       parse/empty/toJson                  aiCredentialDocument_*
 *  AiCredentialService        MAPPER convertValue/valueToTree     (저장 값은 AiCredentialServiceTest 의 jsonb 단언)
 *  OpencodeProbeService       parseModels(private→리플렉션)       opencodeProbe_parseModels
 *  QdrantCollectionToken      JWT 헤더·클레임 직렬화              qdrantCollectionToken_*
 *  LocalPythonOutputLoader    stdout 파서·중첩 값 직렬화          localPythonOutputLoader_*
 * [주입 매퍼로 jsonb·DB 문자열을 쓰는 곳 — Spring 이 만드는 매퍼와 같은 것으로 시험]
 *  AuditLogService·DataImportService·JooqAsyncJobRepository·MappingService·JooqPipelineStepRepository·
 *  JooqTriggerEventRepository·JooqTriggerRepository·MyBatisTriggerRepository·JooqProactive*Repository·
 *  JooqReportTemplateRepository·MetricPollerService
 *                              모두 Map/List 를 objectMapper.writeValueAsString 하므로 대표 값 묶음으로 고정  springMapper_*
 *  ProactiveJsonb             toJsonb (패키지 전용→리플렉션)       proactiveJsonb_*
 *  PlatformJson               write/toMap/toMapList/toObjectNode·오류   platformJson_*
 * [외부로 나가는 본문]
 *  SlackBlockKitRenderer      Block Kit 배열 JSON                 slackBlockKitRenderer_*
 *  SlackApiClient             요청 본문(WireMock 수신 원문)       slackApiClient_*
 * </pre>
 *
 * <p>jsonb 를 쓰는 리포지토리·서비스 중 DB 가 필요한 곳은 해당 클래스의 기존 통합 시험에 저장된 jsonb 문자열 단언을 더해 고정했다(저장소별 위치는 작업 보고서
 * 참고).
 */
class JacksonGoldenStoredJsonTest {

  private static final String DIR = "jackson-golden/stored/";

  private static TimeZone originalTimeZone;
  private static ObjectMapper mapper;
  private static WireMockServer wireMock;

  /**
   * 시간대를 비-UTC 로 고정하고, 앱이 주입받는 것과 같은 매퍼를 얻는다 — 실제 application.yml 의 spring.jackson.* 와
   * JacksonCompatConfig(java.sql.Date 직렬화)를 건 Boot 매퍼({@link AppBootJsonMapper}, WD-793). 골든은
   * Jackson 2 자동 구성 매퍼로 만든 것이라, 이 매퍼가 같은 바이트를 내야 한다.
   */
  @BeforeAll
  static void setUp() {
    originalTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
    mapper = AppBootJsonMapper.create();
    wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
    wireMock.start();
  }

  /** 다른 시험에 영향이 없도록 되돌린다. */
  @AfterAll
  static void tearDown() {
    TimeZone.setDefault(originalTimeZone);
    wireMock.stop();
  }

  // ------------------------------------------------------------------ Spring 이 만드는 매퍼(주입 매퍼 대상 전체)

  /** 저장 jsonb 로 흔히 쓰는 Map 모양 — 순서·유니코드·중첩·null·숫자 종류. */
  private static Map<String, Object> storedMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "한글 \"인용\" \\ / \n 줄바꿈 \u0001");
    m.put("int", 3);
    m.put("long", 9007199254740993L);
    m.put("double", 1.5);
    m.put("decimal", new BigDecimal("123.45"));
    m.put("bigInt", new BigInteger("12345678901234567890"));
    m.put("flag", true);
    m.put("nothing", null);
    m.put("list", List.of(1, "둘", false));
    Map<String, Object> inner = new LinkedHashMap<>();
    inner.put("z", 1);
    inner.put("a", List.of(Map.of("k", "v")));
    m.put("inner", inner);
    m.put("empty", Map.of());
    return m;
  }

  @Test
  void springMapper_storedMap() throws Exception {
    checkRoundTrip("springMapper_storedMap.json", mapper.writeValueAsString(storedMap()));
  }

  /** 골든을 읽어 다시 쓴 결과가 골든과 같아야 한다(숫자는 double 로 안전한 값만 쓴 골든을 따로 둔다). */
  @Test
  void springMapper_roundTrip_isByteIdentical() throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("name", "가나다");
    m.put("n", 3);
    m.put("d", 1.5);
    m.put("list", List.of(1, "둘", true));
    m.put("inner", Map.of("k", "v"));
    m.put("nothing", null);
    String first = mapper.writeValueAsString(m);
    JacksonGoldenFiles.check(DIR, "springMapper_roundTrip.json", first);
    String golden =
        JacksonGoldenFiles.WRITE
            ? first
            : JacksonGoldenFiles.read(DIR, "springMapper_roundTrip.json");
    Object read = mapper.readValue(golden, Object.class);
    assertThat(mapper.writeValueAsString(read)).isEqualTo(golden);
  }

  @Test
  void springMapper_dates() throws Exception {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("localDate", LocalDate.of(2026, 1, 15));
    m.put("localDateTime", LocalDateTime.of(2026, 1, 15, 10, 0, 0));
    m.put("localDateTimeNanos", LocalDateTime.of(2026, 1, 15, 10, 0, 0, 123_000_000));
    m.put("offsetDateTime", OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
    m.put("instant", Instant.parse("2026-01-15T01:00:00Z"));
    m.put("duration", Duration.ofSeconds(90));
    // 저장돼 다시 읽히는 값이라 골든을 읽어 되쓴 결과도 같아야 한다.
    checkRoundTrip("springMapper_dates.json", mapper.writeValueAsString(m));
  }

  /** 감사 로그·적재 오류처럼 Map.of 하나를 쓰는 경우와 목록 최상위. */
  @Test
  void springMapper_topLevelShapes() throws Exception {
    Map<String, Object> errors = new LinkedHashMap<>();
    // Map.of 는 순회 순서가 JVM 마다 달라 골든이 흔들리므로 삽입 순서가 고정인 맵을 쓴다.
    Map<String, Object> error = new LinkedHashMap<>();
    error.put("row", 1);
    error.put("message", "오류");
    errors.put("errors", List.of(error));
    String text =
        mapper.writeValueAsString(errors)
            + "\n"
            + mapper.writeValueAsString(List.of(Map.of("a", 1), Map.of("b", List.of())))
            + "\n"
            + mapper.writeValueAsString(Map.of())
            + "\n"
            + mapper.writeValueAsString("문자열");
    // 감사 로그·적재 오류로 저장돼 다시 읽히는 모양이라 줄 단위로 되읽어 되쓴 결과도 같아야 한다.
    checkRoundTrip("springMapper_topLevelShapes.json", text);
  }

  /**
   * 골든 비교 후, 저장되어 다시 읽히는 JSON 의 (b) 왕복 확인 — 골든(줄 단위)을 읽어 다시 쓴 결과가 골든과 바이트 단위로 같아야 한다. 쓰기 모드에서는 방금 쓴
   * 값을 쓴다.
   */
  private static void checkRoundTrip(String goldenFile, String actual) throws Exception {
    JacksonGoldenFiles.check(DIR, goldenFile, actual);
    String golden = JacksonGoldenFiles.WRITE ? actual : JacksonGoldenFiles.read(DIR, goldenFile);
    for (String line : golden.split("\n")) {
      Object read = mapper.readValue(line, Object.class);
      assertThat(mapper.writeValueAsString(read)).as("왕복 " + goldenFile).isEqualTo(line);
    }
  }

  // ------------------------------------------------------------------ PlatformJson

  @Test
  void platformJson_write() throws Exception {
    String text =
        PlatformJson.write(mapper, storedMap(), "x")
            + "\n"
            + PlatformJson.write(mapper, null, "x")
            + "\n"
            + PlatformJson.write(mapper, mapper.readTree("{\"b\":1,\"a\":[1,2]}"), "x");
    JacksonGoldenFiles.check(DIR, "platformJson_write.json", text);
  }

  @Test
  void platformJson_read_roundTrip_isByteIdentical() throws Exception {
    String json = "{\"b\":1,\"a\":[1,\"둘\"],\"c\":{\"x\":null}}";
    Map<String, Object> map = PlatformJson.toMap(mapper, json, "x");
    String written = PlatformJson.write(mapper, map, "x");
    JacksonGoldenFiles.check(DIR, "platformJson_roundTrip.json", written);
    String golden =
        JacksonGoldenFiles.WRITE
            ? written
            : JacksonGoldenFiles.read(DIR, "platformJson_roundTrip.json");
    assertThat(PlatformJson.write(mapper, PlatformJson.toMap(mapper, golden, "x"), "x"))
        .isEqualTo(golden);
    // 목록·노드 변환도 같은 문자열을 되쓴다.
    assertThat(
            PlatformJson.write(
                mapper, PlatformJson.toMapList(mapper, "[{\"b\":1,\"a\":2}]", "x"), "x"))
        .isEqualTo("[{\"b\":1,\"a\":2}]");
    assertThat(PlatformJson.write(mapper, PlatformJson.toObjectNode(mapper, json, "x"), "x"))
        .isEqualTo(json);
    assertThat(PlatformJson.toObjectNode(mapper, "[1]", "x")).isNull();
    assertThat(PlatformJson.toMap(mapper, null, "x")).isNull();
  }

  /** 깨진 JSON 은 같은 예외 타입·메시지(「Failed to parse {label} JSON」)로 던진다. */
  @Test
  void platformJson_malformed_sameError() throws Exception {
    StringBuilder sb = new StringBuilder();
    sb.append(errorOf(() -> PlatformJson.toMap(mapper, "{\"a\":", "cfg"))).append('\n');
    sb.append(errorOf(() -> PlatformJson.toMapList(mapper, "[{", "rows"))).append('\n');
    sb.append(errorOf(() -> PlatformJson.toObjectNode(mapper, "{{", "node"))).append('\n');
    // 최상위 타입이 틀린 경우(배열을 맵으로, 객체를 목록으로)도 같은 예외로 바뀐다.
    sb.append(errorOf(() -> PlatformJson.toMap(mapper, "[1]", "cfg"))).append('\n');
    sb.append(errorOf(() -> PlatformJson.toMapList(mapper, "{\"a\":1}", "rows")));
    JacksonGoldenFiles.check(DIR, "platformJson_malformed.json", sb.toString());
    assertThatThrownBy(() -> PlatformJson.toMap(mapper, "{\"a\":", "cfg"))
        .isInstanceOf(SerializationException.class)
        .hasMessage("Failed to parse cfg JSON");
  }

  // ------------------------------------------------------------------ ProactiveJsonb (패키지 전용 →
  // 리플렉션)

  @Test
  void proactiveJsonb_toJsonb() throws Exception {
    Object jsonb =
        callStatic(
            "com.smartfirehub.proactive.repository.jooq.ProactiveJsonb",
            "toJsonb",
            new Class<?>[] {ObjectMapper.class, Object.class, String.class},
            mapper,
            storedMap(),
            "config");
    // toString() 은 jOOQ 가 정규화(슬래시 이스케이프)한 값이라, 실제 저장되는 원문은 data() 로 읽는다.
    String text = ((org.jooq.JSONB) jsonb).data();
    JacksonGoldenFiles.check(DIR, "proactiveJsonb_toJsonb.json", text);
    // 되읽어 다시 쓴 결과도 같다(double 로 안전하지 않은 값은 storedMap 에 있어 BigDecimal 이 아닌 골든 쪽 문자열로 비교).
    Object again =
        callStatic(
            "com.smartfirehub.proactive.repository.jooq.ProactiveJsonb",
            "toJsonb",
            new Class<?>[] {ObjectMapper.class, Object.class, String.class},
            mapper,
            mapper.readTree(text),
            "config");
    assertThat(((org.jooq.JSONB) again).data()).isEqualTo(text);
  }

  // ------------------------------------------------------------------ ExactJsonMapper

  @Test
  void exactJsonMapper_readWrite_roundTrip_isByteIdentical() throws Exception {
    String input =
        "{\"x\":127.0000000000000001,\"y\":1E-7,\"z\":[0.10,1.0E+3],\"w\":123456789012345678901234567890}";
    JsonNode tree = ExactJsonMapper.INSTANCE.readTree(input);
    String viaToString = tree.toString();
    String viaMapper = ExactJsonMapper.INSTANCE.writeValueAsString(tree);
    String text = viaToString + "\n" + viaMapper;
    JacksonGoldenFiles.check(DIR, "exactJsonMapper_readWrite.json", text);
    String golden =
        JacksonGoldenFiles.WRITE
            ? text
            : JacksonGoldenFiles.read(DIR, "exactJsonMapper_readWrite.json");
    String firstLine = golden.substring(0, golden.indexOf('\n'));
    assertThat(ExactJsonMapper.INSTANCE.readTree(firstLine).toString()).isEqualTo(firstLine);
    assertThat(
            ExactJsonMapper.INSTANCE.writeValueAsString(
                ExactJsonMapper.INSTANCE.readTree(firstLine)))
        .isEqualTo(golden.substring(golden.indexOf('\n') + 1));
  }

  // ------------------------------------------------------------------ MaxJsonSizeValidator

  /** 한글 바이트(UTF-8 3바이트)까지 센 크기 경계 — 32바이트 JSON 은 32 에서 통과, 31 에서 거부. */
  @Test
  void maxJsonSizeValidator_boundary() throws Exception {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("k", "가나");
    value.put("n", 1.5);
    value.put("d", List.of(1, 2));
    String json = JsonMapper.builderWithJackson2Defaults().build().writeValueAsString(value);
    assertThat(json).isEqualTo("{\"k\":\"가나\",\"n\":1.5,\"d\":[1,2]}");
    assertThat(json.getBytes(StandardCharsets.UTF_8).length).isEqualTo(32);
    MaxJsonSizeValidator fallback = validator(new MaxJsonSizeValidator(), 32);
    assertThat(fallback.isValid(value, null)).isTrue();
    assertThat(validator(new MaxJsonSizeValidator(), 31).isValid(value, null)).isFalse();
    // Spring 이 주입하는 매퍼로 재도 같은 경계다.
    assertThat(validator(new MaxJsonSizeValidator(mapper), 32).isValid(value, null)).isTrue();
    assertThat(validator(new MaxJsonSizeValidator(mapper), 31).isValid(value, null)).isFalse();
    assertThat(fallback.isValid(null, null)).isTrue();
  }

  private static MaxJsonSizeValidator validator(MaxJsonSizeValidator v, long maxBytes) {
    MaxJsonSize annotation = mock(MaxJsonSize.class);
    when(annotation.maxBytes()).thenReturn(maxBytes);
    v.initialize(annotation);
    return v;
  }

  // ------------------------------------------------------------------ GeoJsonExportWriter

  // 내보내기 파일로만 나가고 다시 읽히지 않는다 — 시험 이름의 왕복은 정확 매퍼로 만든 트리 되쓰기이고 (a) 골든이 본체다.
  @Test
  void geoJsonExportWriter_roundTrip_isByteIdentical() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GeoJsonExportWriter writer = new GeoJsonExportWriter(out, "geom");
    writer.writeHeader(List.of("이름", "geom", "n", "d", "big", "flag", "nul", "when"));
    writer.writeRow(
        new Object[] {
          "서울 \"강남\"",
          "{\"type\":\"Point\",\"coordinates\":[127.0,37.5]}",
          3,
          new BigDecimal("1.50"),
          9007199254740993L,
          true,
          null,
          LocalDateTime.of(2026, 1, 15, 10, 0, 0)
        });
    writer.writeRow(new Object[] {"b", null, 1.5, new BigDecimal("2.25"), 5L, false, null, null});
    writer.close();
    String text = out.toString(StandardCharsets.UTF_8);
    JacksonGoldenFiles.check(DIR, "geoJsonExportWriter.json", text);
    String golden =
        JacksonGoldenFiles.WRITE ? text : JacksonGoldenFiles.read(DIR, "geoJsonExportWriter.json");
    // 골든을 읽어 다시 쓰면(소수는 정확히 읽는 매퍼) 같은 문자열이다.
    assertThat(
            ExactJsonMapper.INSTANCE.writeValueAsString(ExactJsonMapper.INSTANCE.readTree(golden)))
        .isEqualTo(golden);
  }

  /** 평문 소수 표기(지수 표기 없음)와 double 로 깎이는 값의 문자열 처리 — 되읽기 비교는 하지 않는다(트리가 지수 표기로 바꾼다). */
  // 외부로만 나가고 다시 읽히지 않아 왕복 확인은 하지 않는다(트리로 되읽으면 지수 표기로 바뀌어 비교가 무의미).
  @Test
  void geoJsonExportWriter_plainDecimals() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    GeoJsonExportWriter writer = new GeoJsonExportWriter(out, "geom");
    writer.writeHeader(List.of("a", "b", "c", "d"));
    writer.writeRow(
        new Object[] {
          new BigDecimal("0.0000001"),
          new BigDecimal("1E+3"),
          new BigDecimal("12345678901234567890.123456789"),
          Double.NaN
        });
    writer.close();
    JacksonGoldenFiles.check(
        DIR, "geoJsonExportWriter_plainDecimals.json", out.toString(StandardCharsets.UTF_8));
  }

  // ------------------------------------------------------------------ EmbeddingConfigDocument (패키지
  // 전용 → 리플렉션)

  private static final String EMBEDDING_DOC =
      "com.smartfirehub.embedding.config.EmbeddingConfigDocument";

  @Test
  void embeddingConfigDocument_roundTrip_isByteIdentical() throws Exception {
    Class<?> providerType =
        Class.forName("com.smartfirehub.embedding.config.EmbeddingProviderType");
    Object provider = providerType.getEnumConstants()[0];
    Class<?>[] types = {
      providerType, String.class, String.class, int.class, String.class, String.class
    };
    String json =
        (String)
            callStatic(
                EMBEDDING_DOC,
                "toJson",
                types,
                provider,
                "모델/x",
                "https://e.example/v1",
                1536,
                "cipher==",
                "SELF_HOSTED");
    JacksonGoldenFiles.check(DIR, "embeddingConfigDocument.json", json);
    String golden =
        JacksonGoldenFiles.WRITE
            ? json
            : JacksonGoldenFiles.read(DIR, "embeddingConfigDocument.json");
    Object parsed = callStatic(EMBEDDING_DOC, "parse", new Class<?>[] {String.class}, golden);
    Object again =
        callStatic(
            EMBEDDING_DOC,
            "toJson",
            types,
            accessor(parsed, "provider"),
            accessor(parsed, "model"),
            accessor(parsed, "baseUrl"),
            accessor(parsed, "dimension"),
            accessor(parsed, "apiKeyCipher"),
            accessor(parsed, "hosting"));
    assertThat(again).isEqualTo(golden);
    // 깨진 문서는 같은 예외 타입·메시지.
    assertThatThrownBy(
            () -> callStatic(EMBEDDING_DOC, "parse", new Class<?>[] {String.class}, "{bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("임베딩 설정 문서를 해석할 수 없습니다");
    assertThatThrownBy(
            () -> callStatic(EMBEDDING_DOC, "parse", new Class<?>[] {String.class}, "[1]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("임베딩 설정 문서가 객체가 아닙니다");
  }

  // ------------------------------------------------------------------ AiCredentialDocument

  @Test
  void aiCredentialDocument_roundTrip_isByteIdentical() throws Exception {
    AiCredentialDocument doc = AiCredentialDocument.empty("OPENCODE");
    doc.payload().put("model", "모델-1");
    doc.payload().put("n", 3);
    doc.payload().putArray("list").add(1).add("둘");
    doc.withSecret("apiKey", "cipher==").withSecret("token", "");
    String json = doc.toJson();
    JacksonGoldenFiles.check(DIR, "aiCredentialDocument_new.json", json);
    String golden =
        JacksonGoldenFiles.WRITE
            ? json
            : JacksonGoldenFiles.read(DIR, "aiCredentialDocument_new.json");
    assertThat(AiCredentialDocument.parse(golden).toJson()).isEqualTo(golden);
  }

  /** 모르는 최상위·payload 필드도 지우지 않고 그대로(순서 포함) 되살린다. */
  @Test
  void aiCredentialDocument_unknownFieldsPreserved() throws Exception {
    String stored =
        "{\"v\":1,\"agentType\":\"X\",\"payload\":{\"a\":1,\"z\":[1,2],\"nested\":{\"u\":true}},\"secret\":{\"k\":\"c\"},\"extra\":{\"u\":null}}";
    AiCredentialDocument doc = AiCredentialDocument.parse(stored);
    JacksonGoldenFiles.check(DIR, "aiCredentialDocument_unknown.json", doc.toJson());
    assertThat(doc.toJson()).isEqualTo(stored);
  }

  @Test
  void aiCredentialDocument_malformed_sameError() {
    assertThatThrownBy(() -> AiCredentialDocument.parse("{bad"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ai.credential JSON 파싱에 실패했다: {bad");
    assertThatThrownBy(() -> AiCredentialDocument.parse("[1]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("ai.credential JSON 은 객체여야 한다: [1]");
  }

  // ------------------------------------------------------------------
  // OpencodeProbeService.parseModels (private → 리플렉션)

  // 외부 응답을 읽기만 하고 다시 쓰지 않아 왕복 확인은 하지 않는다.
  @Test
  void opencodeProbe_parseModels() throws Exception {
    String[] bodies = {
      "{\"data\":[{\"id\":\"m1\"},{\"id\":\" \"},{\"id\":\"모델2\"},{}]}",
      "[{\"id\":\"a\"},{\"id\":\"b\"}]",
      "{\"data\":{}}",
      "{bad",
      ""
    };
    StringBuilder sb = new StringBuilder();
    for (String body : bodies) {
      sb.append(
              callStatic(
                  "com.smartfirehub.settings.service.OpencodeProbeService",
                  "parseModels",
                  new Class<?>[] {String.class},
                  body))
          .append('\n');
    }
    JacksonGoldenFiles.check(DIR, "opencodeProbe_parseModels.json", sb.toString().stripTrailing());
  }

  // ------------------------------------------------------------------ QdrantCollectionToken (패키지
  // 전용 → 리플렉션)

  @Test
  void qdrantCollectionToken_roundTrip_isByteIdentical() throws Exception {
    String token =
        (String)
            callStatic(
                "com.smartfirehub.vectorstore.qdrant.QdrantCollectionToken",
                "issue",
                new Class<?>[] {
                  String.class, java.util.Collection.class, Instant.class, Duration.class
                },
                "test-api-key",
                List.of("rows_t1", "rows_t2"),
                Instant.parse("2026-01-15T01:00:00Z"),
                Duration.ofMinutes(5));
    // 헤더·클레임 안의 Map.of 순서는 JVM 마다 달라 토큰 문자열 자체는 실행마다 바뀐다(현행 동작). 그래서 조각을 풀어 키 순서를 정렬한
    // 정규 JSON 을 골든으로 하고, 서명은 같은 키로 다시 계산해 일치함을 확인한다.
    String[] parts = token.split("\\.");
    assertThat(parts).hasSize(3);
    StringBuilder canonical = new StringBuilder();
    for (int i = 0; i < 2; i++) {
      String original = new String(Base64.getUrlDecoder().decode(parts[i]), StandardCharsets.UTF_8);
      Object read = mapper.readValue(original, Object.class);
      // 되읽어 다시 쓴 결과가 원래 조각과 같다(순서 보존).
      assertThat(mapper.writeValueAsString(read)).isEqualTo(original);
      canonical.append(mapper.writeValueAsString(sortKeys(read))).append('\n');
    }
    JacksonGoldenFiles.check(
        DIR, "qdrantCollectionToken.json", canonical.toString().stripTrailing());
    javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
    mac.init(
        new javax.crypto.spec.SecretKeySpec(
            "test-api-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    String signature =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
    assertThat(parts[2]).isEqualTo(signature);
  }

  /** Map 키를 알파벳 순으로 정렬한 사본(중첩 포함)을 만든다 — 순서가 실행마다 바뀌는 값을 안정적으로 비교하려고. */
  private static Object sortKeys(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new java.util.TreeMap<>();
      map.forEach((k, v) -> sorted.put(String.valueOf(k), sortKeys(v)));
      return sorted;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(JacksonGoldenStoredJsonTest::sortKeys).toList();
    }
    return value;
  }

  // ------------------------------------------------------------------ LocalPythonOutputLoader (패키지
  // 전용 → 리플렉션)

  private static final String PY_LOADER =
      "com.smartfirehub.pipeline.service.LocalPythonOutputLoader";

  // 읽기 전용(스크립트 stdout 해석)이라 왕복 확인은 하지 않는다.
  @Test
  void localPythonOutputLoader_parseStdoutJson() throws Exception {
    String[] stdouts = {
      "[{\"a\":1,\"b\":1.5,\"c\":\"한글\",\"d\":null,\"e\":[1,2],\"f\":{\"g\":true}}]",
      "[{\"nan\":NaN,\"inf\":Infinity,\"neg\":-Infinity}]",
      "[]",
      "  ",
      "{\"a\":1}",
      "[{\"a\":1}] trailing",
      "not json at all"
    };
    StringBuilder sb = new StringBuilder();
    for (String stdout : stdouts) {
      try {
        sb.append(callStatic(PY_LOADER, "parseStdoutJson", new Class<?>[] {String.class}, stdout));
      } catch (Exception e) {
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
      }
      sb.append('\n');
    }
    JacksonGoldenFiles.check(
        DIR, "localPythonOutputLoader_parseStdoutJson.json", sb.toString().stripTrailing());
  }

  // 적재용 값으로만 나가고 다시 읽히지 않아 왕복 확인은 하지 않는다.
  @Test
  void localPythonOutputLoader_nestedValuesToJsonString() throws Exception {
    List<Map<String, Object>> rows = new ArrayList<>();
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("obj", storedMapForLoader());
    row.put("arr", List.of(1, "둘", Map.of("k", List.of())));
    row.put("nan", Double.NaN);
    row.put("odt", OffsetDateTime.of(2026, 1, 15, 10, 0, 0, 0, ZoneOffset.ofHours(9)));
    rows.add(row);
    callStatic(PY_LOADER, "toTiberoStagingValues", new Class<?>[] {List.class}, rows);
    JacksonGoldenFiles.check(
        DIR, "localPythonOutputLoader_nested.json", String.valueOf(rows.get(0)));
  }

  private static Map<String, Object> storedMapForLoader() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("b", 1);
    m.put("a", "가\"나");
    m.put("n", null);
    return m;
  }

  // ------------------------------------------------------------------ SlackBlockKitRenderer

  @Test
  void slackBlockKitRenderer_roundTrip_isByteIdentical() throws Exception {
    Payload payload =
        new Payload(
            Payload.PayloadType.STANDARD,
            "제목 \"인용\"",
            "요약 *굵게*",
            List.of(new Payload.Section("머리", "본문\n둘째 줄"), new Payload.Section(null, "본문만")),
            List.of(new Payload.Link("열기", "https://example.com/a?b=1&c=2")),
            List.of(),
            Map.of(),
            null);
    SlackBlockKitRenderer renderer = new SlackBlockKitRenderer(mapper);
    String blocks = renderer.renderBlocksJson(payload);
    JacksonGoldenFiles.check(DIR, "slackBlockKitRenderer.json", blocks);
    String golden =
        JacksonGoldenFiles.WRITE
            ? blocks
            : JacksonGoldenFiles.read(DIR, "slackBlockKitRenderer.json");
    assertThat(mapper.writeValueAsString(mapper.readTree(golden))).isEqualTo(golden);
    // 빈 페이로드는 빈 배열.
    assertThat(
            renderer.renderBlocksJson(
                new Payload(
                    Payload.PayloadType.STANDARD, null, null, null, null, null, null, null)))
        .isEqualTo("[]");
  }

  // ------------------------------------------------------------------ SlackApiClient 요청 본문

  // 외부(Slack)로만 나가고 다시 읽히지 않아 왕복 확인은 하지 않는다.
  @Test
  void slackApiClient_requestBodies() throws Exception {
    wireMock.resetAll();
    wireMock.stubFor(
        post(urlEqualTo("/chat.postMessage"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));
    wireMock.stubFor(
        post(urlEqualTo("/reactions.add"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));
    wireMock.stubFor(
        post(urlEqualTo("/chat.postEphemeral"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true}")));
    // 운영 생성자와 같은 공유 코덱 팩토리 빌더로 만든다 — 요청 본문을 그 코덱이 쓴다(WD-793).
    WebClient webClient =
        AppJsonWebClients.webClientBuilder().baseUrl("http://localhost:" + wireMock.port()).build();
    var ctor = SlackApiClient.class.getDeclaredConstructor(WebClient.class, ObjectMapper.class);
    ctor.setAccessible(true);
    SlackApiClient client = ctor.newInstance(webClient, mapper);
    client.reactionsAdd("tok", "C1", "1.0001", "eyes");
    client.postEphemeral("tok", "C1", "U1", "임시 \"메시지\"");
    client.chatPostMessageInThread(
        "tok",
        "C1",
        "1.0001",
        "[{\"type\":\"section\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"가\"}}]",
        "대체 텍스트");
    JacksonGoldenFiles.check(DIR, "slackApiClient_requestBodies.json", requestBodies());
  }

  // ------------------------------------------------------------------ GraphMutationClient

  // 외부(ai-agent)로만 나가고 다시 읽히지 않아 왕복 확인은 하지 않는다.
  @Test
  void graphMutationClient_requestBody_and_conflictMessage() throws Exception {
    wireMock.resetAll();
    wireMock.stubFor(
        post(urlEqualTo("/agent/graph/merge-entities"))
            .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{}")));
    GraphMutationClient client =
        new GraphMutationClient("http://localhost:" + wireMock.port(), "tok");
    // 대행 주체(사용자·테넌트)가 요청 컨텍스트에 있어야 원격 호출까지 간다.
    SecurityContextHolder.getContext()
        .setAuthentication(new UsernamePasswordAuthenticationToken(42L, null, List.of()));
    TenantContext.set(7L);
    try {
      client.mergeEntities("사람", "홍길동", "홍 길동", 7L);
    } finally {
      SecurityContextHolder.clearContext();
      TenantContext.clear();
    }
    StringBuilder sb = new StringBuilder(requestBodies()).append('\n');
    Method m =
        GraphMutationClient.class.getDeclaredMethod("conflictMessage", String.class, String.class);
    m.setAccessible(true);
    sb.append(m.invoke(client, "{\"error\":\"x\",\"message\":\"이미 병합됨\"}", "병합")).append('\n');
    sb.append(m.invoke(client, "not json", "병합")).append('\n');
    sb.append(m.invoke(client, "{\"message\":\" \"}", "병합"));
    JacksonGoldenFiles.check(DIR, "graphMutationClient.json", sb.toString());
  }

  /** WireMock 이 받은 요청 본문 원문을 수신 순서대로 줄바꿈으로 잇는다(오래된 것부터). */
  private static String requestBodies() {
    List<ServeEvent> events = new ArrayList<>(wireMock.getAllServeEvents());
    java.util.Collections.reverse(events);
    StringBuilder sb = new StringBuilder();
    for (ServeEvent e : events) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(e.getRequest().getUrl()).append(' ').append(e.getRequest().getBodyAsString());
    }
    return sb.toString();
  }

  // ------------------------------------------------------------------ 도우미

  /** 오류 한 건을 「타입: 메시지」 문자열로 만든다. */
  private static String errorOf(ThrowingRunnable r) {
    try {
      r.run();
      return "no error";
    } catch (Exception e) {
      return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /** 검사 예외를 던질 수 있는 실행 단위. */
  private interface ThrowingRunnable {
    void run() throws Exception;
  }

  /** 패키지 전용·private 정적 메서드를 호출한다. 원래 예외를 그대로 다시 던져 오류 타입·메시지를 비교할 수 있게 한다. */
  private static Object callStatic(
      String className, String method, Class<?>[] types, Object... args) throws Exception {
    Method m = Class.forName(className).getDeclaredMethod(method, types);
    m.setAccessible(true);
    try {
      return m.invoke(null, args);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception cause) {
        throw cause;
      }
      throw e;
    }
  }

  /** record 접근자(패키지 전용 record 라 리플렉션)로 값을 꺼낸다. */
  private static Object accessor(Object record, String name) throws Exception {
    Method m = record.getClass().getDeclaredMethod(name);
    m.setAccessible(true);
    return m.invoke(record);
  }
}
