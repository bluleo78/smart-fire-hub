package com.smartfirehub.dbexecutor.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
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
          5L, "TIBERO", "h", 8629, "tb", "GENIA_T5", new StoreDescriptor.Account("u", "p"));

  @Autowired MockMvc mvc;
  @MockitoBean StoreDescriptorClient descriptors;
  @MockitoBean SqlRunner sqlRunner;
  @MockitoBean ProbeRunner probeRunner;
  @MockitoBean TenantPools pools;

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
    verify(sqlRunner, never()).run(any(), anyString(), any(), any());
    verify(probeRunner, never()).probe(any(), anyString(), any());
    verifyNoInteractions(pools);
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
            any(Deadline.class)))
        .thenReturn(
            new SqlExecuteResponse(
                false,
                List.of(row),
                List.of("amount"),
                2,
                "로그",
                "오류",
                new DbError(-10007, "TBR-10007: dup")));

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
}
