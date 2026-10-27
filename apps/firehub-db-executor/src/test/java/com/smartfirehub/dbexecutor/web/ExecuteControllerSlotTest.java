package com.smartfirehub.dbexecutor.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.dbexecutor.sql.Deadline;
import com.smartfirehub.dbexecutor.sql.LoadRunner;
import com.smartfirehub.dbexecutor.sql.ProbeRunner;
import com.smartfirehub.dbexecutor.sql.QueryRunner;
import com.smartfirehub.dbexecutor.sql.SlotQueryRunner;
import com.smartfirehub.dbexecutor.sql.SqlRunner;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.StoreUnavailableException;
import com.smartfirehub.dbexecutor.store.TenantPools;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 슬롯 조회 HTTP 경로(WD-78): 실제 인증 필터를 거쳐 스크립트 토큰의 범위(Review Focus 1)·본문 일치·세대(epoch) 확인과 NDJSON 응답을 본다.
 * 스크립트 토큰이 RO 분석 조회 경로로 새지 않는지가 핵심이다.
 */
@SpringBootTest(
    properties = {
      "app.internal-token=ctl-token",
      "app.script-token-key=test-script-key-0123456789abcdef-32b"
    })
@AutoConfigureMockMvc
class ExecuteControllerSlotTest {

  private static final String KEY = "test-script-key-0123456789abcdef-32b";
  private static final String AUTH = "Internal ctl-token";

  /** 테넌트 7, 슬롯 계정 S3, 스크립트 토큰 세대 2. */
  private static StoreDescriptor descriptor(Long epoch) {
    return new StoreDescriptor(
        7L,
        "TIBERO",
        "h",
        8629,
        "tb",
        "GENIA_T7",
        new StoreDescriptor.Account("GENIA_T7", "p"),
        null,
        List.of(new StoreDescriptor.SlotAccount(3, "GENIA_T7_S3", "s")),
        epoch);
  }

  @Autowired MockMvc mvc;
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

  /** api ScriptTokenIssuer 와 같은 형식으로 테스트 토큰을 만든다(공유 벡터로 형식은 따로 고정). */
  static String token(long tenantId, int slot, long epoch, long exp) throws Exception {
    String msg = "v1." + tenantId + "." + slot + "." + epoch + "." + exp;
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return msg
        + "."
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(mac.doFinal(msg.getBytes(StandardCharsets.UTF_8)));
  }

  private static long inTenMinutes() {
    return System.currentTimeMillis() / 1000 + 600;
  }

  private String validToken;

  @BeforeEach
  void setUp() throws Exception {
    validToken = token(7, 3, 2, inTenMinutes());
    when(descriptors.get(anyLong(), any(Duration.class))).thenReturn(descriptor(2L));
    when(descriptors.getScriptTokenEpoch(anyLong(), any(Duration.class))).thenReturn(2L);
    // 스트리머는 고정 NDJSON 두 줄을 쓴다(형식 자체는 SlotQueryRunnerTest 가 본다).
    doAnswer(
        inv -> {
          OutputStream out = inv.getArgument(4);
          out.write(
              "{\"columns\":[\"a\"]}\n{\"success\":true,\"row_count\":0}\n"
                  .getBytes(StandardCharsets.UTF_8));
          return null;
        })
        .when(slotQueryRunner)
        .stream(any(), anyInt(), anyString(), any(Deadline.class), any(OutputStream.class));
  }

  private MockHttpServletResponse post(String path, String auth, String body) throws Exception {
    return mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  private static final String STREAM_BODY =
      "{\"tenantId\":7,\"query\":\"SELECT a FROM t\",\"read_only\":true,\"readSlot\":3,\"stream\":true}";

  @Test
  void scriptToken_streamsThroughSlotRunner_withTokenTenantAndSlot() throws Exception {
    MockHttpServletResponse res = post("/execute/query", "Script " + validToken, STREAM_BODY);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).isEqualTo("application/x-ndjson");
    assertThat(res.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo("{\"columns\":[\"a\"]}\n{\"success\":true,\"row_count\":0}\n");
    verify(slotQueryRunner).stream(
        eq(descriptor(2L)),
        eq(3),
        eq("SELECT a FROM t"),
        any(Deadline.class),
        any(OutputStream.class));
    // 스크립트 요청은 세대만 5초 신선도로 확인하고 기술자는 일반 TTL 캐시를 쓴다.
    verify(descriptors).getScriptTokenEpoch(eq(7L), any(Duration.class));
    verify(descriptors).get(eq(7L), any(Duration.class));
    verifyNoInteractions(queryRunner);
  }

  /** Review Focus 1: 서명이 맞는 스크립트 토큰이어도 /execute/sql·load·probe 는 401 이고 아무것도 실행하지 않는다. */
  @Test
  void scriptToken_onOtherEndpoints_401() throws Exception {
    String[][] cases = {
      {"/execute/sql", "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\"}"},
      {"/execute/load", "{\"tenantId\":7,\"table\":\"STG_x\",\"rows\":[]}"},
      {"/execute/probe", "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\"}"},
    };
    for (String[] c : cases) {
      assertThat(post(c[0], "Script " + validToken, c[1]).getStatus()).as(c[0]).isEqualTo(401);
    }
    verifyNoInteractions(sqlRunner, loadRunner, probeRunner, queryRunner, slotQueryRunner);
  }

  /** 스크립트 토큰은 readSlot·stream:true 가 있는 조회만 — 없으면 401(RO 분석 조회 경로로 새지 않는다). */
  @Test
  void scriptToken_queryWithoutStreamOrReadSlot_401() throws Exception {
    for (String body :
        new String[] {
          "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true}",
          "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true,\"readSlot\":3}",
          "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"read_only\":true,\"readSlot\":3,\"stream\":false}",
          "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"read_only\":true,\"stream\":true}",
        }) {
      MockHttpServletResponse res = post("/execute/query", "Script " + validToken, body);
      assertThat(res.getStatus()).as(body).isEqualTo(401);
      assertThat(res.getContentAsString(StandardCharsets.UTF_8))
          .isEqualTo("{\"success\":false,\"error\":\"인증되지 않은 요청입니다.\"}");
    }
    verifyNoInteractions(queryRunner, slotQueryRunner, descriptors);
  }

  /** 토큰의 테넌트·슬롯이 이긴다 — 본문 값이 다르거나 없으면 401. */
  @Test
  void scriptToken_bodyTenantOrSlotMismatch_401() throws Exception {
    for (String body :
        new String[] {
          STREAM_BODY.replace("\"tenantId\":7", "\"tenantId\":8"),
          STREAM_BODY.replace("\"readSlot\":3", "\"readSlot\":4"),
          STREAM_BODY.replace("\"tenantId\":7,", ""),
        }) {
      assertThat(post("/execute/query", "Script " + validToken, body).getStatus())
          .as(body)
          .isEqualTo(401);
    }
    verifyNoInteractions(slotQueryRunner, descriptors);
  }

  /** 별도 세대 창구의 값보다 낮은 토큰은 401, 같거나 높으면 통과한다(세대를 얻지 못한 경우는 아래 실패 테스트 — 예외로 거부). */
  @Test
  void scriptToken_epochBelowDescriptor_401() throws Exception {
    when(descriptors.getScriptTokenEpoch(eq(7L), any(Duration.class))).thenReturn(3L);
    assertThat(post("/execute/query", "Script " + validToken, STREAM_BODY).getStatus())
        .isEqualTo(401);

    verifyNoInteractions(slotQueryRunner);

    when(descriptors.getScriptTokenEpoch(eq(7L), any(Duration.class))).thenReturn(1L);
    assertThat(post("/execute/query", "Script " + validToken, STREAM_BODY).getStatus())
        .isEqualTo(200);
  }

  /**
   * 세대 조회 자체가 실패하면(api 재시작·시간 초과·5xx·404) 토큰 문제가 아니므로 401 이 아니라 「기술자를 얻지 못함」과 같은 거부(NDJSON 실패 줄 하나,
   * 200)로 답한다 — 기술자도 묻지 않고 실행하지 않는다(fail-closed). 401 은 세대가 토큰보다 높을 때만이다(최종 리뷰).
   */
  @Test
  void scriptToken_epochLookupFailure_sameRejectionAsDescriptorFailure_noExecution()
      throws Exception {
    for (String status : new String[] {"503", "404", "시간 초과", "연결 실패"}) {
      clearInvocations(descriptors);
      when(descriptors.getScriptTokenEpoch(eq(7L), any(Duration.class)))
          .thenThrow(new StoreUnavailableException(status));

      MockHttpServletResponse res = post("/execute/query", "Script " + validToken, STREAM_BODY);

      assertThat(res.getStatus()).as(status).isEqualTo(200);
      assertThat(res.getContentType()).as(status).isEqualTo("application/x-ndjson");
      assertThat(res.getContentAsString(StandardCharsets.UTF_8))
          .as(status)
          .isEqualTo(
              "{\"success\":false,\"error\":\"테넌트 저장소 정보를 가져오지 못했습니다("
                  + status
                  + ").\",\"db_error\":null}\n");
      verify(descriptors, never()).get(anyLong(), any(Duration.class));
    }
    verifyNoInteractions(slotQueryRunner);
  }

  @Test
  void scriptToken_expired_401() throws Exception {
    String expired = token(7, 3, 2, System.currentTimeMillis() / 1000 - 1);
    assertThat(post("/execute/query", "Script " + expired, STREAM_BODY).getStatus()).isEqualTo(401);
    verifyNoInteractions(slotQueryRunner, descriptors);
  }

  /** 기술자를 못 얻으면 NDJSON 실패 줄 하나(200)로 끝난다 — 스트리밍 요청의 응답 모양을 지킨다. */
  @Test
  void scriptToken_descriptorFailure_singleFailureLine() throws Exception {
    when(descriptors.get(eq(7L), any(Duration.class)))
        .thenThrow(new StoreUnavailableException("503"));

    MockHttpServletResponse res = post("/execute/query", "Script " + validToken, STREAM_BODY);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).isEqualTo("application/x-ndjson");
    assertThat(res.getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(
            "{\"success\":false,\"error\":\"테넌트 저장소 정보를 가져오지 못했습니다(503).\",\"db_error\":null}\n");
    verifyNoInteractions(slotQueryRunner);
  }

  // ---- 내부 토큰(api 대칭용) ----

  /** 실행 전 거부를 NDJSON 실패 줄 하나로 받았는지 본다(스트리밍 요청의 응답 모양). */
  private static void assertSingleNdjsonFailure(MockHttpServletResponse res, String expectedError)
      throws Exception {
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).isEqualTo("application/x-ndjson");
    String body = res.getContentAsString(StandardCharsets.UTF_8);
    assertThat(body).endsWith("\n");
    assertThat(body.split("\n")).hasSize(1);
    assertThat(body).startsWith("{\"success\":false,\"error\":");
    assertThat(body).contains("\"db_error\":null");
    if (expectedError != null) {
      assertThat(body).contains("\"error\":\"" + expectedError + "\"");
    }
  }

  /** stream:true 요청의 실행 전 거부(read_only·query 누락·슬롯 조합)는 내부 토큰에서도 NDJSON 실패 줄 하나다(Minor 3). */
  @Test
  void internalToken_streamRequest_preExecutionRejections_areNdjson() throws Exception {
    assertSingleNdjsonFailure(
        post("/execute/query", AUTH, STREAM_BODY.replace(",\"read_only\":true", "")),
        ExecuteController.READ_ONLY_REQUIRED);
    assertSingleNdjsonFailure(
        post(
            "/execute/query",
            AUTH,
            STREAM_BODY.replace("\"read_only\":true", "\"read_only\":false")),
        ExecuteController.READ_ONLY_REQUIRED);
    assertSingleNdjsonFailure(
        post("/execute/query", AUTH, STREAM_BODY.replace("\"query\":\"SELECT a FROM t\",", "")),
        ExecuteController.BAD_REQUEST);
    assertSingleNdjsonFailure(
        post("/execute/query", AUTH, STREAM_BODY.replace("\"tenantId\":7,", "")),
        ExecuteController.BAD_REQUEST);
    assertSingleNdjsonFailure(
        post(
            "/execute/query",
            AUTH,
            "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"read_only\":true,\"stream\":true}"),
        ExecuteController.STREAM_REQUIRES_SLOT);
    assertSingleNdjsonFailure(
        post("/execute/query", AUTH, STREAM_BODY.replace("\"readSlot\":3", "\"readSlot\":11")),
        ExecuteController.BAD_SLOT);
    verifyNoInteractions(queryRunner, slotQueryRunner, descriptors);
  }

  /** 스크립트 토큰도 같다 — 인증(401)을 통과한 뒤의 형식 거부는 NDJSON 실패 줄 하나. */
  @Test
  void scriptToken_preExecutionRejections_areNdjson() throws Exception {
    assertSingleNdjsonFailure(
        post(
            "/execute/query",
            "Script " + validToken,
            STREAM_BODY.replace(",\"read_only\":true", "")),
        ExecuteController.READ_ONLY_REQUIRED);
    assertSingleNdjsonFailure(
        post(
            "/execute/query",
            "Script " + validToken,
            STREAM_BODY.replace("\"query\":\"SELECT a FROM t\",", "")),
        ExecuteController.BAD_REQUEST);
    verifyNoInteractions(queryRunner, slotQueryRunner, descriptors);
  }

  @Test
  void internalToken_readSlotWithoutStream_rejected() throws Exception {
    MockHttpServletResponse res =
        post(
            "/execute/query",
            AUTH,
            "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"max_rows\":10,\"read_only\":true,\"readSlot\":3}");
    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentAsString(StandardCharsets.UTF_8))
        .contains("\"success\":false")
        .contains(ExecuteController.SLOT_REQUIRES_STREAM);
    verifyNoInteractions(queryRunner, slotQueryRunner);
  }

  @Test
  void internalToken_streamWithoutReadSlot_rejected() throws Exception {
    MockHttpServletResponse res =
        post(
            "/execute/query",
            AUTH,
            "{\"tenantId\":7,\"query\":\"SELECT 1 FROM DUAL\",\"read_only\":true,\"stream\":true}");
    assertSingleNdjsonFailure(res, ExecuteController.STREAM_REQUIRES_SLOT);
    verifyNoInteractions(queryRunner, slotQueryRunner);
  }

  /** 내부 토큰의 슬롯 스트리밍(대칭용)은 본문의 테넌트·슬롯을 쓰고 일반 캐시 기술자를 쓴다. max_rows 는 없어도 된다. */
  @Test
  void internalToken_slotStream_usesBodyValues() throws Exception {
    MockHttpServletResponse res = post("/execute/query", AUTH, STREAM_BODY);

    assertThat(res.getStatus()).isEqualTo(200);
    assertThat(res.getContentType()).isEqualTo("application/x-ndjson");
    verify(slotQueryRunner).stream(
        any(), eq(3), eq("SELECT a FROM t"), any(Deadline.class), any(OutputStream.class));
    verify(descriptors).get(eq(7L), any(Duration.class));
    verifyNoInteractions(queryRunner);
  }

  @Test
  void internalToken_slotOutOfRange_rejected() throws Exception {
    for (int slot : new int[] {0, 11, -1}) {
      MockHttpServletResponse res =
          post(
              "/execute/query",
              AUTH,
              STREAM_BODY.replace("\"readSlot\":3", "\"readSlot\":" + slot));
      assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("\"success\":false");
    }
    verifyNoInteractions(slotQueryRunner);
  }
}
