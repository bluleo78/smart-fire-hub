package com.smartfirehub.dbexecutor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * db-executor HTTP 응답 JSON 골든 특성 시험(WD-793) — Jackson 2→3 전환 전후로 응답 본문이 바이트 단위로 같은지 못 박는다.
 *
 * <p>기존 컨트롤러 시험과 같은 방식(MockMvc, 러너는 목)으로 <b>응답 본문 문자열</b>을 그대로 골든과 비교한다. 특히 결과 NUMBER(BigDecimal)는
 * {@code write-bigdecimal-as-plain} 설정 덕에 {@code 1E-7} 이 아니라 {@code 0.0000001} 로 나가야 한다(api 가 읽는
 * JSON 텍스트가 같아야 하므로). 골든은 {@code src/test/resources/jackson-golden/<케이스>.json}, 쓰기는 {@code
 * -PjacksonGoldenWrite=true}({@code jackson.golden.write}).
 */
@SpringBootTest(properties = {"app.internal-token=ctl-token"})
@AutoConfigureMockMvc
// 이 클래스의 응답 본문은 모두 api 로 나가기만 하고 db-executor 가 다시 읽지 않는다 — 그래서 왕복(b) 없이 (a) 골든 비교만 한다.
class JacksonGoldenTest {

  private static final boolean WRITE = Boolean.getBoolean("jackson.golden.write");
  private static final String DIR = "jackson-golden/";
  private static final String AUTH = "Internal ctl-token";
  private static final StoreDescriptor D =
      new StoreDescriptor(
          5L, "TIBERO", "h", 8629, "tb", "GENIA_T5", new StoreDescriptor.Account("u", "p"), null);

  @Autowired MockMvc mvc;
  @MockitoBean StoreDescriptorClient descriptors;
  @MockitoBean SqlRunner sqlRunner;
  @MockitoBean ProbeRunner probeRunner;
  @MockitoBean LoadRunner loadRunner;
  @MockitoBean QueryRunner queryRunner;

  @MockitoBean(name = "tenantPools")
  TenantPools pools;

  @MockitoBean(name = "readOnlyPools")
  TenantPools readOnlyPools;

  /** SQL 실행 응답 — 결과 NUMBER 의 평문 표기·null·다양한 숫자 종류·snake_case·유니코드. */
  @Test
  void sqlResponse_bigDecimalPlain() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("tiny", new BigDecimal("0.0000001"));
    row.put("sciSmall", new BigDecimal("1E-7"));
    row.put("sciBig", new BigDecimal("1E+3"));
    row.put("scaled", new BigDecimal("1.50"));
    row.put("huge", new BigDecimal("12345678901234567890.123456789"));
    row.put("long", 9007199254740993L);
    row.put("double", 1.5);
    row.put("flag", true);
    row.put("text", "한글 \"인용\" \n");
    row.put("nothing", null);
    when(sqlRunner.run(eq(D), eq("SELECT x"), any(), any(Deadline.class), any(), any()))
        .thenReturn(
            new SqlExecuteResponse(
                true, List.of(row, Map.of()), List.of("tiny", "sciSmall"), 2, "로그", null, null));
    String body = postJson("/execute/sql", "{\"tenantId\":5,\"query\":\"SELECT x\"}");
    check("sqlResponse_bigDecimalPlain.json", body);
  }

  /** SQL 실행 실패 응답 — db_error 중첩 객체·null 필드가 그대로 실린다. */
  @Test
  void sqlResponse_failureWithDbError() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(sqlRunner.run(eq(D), anyString(), any(), any(Deadline.class), any(), any()))
        .thenReturn(
            new SqlExecuteResponse(
                false, null, null, 0, "로그", "오류", new DbError(-10007, "23000", "TBR-10007: dup")));
    check(
        "sqlResponse_failureWithDbError.json",
        postJson("/execute/sql", "{\"tenantId\":5,\"query\":\"INSERT x\"}"));
  }

  @Test
  void queryResponse() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("a", new BigDecimal("0.0000001"));
    row.put("b", "x");
    when(queryRunner.run(eq(D), eq("SELECT 1 FROM DUAL"), eq(10), any(Deadline.class)))
        .thenReturn(
            new QueryResponse(
                true, List.of("a", "b"), List.of(row), 1, true, "SELECT", 0, 7L, null, null));
    check(
        "queryResponse.json",
        postJson(
            "/execute/query",
            "{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true}"));
  }

  @Test
  void probeResponse() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(probeRunner.probe(eq(D), eq("SELECT 1 a FROM DUAL"), any(Deadline.class)))
        .thenReturn(
            new ProbeResponse(
                true,
                List.of(
                    new ProbeColumn("a", 2, "NUMBER", 0, -127),
                    new ProbeColumn("b", 12, "VARCHAR", 10, 0)),
                null,
                null));
    check(
        "probeResponse.json",
        postJson("/execute/probe", "{\"tenantId\":5,\"query\":\"SELECT 1 a FROM DUAL\"}"));
  }

  @Test
  void loadResponse() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(loadRunner.load(
            eq(D), eq("STG_0123456789ABCDEF01234567"), any(), any(), any(Deadline.class)))
        .thenReturn(new LoadResponse(true, 2, null, null));
    check(
        "loadResponse.json",
        postJson(
            "/execute/load",
            "{\"tenantId\":5,\"table\":\"STG_0123456789ABCDEF01234567\","
                + "\"rows\":[{\"a\":1},{\"a\":2}],\"column_type_map\":{\"a\":\"INTEGER\"},"
                + "\"timeout_seconds\":60}"));
    // 형식 오류(필수 필드 없음) 응답 본문도 고정한다.
    check("loadResponse_badRequest.json", postJson("/execute/load", "{\"tenantId\":5}"));
  }

  /** 필수 필드가 빠진 요청 — 200 + success=false 본문(한국어)이 그대로다. */
  @Test
  void request_missingTenant_failureBody() throws Exception {
    check(
        "request_missingTenant.json",
        postJson("/execute/sql", "{\"query\":\"SELECT 1 FROM DUAL\"}"));
  }

  /** 알 수 없는 속성은 무시한다(요청 역직렬화). */
  @Test
  void request_unknownProperty_isIgnored() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(sqlRunner.run(eq(D), eq("SELECT x"), any(), any(Deadline.class), any(), any()))
        .thenReturn(new SqlExecuteResponse(true, List.of(), List.of(), 0, "", null, null));
    check(
        "request_unknownProperty.json",
        postJson("/execute/sql", "{\"tenantId\":5,\"query\":\"SELECT x\",\"unknown\":1}"));
  }

  // ------------------------------------------------------------------ 도우미

  private String postJson(String path, String json) throws Exception {
    return mvc.perform(
            post(path)
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  /** 쓰기 모드면 골든을 쓰고, 아니면 문자열 그대로 비교한다(공백 정규화 없음). */
  private static void check(String name, String actual) throws IOException {
    if (WRITE) {
      Path dir = Path.of("src/test/resources/" + DIR);
      Files.createDirectories(dir);
      Files.writeString(dir.resolve(name), actual, StandardCharsets.UTF_8);
      return;
    }
    try (InputStream in =
        JacksonGoldenTest.class.getClassLoader().getResourceAsStream(DIR + name)) {
      assertThat(in).as("골든 파일 없음: " + DIR + name).isNotNull();
      assertThat(actual)
          .as("골든 " + name)
          .isEqualTo(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
