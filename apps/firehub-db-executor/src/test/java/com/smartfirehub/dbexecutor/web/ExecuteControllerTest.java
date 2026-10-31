package com.smartfirehub.dbexecutor.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.LoadResponse;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import com.smartfirehub.dbexecutor.web.dto.QueryResponse;
import com.smartfirehub.dbexecutor.web.dto.SqlExecuteResponse;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP 계약: 인증(401), 기술자 실패 → 200+success=false(한국어), 응답 JSON 은 snake_case(api SqlExecuteResult 와 같은
 * 이름).
 */
@SpringBootTest(properties = {"app.internal-token=ctl-token"})
@AutoConfigureMockMvc
class ExecuteControllerTest {

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

  @Test
  void withoutToken_401() throws Exception {
    mvc.perform(
            post("/execute/sql")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/execute/probe")
                .header("Authorization", "Internal wrong")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(descriptors, sqlRunner, probeRunner);
  }

  @Test
  void descriptorFailure_returns200WithKoreanErrorAndNeverRuns() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class)))
        .thenThrow(new StoreUnavailableException("503"));

    mvc.perform(
            post("/execute/sql")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\",\"preStatements\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("테넌트 저장소 정보를 가져오지 못했습니다(503)."))
        .andExpect(jsonPath("$.row_count").value(0))
        .andExpect(jsonPath("$.db_error").value(nullValue()));
    mvc.perform(
            post("/execute/probe")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("테넌트 저장소 정보를 가져오지 못했습니다(503)."));
    verify(sqlRunner, never()).run(any(), anyString(), any(), any(), any(), any());
    verify(probeRunner, never()).probe(any(), anyString(), any());
    verifyNoInteractions(pools);
  }

  @Test
  void queryDescriptorFailure_returns200WithKoreanReasonAndNeverRuns() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class)))
        .thenThrow(new StoreUnavailableException("503"));

    mvc.perform(
            post("/execute/query")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("테넌트 저장소 정보를 가져오지 못했습니다(503)."))
        .andExpect(jsonPath("$.row_count").value(0))
        .andExpect(jsonPath("$.truncated").value(false));
    verifyNoInteractions(queryRunner, readOnlyPools);
  }

  @Test
  void queryMissingFields_returns200BadRequest() throws Exception {
    for (String json :
        new String[] {
          "{\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true}",
          "{\"tenantId\":5,\"max_rows\":10,\"read_only\":true}",
          "{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\"}"
        }) {
      mvc.perform(
              post("/execute/query")
                  .header("Authorization", AUTH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.error").value("요청 형식이 올바르지 않습니다."));
    }
    verify(descriptors, never()).get(anyLong(), any());
  }

  @Test
  void querySuccess_isSnakeCase() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(queryRunner.run(eq(D), eq("SELECT 1 FROM DUAL"), eq(10), any(Deadline.class)))
        .thenReturn(
            new QueryResponse(
                true, List.of("a"), List.of(Map.of("a", 1)), 1, true, "SELECT", 0, 1L, null, null));

    mvc.perform(
            post("/execute/query")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":5,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.row_count").value(1))
        .andExpect(jsonPath("$.truncated").value(true))
        .andExpect(jsonPath("$.rows[0].a").value(1));
  }

  @Test
  void missingTenantOrQuery_returns200Failure() throws Exception {
    mvc.perform(
            post("/execute/sql")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"SELECT 1 FROM DUAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("요청 형식이 올바르지 않습니다."));
    verify(descriptors, never()).get(anyLong(), any());
  }

  @Test
  void sqlResponse_isSnakeCase_andPassesRequestThrough() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("amount", new BigDecimal("0.0000001"));
    when(sqlRunner.run(
            eq(D),
            eq("INSERT x"),
            eq(List.of("DELETE FROM \"GENIA_T5\".\"T\"")),
            any(Deadline.class),
            isNull(),
            isNull()))
        .thenReturn(
            new SqlExecuteResponse(
                false,
                List.of(row),
                List.of("amount"),
                2,
                "로그",
                "오류",
                new DbError(-10007, "23000", "TBR-10007: dup")));

    mvc.perform(
            post("/execute/sql")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":5,\"query\":\"INSERT x\","
                        + "\"preStatements\":[\"DELETE FROM \\\"GENIA_T5\\\".\\\"T\\\"\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.row_count").value(2))
        .andExpect(jsonPath("$.execution_log").value("로그"))
        .andExpect(jsonPath("$.error").value("오류"))
        .andExpect(jsonPath("$.db_error.code").value(-10007))
        .andExpect(jsonPath("$.db_error.message").value("TBR-10007: dup"))
        .andExpect(jsonPath("$.columns[0]").value("amount"))
        // BigDecimal 은 지수 표기 없이(write-bigdecimal-as-plain) 실린다.
        .andExpect(
            result ->
                org.assertj.core.api.Assertions.assertThat(
                        result.getResponse().getContentAsString())
                    .contains("\"amount\":0.0000001"));
  }

  /** 파이프라인 실행 id(WD-482)는 러너로 그대로 넘기고, 러너의 취소 응답은 cancelled=true 로 실린다. */
  @Test
  void sqlExecutionId_passedToRunner_andCancelledSerialized() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(sqlRunner.run(eq(D), anyString(), any(), any(Deadline.class), any(), eq(42L)))
        .thenReturn(SqlExecuteResponse.cancelled("로그"));

    mvc.perform(
            post("/execute/sql")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"INSERT x\",\"executionId\":42}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.cancelled").value(true))
        .andExpect(jsonPath("$.error").value(SqlExecuteResponse.CANCELLED_MESSAGE));
  }

  @Test
  void probeResponse_isSnakeCase() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(probeRunner.probe(eq(D), eq("SELECT 1 a FROM DUAL"), any(Deadline.class)))
        .thenReturn(
            new ProbeResponse(
                true, List.of(new ProbeColumn("a", 2, "NUMBER", 0, -127)), null, null));

    mvc.perform(
            post("/execute/probe")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"query\":\"SELECT 1 a FROM DUAL\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.columns[0].name").value("a"))
        .andExpect(jsonPath("$.columns[0].jdbc_type").value(2))
        .andExpect(jsonPath("$.columns[0].type_name").value("NUMBER"))
        .andExpect(jsonPath("$.columns[0].precision").value(0))
        .andExpect(jsonPath("$.columns[0].scale").value(-127))
        .andExpect(jsonPath("$.db_error").value(nullValue()));
  }

  private String postSql(String json) throws Exception {
    return mvc.perform(
            post("/execute/sql")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  void sqlTimeoutSeconds_isCappedByLoadLimit_andPassedToRunner() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(sqlRunner.run(eq(D), anyString(), any(), any(Deadline.class), any(), any()))
        .thenReturn(new SqlExecuteResponse(true, null, null, 1, "로그", null, null));

    postSql("{\"tenantId\":5,\"query\":\"INSERT x\",\"timeout_seconds\":120}");
    postSql("{\"tenantId\":5,\"query\":\"INSERT x\",\"timeout_seconds\":999999}");
    postSql("{\"tenantId\":5,\"query\":\"INSERT x\"}");

    org.mockito.ArgumentCaptor<Integer> seconds =
        org.mockito.ArgumentCaptor.forClass(Integer.class);
    verify(sqlRunner, org.mockito.Mockito.times(3))
        .run(eq(D), anyString(), any(), any(Deadline.class), seconds.capture(), any());
    // 요청값은 app.load.timeout-seconds(1800) 로 자르고, 없으면 null(기존 50/55 그대로).
    org.assertj.core.api.Assertions.assertThat(seconds.getAllValues())
        .containsExactly(120, 1800, null);
  }

  @Test
  void sqlTimeoutSeconds_zeroOrNegative_rejectedWithoutRunning() throws Exception {
    for (String v : new String[] {"0", "-5"}) {
      mvc.perform(
              post("/execute/sql")
                  .header("Authorization", AUTH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"tenantId\":5,\"query\":\"INSERT x\",\"timeout_seconds\":" + v + "}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.error").value("timeout_seconds 는 1 이상이어야 합니다."));
    }
    verify(descriptors, never()).get(anyLong(), any());
    verifyNoInteractions(sqlRunner);
  }

  @Test
  void load_passesRequestThrough_andIsSnakeCase() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class))).thenReturn(D);
    when(loadRunner.load(
            eq(D), eq("STG_0123456789ABCDEF01234567"), any(), any(), any(Deadline.class)))
        .thenReturn(new LoadResponse(true, 2, null, null));

    mvc.perform(
            post("/execute/load")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"tenantId\":5,\"table\":\"STG_0123456789ABCDEF01234567\","
                        + "\"rows\":[{\"a\":1},{\"a\":2}],\"column_type_map\":{\"a\":\"INTEGER\"},"
                        + "\"timeout_seconds\":60}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.rows_loaded").value(2))
        .andExpect(jsonPath("$.db_error").value(nullValue()));
    verify(loadRunner)
        .load(
            eq(D),
            eq("STG_0123456789ABCDEF01234567"),
            eq(List.of(Map.of("a", 1), Map.of("a", 2))),
            eq(Map.of("a", "INTEGER")),
            any(Deadline.class));
  }

  @Test
  void load_badRequest_neverResolvesDescriptor() throws Exception {
    for (String json :
        new String[] {
          "{\"table\":\"STG_0123456789ABCDEF01234567\",\"rows\":[]}",
          "{\"tenantId\":5,\"rows\":[]}",
          "{\"tenantId\":5,\"table\":\"STG_0123456789ABCDEF01234567\"}",
          "{\"tenantId\":5,\"table\":\"STG_0123456789ABCDEF01234567\",\"rows\":[],\"timeout_seconds\":0}"
        }) {
      mvc.perform(
              post("/execute/load")
                  .header("Authorization", AUTH)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success").value(false))
          .andExpect(jsonPath("$.rows_loaded").value(0));
    }
    verify(descriptors, never()).get(anyLong(), any());
    verifyNoInteractions(loadRunner);
  }

  @Test
  void load_descriptorFailure_returns200WithKoreanReason() throws Exception {
    when(descriptors.get(eq(5L), any(Duration.class)))
        .thenThrow(new StoreUnavailableException("503"));

    mvc.perform(
            post("/execute/load")
                .header("Authorization", AUTH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tenantId\":5,\"table\":\"STG_0123456789ABCDEF01234567\",\"rows\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error").value("테넌트 저장소 정보를 가져오지 못했습니다(503)."));
    verifyNoInteractions(loadRunner);
  }
}
