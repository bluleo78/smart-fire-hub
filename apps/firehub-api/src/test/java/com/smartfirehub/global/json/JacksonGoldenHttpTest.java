package com.smartfirehub.global.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartfirehub.global.exception.GlobalExceptionHandler;
import com.smartfirehub.global.platformdb.PlatformDbErrors;
import com.smartfirehub.global.security.JwtProperties;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * HTTP JSON 골든 특성 시험(WD-793) — Jackson 2→3 전환 전후로 응답·요청 JSON 이 바이트 단위로 같은지 못 박는다.
 *
 * <p>Jackson 타입에 기대지 않고 MockMvc 로 실제 HTTP 메시지 변환기를 거친 <b>응답 본문 문자열</b>을 골든 파일과 그대로 비교한다. 그래서 전환 작업은
 * 이 시험의 import 만 고치면 같은 골든으로 다시 돌릴 수 있다(공백 정규화 없음, 골든은 끝 줄바꿈 없이 저장).
 *
 * <p>골든 파일은 {@code src/test/resources/jackson-golden/http/<케이스>.json}. 시스템 속성 {@code
 * -Djackson.golden.write=true}(Gradle 에서는 {@code -PjacksonGoldenWrite=true})일 때만 현재 응답으로 다시 쓴다.
 * 평소에는 비교만 한다.
 *
 * <p>오류 응답의 {@code timestamp} 는 {@code Instant.now()} 라 고정할 수 없어 값만 자리표시자로 바꿔 비교한다(그 밖의 모든 글자는 그대로
 * 비교).
 */
@ActiveProfiles(JacksonGoldenFixtureController.PROFILE)
@WebMvcTest(controllers = JacksonGoldenFixtureController.class)
// 보안 필터 체인은 끈다 — 이 시험은 인증이 아니라 JSON 변환기 경로만 본다.
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonGoldenFixtureController.class, GlobalExceptionHandler.class})
class JacksonGoldenHttpTest {

  /** 골든 파일 위치(클래스패스 기준). */
  private static final String GOLDEN_DIR = "jackson-golden/http/";

  // WebMvcConfig 가 인터셉터(→ 하위 빈)를 등록하므로 슬라이스에도 있어야 한다. 시험 경로(/golden)는 인터셉터 대상이 아니다.
  @MockitoBean private DatasetAccessGuard datasetAccessGuard;
  @MockitoBean private ClearanceResolver clearanceResolver;
  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private JwtProperties jwtProperties;
  @MockitoBean private PermissionService permissionService;
  // 전역 예외 처리기의 생성자 의존성 — 이 시험의 오류 경로에서는 호출되지 않는다.
  @MockitoBean private PlatformDbErrors platformDbErrors;

  @Autowired private MockMvc mockMvc;

  private static TimeZone originalTimeZone;

  /** StorageZoneDateTimeSerializer 는 JVM 기본 시간대로 오프셋을 붙이므로 비-UTC 로 고정해 오프셋이 실제로 드러나게 한다. */
  @BeforeAll
  static void pinTimeZone() {
    originalTimeZone = TimeZone.getDefault();
    TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"));
  }

  /** 다른 시험에 영향이 없도록 JVM 기본 시간대를 되돌린다. */
  @AfterAll
  static void restoreTimeZone() {
    TimeZone.setDefault(originalTimeZone);
  }

  // ------------------------------------------------------------------ 응답(GET)

  @Test
  void response_auditLog() throws Exception {
    assertGet("/golden/audit-log", 200, "response_auditLog");
  }

  @Test
  void response_rowData() throws Exception {
    assertGet("/golden/row-data", 200, "response_rowData");
  }

  @Test
  void response_platformAccount() throws Exception {
    assertGet("/golden/platform-account", 200, "response_platformAccount");
  }

  @Test
  void response_tenantSummary() throws Exception {
    assertGet("/golden/tenant-summary", 200, "response_tenantSummary");
  }

  @Test
  void response_datasetList() throws Exception {
    assertGet("/golden/dataset-list", 200, "response_datasetList");
  }

  @Test
  void response_pipelineExecution() throws Exception {
    assertGet("/golden/pipeline-execution", 200, "response_pipelineExecution");
  }

  @Test
  void response_dates() throws Exception {
    assertGet("/golden/dates", 200, "response_dates");
  }

  @Test
  void response_decimals() throws Exception {
    assertGet("/golden/decimals", 200, "response_decimals");
  }

  @Test
  void response_optional() throws Exception {
    assertGet("/golden/optional", 200, "response_optional");
  }

  @Test
  void response_enum() throws Exception {
    assertGet("/golden/enum", 200, "response_enum");
  }

  @Test
  void response_emptyList() throws Exception {
    assertGet("/golden/empty-list", 200, "response_emptyList");
  }

  @Test
  void response_nulls() throws Exception {
    assertGet("/golden/nulls", 200, "response_nulls");
  }

  @Test
  void response_linkedMapKeepsInsertionOrder() throws Exception {
    assertGet("/golden/linked-map", 200, "response_linkedMapKeepsInsertionOrder");
  }

  @Test
  void response_declarationOrder() throws Exception {
    assertGet("/golden/declaration-order", 200, "response_declarationOrder");
  }

  @Test
  void response_errorNotFound() throws Exception {
    assertGet("/golden/error-not-found", 404, "response_errorNotFound");
  }

  // ------------------------------------------------------------------ 요청(POST 에코)

  @Test
  void request_normal_roundTrip() throws Exception {
    assertPost(
        "/golden/echo",
        "{\"name\":\"가나\",\"count\":3,\"flag\":true}",
        200,
        "request_normal_roundTrip");
  }

  @Test
  void request_unknownProperty_isIgnored() throws Exception {
    assertPost(
        "/golden/echo", "{\"name\":\"a\",\"unknown\":1}", 200, "request_unknownProperty_isIgnored");
  }

  @Test
  void request_nullForPrimitive_becomesDefault() throws Exception {
    assertPost(
        "/golden/echo",
        "{\"name\":\"a\",\"count\":null,\"flag\":null}",
        200,
        "request_nullForPrimitive_becomesDefault");
  }

  @Test
  void request_emptyStringToObject() throws Exception {
    assertPost(
        "/golden/echo-nested",
        "{\"name\":\"a\",\"inner\":\"\"}",
        null,
        "request_emptyStringToObject");
  }

  @Test
  void request_singleValueAsArray() throws Exception {
    assertPost(
        "/golden/echo-tags", "{\"name\":\"a\",\"tags\":\"x\"}", null, "request_singleValueAsArray");
  }

  @Test
  void request_malformedJson_sameErrorBody() throws Exception {
    assertPost("/golden/echo", "{\"name\":", 400, "request_malformedJson_sameErrorBody");
  }

  @Test
  void request_localDate() throws Exception {
    assertPost("/golden/echo-dates", "{\"localDate\":\"2026-01-15\"}", null, "request_localDate");
  }

  @Test
  void request_localDateTime() throws Exception {
    assertPost(
        "/golden/echo-dates",
        "{\"localDateTime\":\"2026-01-15T10:00:00\"}",
        null,
        "request_localDateTime");
  }

  @Test
  void request_offsetDateTimeFromZ() throws Exception {
    assertPost(
        "/golden/echo-dates",
        "{\"offsetDateTime\":\"2026-01-15T10:00:00Z\"}",
        null,
        "request_offsetDateTimeFromZ");
  }

  @Test
  void request_instantFromZ() throws Exception {
    assertPost(
        "/golden/echo-dates",
        "{\"instant\":\"2026-01-15T10:00:00Z\"}",
        null,
        "request_instantFromZ");
  }

  /** 오프셋 없는 LocalDateTime 자리에 Z 가 붙은 문자열이 오면 현재 어떻게 되는지(성공이든 400 이든) 그대로 못 박는다. */
  @Test
  void request_localDateTimeFromZ() throws Exception {
    assertPost(
        "/golden/echo-dates",
        "{\"localDateTime\":\"2026-01-15T10:00:00Z\"}",
        null,
        "request_localDateTimeFromZ");
  }

  // ------------------------------------------------------------------ 도우미

  /** GET 응답의 상태·본문을 골든과 비교한다. */
  private void assertGet(String path, int expectedStatus, String goldenName) throws Exception {
    MvcResult result = mockMvc.perform(get(path)).andReturn();
    verify(result, expectedStatus, goldenName);
  }

  /** POST 응답의 상태·본문을 골든과 비교한다. expectedStatus 가 null 이면 현재 동작(성공이든 400 이든)을 골든으로만 못 박는다. */
  private void assertPost(String path, String json, Integer expectedStatus, String goldenName)
      throws Exception {
    MvcResult result =
        mockMvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
            .andReturn();
    verify(result, expectedStatus, goldenName);
  }

  /**
   * 상태 코드와 본문을 골든과 비교한다. 본문은 순수 JSON 파일({@code <케이스>.json})로, 상태 코드는 {@code <케이스>.status} 에 따로 둔다 —
   * 본문 파일을 응답 그대로 두어야 문자열 비교가 단순하고, 상태는 성공/400 어느 쪽이든 현재 동작을 함께 못 박기 위해서다.
   */
  private void verify(MvcResult result, Integer expectedStatus, String goldenName)
      throws IOException {
    int status = result.getResponse().getStatus();
    String body = normalize(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    if (expectedStatus != null) {
      assertThat(status).as("HTTP 상태").isEqualTo(expectedStatus);
    }
    String statusText = String.valueOf(status);
    if (JacksonGoldenFiles.WRITE) {
      JacksonGoldenFiles.write(GOLDEN_DIR, goldenName + ".json", body);
      JacksonGoldenFiles.write(GOLDEN_DIR, goldenName + ".status", statusText);
      return;
    }
    assertThat(JacksonGoldenFiles.read(GOLDEN_DIR, goldenName + ".status"))
        .as("HTTP 상태(골든)")
        .isEqualTo(statusText);
    assertThat(body)
        .as("응답 본문(골든 " + goldenName + ")")
        .isEqualTo(JacksonGoldenFiles.read(GOLDEN_DIR, goldenName + ".json"));
  }

  /** 시각이 매번 달라지는 오류 본문의 timestamp 값만 자리표시자로 바꾼다. */
  private static String normalize(String body) {
    return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<TS>\"");
  }
}
