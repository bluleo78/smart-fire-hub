package com.smartfirehub.dbexecutor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SlotQueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 두 실행기 공통 DB 계약 벡터({@code executor-db-contract-vectors.json}, api 테스트 리소스)를 컨트롤러에 그대로 넣어, 응답 JSON
 * 이 {@code responseFields} 를 모두 가지고 예시의 성공·거부 판정이 맞는지 본다. Python 실행기 테스트도 같은 파일을 읽는다.
 */
@SpringBootTest(properties = {"app.internal-token=ctl-token"})
@AutoConfigureMockMvc
class ExecutorContractVectorsTest {

  private static final JsonNode FIXTURE = load();
  private static final ObjectMapper OM = new ObjectMapper();
  private static final StoreDescriptor D =
      new StoreDescriptor(
          1L, "TIBERO", "h", 8629, "tb", "GENIA_T1", new StoreDescriptor.Account("u", "p"), null);

  @Autowired MockMvc mvc;
  @Autowired ExecutionTimeouts timeouts;
  @MockitoBean StoreDescriptorClient descriptors;
  @MockitoBean SqlRunner sqlRunner;
  @MockitoBean ProbeRunner probeRunner;
  @MockitoBean LoadRunner loadRunner;
  @MockitoBean QueryRunner queryRunner;
  @MockitoBean SlotQueryRunner slotQueryRunner;

  @MockitoBean(name = "tenantPools")
  TenantPools pools;

  @MockitoBean(name = "readOnlyPools")
  TenantPools readOnlyPools;

  @MockitoBean(name = "slotPools")
  TenantPools slotPools;

  private static JsonNode load() {
    try {
      return new ObjectMapper()
          .readTree(
              Path.of(
                      "../firehub-api/src/test/resources/fixtures/executor-db-contract-vectors.json")
                  .toFile());
    } catch (Exception e) {
      throw new IllegalStateException("실행기 DB 계약 벡터를 읽지 못했다", e);
    }
  }

  /** 러너는 성공 응답을 돌려주게 해 둔다 — 거부는 컨트롤러 검증에서 나와야 한다. */
  private void stubRunners() throws Exception {
    when(descriptors.get(org.mockito.ArgumentMatchers.anyLong(), any(Duration.class)))
        .thenReturn(D);
    when(sqlRunner.run(any(), anyString(), any(), any(Deadline.class), any()))
        .thenReturn(new SqlExecuteResponse(true, null, null, 1, "로그", null, null));
    when(loadRunner.load(any(), anyString(), any(), any(), any(Deadline.class)))
        .thenReturn(new LoadResponse(true, 1, null, null));
    when(probeRunner.probe(any(), anyString(), any(Deadline.class)))
        .thenReturn(new ProbeResponse(true, List.of(), null, null));
    when(queryRunner.run(any(), anyString(), anyInt(), any(Deadline.class)))
        .thenReturn(
            new QueryResponse(true, List.of(), List.of(), 0, false, "SELECT", 0, 3L, null, null));
  }

  private static List<String> names(JsonNode array) {
    List<String> out = new ArrayList<>();
    array.forEach(n -> out.add(n.isTextual() ? n.asText() : n.get("name").asText()));
    return out;
  }

  @Test
  void examples_responseHasAllContractFields_andOutcomeMatches() throws Exception {
    stubRunners();
    JsonNode endpoints = FIXTURE.get("endpoints");
    assertThat(names(endpoints.get("sql").get("requestFields")))
        .contains("tenantId", "query", "preStatements", "params", "timeout_seconds");
    assertThat(names(endpoints.get("load").get("requestFields")))
        .containsExactly("tenantId", "table", "rows", "column_type_map", "timeout_seconds");
    int checked = 0;
    for (Iterator<Map.Entry<String, JsonNode>> it = endpoints.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode ep = e.getValue();
      for (JsonNode ex : ep.get("examples")) {
        if (ex.has("backend") && !ex.get("backend").asText().equals("db-executor")) {
          continue;
        }
        String label = e.getKey() + " / " + ex.get("name").asText();
        String body =
            mvc.perform(
                    post(ep.get("path").asText())
                        .header("Authorization", "Internal ctl-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(OM.writeValueAsString(ex.get("request"))))
                .andReturn()
                .getResponse()
                .getContentAsString();
        JsonNode res = OM.readTree(body);
        if (ex.get("request").path("stream").asBoolean(false)) {
          // stream:true 요청의 실행 전 거부는 NDJSON 실패 줄 하나다(스트리밍 응답 모양,
          // query.streaming.failureLastLineKeys).
          assertThat(body.split("\n")).as(label).hasSize(1);
          assertThat(res.get("success").asBoolean()).as(label).isFalse();
          assertThat(res.has("error") && res.has("db_error")).as(label).isTrue();
          checked++;
          continue;
        }
        for (String field : names(ep.get("responseFields"))) {
          assertThat(res.has(field)).as(label + " 응답 키 " + field).isTrue();
        }
        assertThat(res.get("success").asBoolean())
            .as(label + " success")
            .isEqualTo(ex.get("expectSuccess").asBoolean());
        if (ex.path("expectErrorPresent").asBoolean(false)) {
          assertThat(res.get("error").asText()).as(label + " error").isNotEmpty();
        }
        checked++;
      }
    }
    assertThat(checked).isGreaterThanOrEqualTo(13);
  }

  /**
   * 「선행 문장 + timeout_seconds」 벡터(WD-236, 스펙 §6): timeout_seconds 는 본문에만 걸린다 — 실행기는 요청값(120)을 본문
   * 제한으로 받고, 도착 시점 기한은 선행 상한(app.sql.pre-statement-timeout-seconds 기본 1800)만큼 더 길다. Python 실행기도 같은
   * 예시를 읽는다.
   */
  @Test
  void preStatementsWithTimeout_vector_timeoutAppliesToBodyOnly() throws Exception {
    stubRunners();
    JsonNode ex = null;
    for (JsonNode e : FIXTURE.get("endpoints").get("sql").get("examples")) {
      if (e.get("name").asText().equals("선행 문장 + timeout_seconds")) {
        ex = e;
      }
    }
    assertThat(ex).as("선행 문장 + timeout_seconds 벡터").isNotNull();
    int requested = ex.get("request").get("timeout_seconds").asInt();
    mvc.perform(
        post("/execute/sql")
            .header("Authorization", "Internal ctl-token")
            .contentType(MediaType.APPLICATION_JSON)
            .content(OM.writeValueAsString(ex.get("request"))));

    ArgumentCaptor<Deadline> deadline = ArgumentCaptor.forClass(Deadline.class);
    verify(sqlRunner)
        .run(
            any(),
            anyString(),
            any(),
            deadline.capture(),
            org.mockito.ArgumentMatchers.eq(requested));
    assertThat(deadline.getValue().totalSeconds()).isEqualTo(1800 + requested);
  }

  /**
   * 기본 제한 벡터(WD-259, 스펙 §4.2 「두 실행기 테스트가 벡터 사례를 읽는다」, 리뷰 I-5): {@code timeout_seconds} 없는 SQL 은 본문
   * 문장 제한이 {@code expectedStatementTimeoutMs} 다. 컨트롤러는 요청값이 없으면 러너에 null 을 넘기고 러너가 {@code
   * app.sql.statement-timeout-seconds} 를 쓰므로, 넘어간 값(null 이면 그 설정값)을 벡터와 대조한다 — 설정 기본값이 바뀌면 여기서 깨진다.
   */
  @Test
  void defaultSqlStatementTimeoutVectors_matchRunnerLimit() throws Exception {
    stubRunners();
    int checked = 0;
    for (JsonNode ex : FIXTURE.get("endpoints").get("sql").get("examples")) {
      if (!ex.has("expectedStatementTimeoutMs")
          || (ex.has("backend") && !ex.get("backend").asText().equals("db-executor"))) {
        continue;
      }
      clearInvocations(sqlRunner);
      mvc.perform(
          post("/execute/sql")
              .header("Authorization", "Internal ctl-token")
              .contentType(MediaType.APPLICATION_JSON)
              .content(OM.writeValueAsString(ex.get("request"))));
      ArgumentCaptor<Integer> requested = ArgumentCaptor.forClass(Integer.class);
      verify(sqlRunner).run(any(), anyString(), any(), any(Deadline.class), requested.capture());
      Integer passed = requested.getValue();
      int effectiveSeconds = passed != null ? passed : timeouts.sql().statementTimeoutSeconds();
      assertThat(effectiveSeconds * 1000L)
          .as(ex.get("name").asText())
          .isEqualTo(ex.get("expectedStatementTimeoutMs").asLong());
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(1);
  }

  /**
   * Tibero 기본 적재 제한 벡터(리뷰 I-5): {@code timeout_seconds} 없는 적재의 요청 전체 기한이 {@code
   * expectedDeadlineSeconds} (app.load.timeout-seconds 기본 1800)다. Python 실행기 테스트도 같은 값을 자기 기본값과
   * 대조한다.
   */
  @Test
  void defaultLoadDeadlineVectors_matchLoadRunnerDeadline() throws Exception {
    stubRunners();
    int checked = 0;
    for (JsonNode ex : FIXTURE.get("endpoints").get("load").get("examples")) {
      if (!ex.has("expectedDeadlineSeconds")
          || !ex.path("backend").asText("").equals("db-executor")) {
        continue;
      }
      clearInvocations(loadRunner);
      mvc.perform(
          post("/execute/load")
              .header("Authorization", "Internal ctl-token")
              .contentType(MediaType.APPLICATION_JSON)
              .content(OM.writeValueAsString(ex.get("request"))));
      ArgumentCaptor<Deadline> deadline = ArgumentCaptor.forClass(Deadline.class);
      verify(loadRunner).load(any(), anyString(), any(), any(), deadline.capture());
      assertThat(deadline.getValue().totalSeconds())
          .as(ex.get("name").asText())
          .isEqualTo(ex.get("expectedDeadlineSeconds").asInt());
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(1);
  }

  @Test
  void readOnlyFalseOrMissing_rejectedWithKoreanMessage_andNothingRuns() throws Exception {
    stubRunners();
    for (String json :
        new String[] {
          "{\"tenantId\":1,\"query\":\"SELECT 1\",\"max_rows\":10,\"read_only\":false}",
          "{\"tenantId\":1,\"query\":\"SELECT 1\",\"max_rows\":10}"
        }) {
      String body =
          mvc.perform(
                  post("/execute/query")
                      .header("Authorization", "Internal ctl-token")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(json))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertThat(OM.readTree(body).get("error").asText()).isEqualTo("읽기 전용 조회만 실행할 수 있습니다.");
    }
    org.mockito.Mockito.verifyNoInteractions(queryRunner);
  }

  /** readSlot 은 stream:true 와 함께일 때만 받는다(WD-78) — 하나만 있으면 한국어 사유로 거부하고 아무것도 실행하지 않는다. */
  @Test
  void readSlotWithoutStream_rejected_andNothingRuns() throws Exception {
    stubRunners();
    String body =
        mvc.perform(
                post("/execute/query")
                    .header("Authorization", "Internal ctl-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"tenantId\":1,\"query\":\"SELECT 1\",\"max_rows\":10,\"read_only\":true,\"readSlot\":2}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode res = OM.readTree(body);
    assertThat(res.get("success").asBoolean()).isFalse();
    assertThat(res.get("error").asText()).isEqualTo("슬롯 조회(readSlot)는 stream:true 로만 실행할 수 있습니다.");
    org.mockito.Mockito.verifyNoInteractions(queryRunner, slotQueryRunner);
  }

  /**
   * 스트리밍 벡터 중 요청 형식 단계에서 거부되는 예시(requestLevel)를 창구로 보낸다 — NDJSON 실패 줄 하나이고 스트리머는 돌지 않는다(Python 실행기도
   * 같은 예시를 자기 창구로 검사한다).
   */
  @Test
  void streamingRequestLevelExamples_singleNdjsonFailureLine() throws Exception {
    stubRunners();
    JsonNode streaming = FIXTURE.get("endpoints").get("query").get("streaming");
    int checked = 0;
    for (JsonNode ex : streaming.get("examples")) {
      if (!ex.path("requestLevel").asBoolean(false)) {
        continue;
      }
      String label = ex.get("name").asText();
      org.springframework.mock.web.MockHttpServletResponse res =
          mvc.perform(
                  post("/execute/query")
                      .header("Authorization", "Internal ctl-token")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(OM.writeValueAsString(ex.get("request"))))
              .andReturn()
              .getResponse();
      assertThat(res.getContentType()).as(label).isEqualTo(streaming.get("contentType").asText());
      String[] lines = res.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).split("\n");
      assertThat(lines).as(label).hasSize(ex.get("expectLines").asInt());
      JsonNode last = OM.readTree(lines[lines.length - 1]);
      assertThat(last.get("success").asBoolean())
          .as(label)
          .isEqualTo(ex.get("expectSuccess").asBoolean());
      assertThat(names(streaming.get("failureLastLineKeys")))
          .as(label)
          .containsExactlyElementsOf(
              () -> {
                java.util.List<String> keys = new java.util.ArrayList<>();
                last.fieldNames().forEachRemaining(keys::add);
                return keys.iterator();
              });
      checked++;
    }
    assertThat(checked).isGreaterThanOrEqualTo(1);
    org.mockito.Mockito.verifyNoInteractions(slotQueryRunner);
  }

  /** 슬롯 스트리밍 응답의 Content-Type 은 계약 벡터 값이다(두 실행기 같음). */
  @Test
  void slotStream_contentTypeMatchesVector() throws Exception {
    stubRunners();
    JsonNode streaming = FIXTURE.get("endpoints").get("query").get("streaming");
    String contentType =
        mvc.perform(
                post("/execute/query")
                    .header("Authorization", "Internal ctl-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        OM.writeValueAsString(streaming.get("examples").get(0).get("request"))))
            .andReturn()
            .getResponse()
            .getContentType();
    assertThat(contentType).isEqualTo(streaming.get("contentType").asText());
  }

  @Test
  void dbError_serializesCodeSqlStateMessage() throws Exception {
    JsonNode e = OM.valueToTree(new DbError(-10007, "23000", "TBR-10007: dup"));
    assertThat(e.get("code").asInt()).isEqualTo(-10007);
    assertThat(e.get("sql_state").asText()).isEqualTo("23000");
    assertThat(e.get("message").asText()).isEqualTo("TBR-10007: dup");
    JsonNode nullState = OM.valueToTree(new DbError(1, null, "m"));
    assertThat(nullState.has("sql_state")).isTrue();
    assertThat(nullState.get("sql_state").isNull()).isTrue();
  }
}
