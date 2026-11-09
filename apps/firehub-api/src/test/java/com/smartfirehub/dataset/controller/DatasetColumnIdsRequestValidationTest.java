package com.smartfirehub.dataset.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.dto.ReorderColumnsRequest;
import com.smartfirehub.dataset.dto.UpdatePrimaryKeysRequest;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 필드 순서 변경·기본 키 일괄 변경의 {@code columnIds} null 검증(WD-604) — HTTP 왕복으로 검증한다.
 *
 * <p>결함: {@code PUT /api/v1/datasets/{id}/columns/reorder} 에 {@code {}}·{@code {"columnIds":null}}
 * 을 보내면 서비스({@code DatasetService.reorderColumns})가 null 목록으로 {@code new HashSet<>(null)} 을 만들다 NPE
 * 가 나 500 이 됐다. 이제 요청 경계(Bean Validation)에서 400 + 한국어 필드 문구로 거부한다. 기본 키 변경은 500 은 아니었지만 목록이 빠지면 모든
 * 기본 키를 조용히 지웠고, null 요소는 「없는 필드: null」로 안내돼 같은 방식으로 막는다 — 빈 배열(기본 키 해제)은 그대로 허용한다.
 *
 * <p>MockMvc 통합 테스트로 하는 이유: 핵심이 「@Valid 가 목록·요소까지 내려가는가」와 「실제 서비스까지 갔을 때 500 이 나던 경로가 막히는가」라서, 서비스를
 * 모킹하는 컨트롤러 단위 테스트로는 회귀를 재현할 수 없다. 본문은 JSON 문자열로 직접 써서 null·누락을 그대로 보낸다.
 */
@AutoConfigureMockMvc
class DatasetColumnIdsRequestValidationTest extends IntegrationTestBase {

  private static final String BASE = "/api/v1/datasets";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;
  private String unique;
  private final List<Long> datasets = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    unique = String.valueOf(System.nanoTime());
    editor = fx.createUser("wd604-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
  }

  @AfterEach
  void tearDown() throws Exception {
    // 만든 데이터셋만 지운다 — 삭제 경로가 카탈로그·물리 테이블을 함께 정리한다.
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token()));
    }
    fx.deleteUser(editor);
  }

  // ── 필드 순서 변경 ────────────────────────────────────────────────────

  /** {} — columnIds 가 빠지면 500 이 아니라 400 + 「필드 순서 목록이 비어 있습니다.」, 순서는 그대로다. */
  @Test
  void reorder_missingColumnIds_returns400() throws Exception {
    long id = createDataset("wd604_rmiss_" + unique);
    List<Long> before = columnIds(id);
    assertValidation400(
        reorderRaw(id, "{}"), "columnIds", ReorderColumnsRequest.COLUMN_IDS_REQUIRED_MESSAGE);
    assertThat(columnIds(id)).isEqualTo(before);
  }

  /** {"columnIds":null} — 명시적 null 도 같은 400. */
  @Test
  void reorder_nullColumnIds_returns400() throws Exception {
    long id = createDataset("wd604_rnull_" + unique);
    assertValidation400(
        reorderRaw(id, "{\"columnIds\":null}"),
        "columnIds",
        ReorderColumnsRequest.COLUMN_IDS_REQUIRED_MESSAGE);
  }

  /** {"columnIds":[null]} — null 요소는 해당 위치(columnIds[0])를 짚는 400. */
  @Test
  void reorder_nullElement_returns400() throws Exception {
    long id = createDataset("wd604_rnullel_" + unique);
    assertValidation400(
        reorderRaw(id, "{\"columnIds\":[null]}"),
        "columnIds[0]",
        ReorderColumnsRequest.COLUMN_ID_REQUIRED_MESSAGE);
  }

  /** [첫 id, null] — 일부만 null 이어도 그 위치(columnIds[1])를 짚는다. */
  @Test
  void reorder_partialNullElement_returns400() throws Exception {
    long id = createDataset("wd604_rpart_" + unique);
    long first = columnIds(id).get(0);
    assertValidation400(
        reorderRaw(id, "{\"columnIds\":[" + first + ",null]}"),
        "columnIds[1]",
        ReorderColumnsRequest.COLUMN_ID_REQUIRED_MESSAGE);
  }

  /** [] — 빈 배열은 기존대로 서비스의 「필드 목록과 일치하지 않습니다」 400 이다(검증 문구로 바뀌지 않는다). */
  @Test
  void reorder_emptyArray_keepsExistingMismatch400() throws Exception {
    long id = createDataset("wd604_rempty_" + unique);
    MvcResult r = reorderRaw(id, "{\"columnIds\":[]}");
    assertThat(r.getResponse().getStatus()).isEqualTo(400);
    assertThat(json(r).path("message").asText()).isEqualTo("필드 순서 요청이 데이터셋의 필드 목록과 일치하지 않습니다.");
  }

  /** 올바른 순서 변경은 계속 204 이고 실제로 순서가 바뀐다. */
  @Test
  void reorder_valid_returns204AndReorders() throws Exception {
    long id = createDataset("wd604_rok_" + unique);
    List<Long> reversed = new ArrayList<>(columnIds(id));
    java.util.Collections.reverse(reversed);
    MvcResult r =
        reorderRaw(id, objectMapper.writeValueAsString(new ReorderColumnsRequest(reversed)));
    assertThat(r.getResponse().getStatus()).isEqualTo(204);
    assertThat(columnIds(id)).isEqualTo(reversed);
  }

  // ── 기본 키 일괄 변경 ──────────────────────────────────────────────────

  /** {} — 목록이 빠지면 기본 키를 조용히 지우지 않고 400, 기존 기본 키는 그대로다. */
  @Test
  void primaryKeys_missingColumnIds_returns400AndKeepsKeys() throws Exception {
    long id = createDataset("wd604_pmiss_" + unique);
    long code = columnIds(id).get(0);
    assertThat(primaryKeysRaw(id, "{\"columnIds\":[" + code + "]}").getResponse().getStatus())
        .isEqualTo(204);
    assertValidation400(
        primaryKeysRaw(id, "{}"),
        "columnIds",
        UpdatePrimaryKeysRequest.COLUMN_IDS_REQUIRED_MESSAGE);
    assertThat(primaryKeyIds(id)).containsExactly(code);
  }

  /** {"columnIds":null} — 명시적 null 도 같은 400. */
  @Test
  void primaryKeys_nullColumnIds_returns400() throws Exception {
    long id = createDataset("wd604_pnull_" + unique);
    assertValidation400(
        primaryKeysRaw(id, "{\"columnIds\":null}"),
        "columnIds",
        UpdatePrimaryKeysRequest.COLUMN_IDS_REQUIRED_MESSAGE);
  }

  /** {"columnIds":[null]} — 「없는 필드: null」 대신 해당 위치를 짚는 400. */
  @Test
  void primaryKeys_nullElement_returns400() throws Exception {
    long id = createDataset("wd604_pnullel_" + unique);
    assertValidation400(
        primaryKeysRaw(id, "{\"columnIds\":[null]}"),
        "columnIds[0]",
        UpdatePrimaryKeysRequest.COLUMN_ID_REQUIRED_MESSAGE);
  }

  /** [] — 빈 배열은 「기본 키 해제」라 계속 204 이고 기본 키가 비워진다. */
  @Test
  void primaryKeys_emptyArray_clearsKeys() throws Exception {
    long id = createDataset("wd604_pempty_" + unique);
    long code = columnIds(id).get(0);
    assertThat(primaryKeysRaw(id, "{\"columnIds\":[" + code + "]}").getResponse().getStatus())
        .isEqualTo(204);
    assertThat(primaryKeyIds(id)).containsExactly(code);
    assertThat(primaryKeysRaw(id, "{\"columnIds\":[]}").getResponse().getStatus()).isEqualTo(204);
    assertThat(primaryKeyIds(id)).isEmpty();
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────

  /** NOT NULL 필드(code)와 NULL 허용 필드(title) 두 개짜리 데이터셋을 만든다 — code 는 기본 키 후보다. */
  private long createDataset(String tableName) throws Exception {
    String body =
        "{\"name\":\"WD604 "
            + tableName
            + "\",\"tableName\":\""
            + tableName
            + "\",\"columns\":["
            + "{\"columnName\":\"code\",\"dataType\":\"TEXT\",\"isNullable\":false},"
            + "{\"columnName\":\"title\",\"dataType\":\"TEXT\",\"isNullable\":true}]}";
    MvcResult r =
        mockMvc
            .perform(
                post(BASE)
                    .header("Authorization", token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andReturn();
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    return id;
  }

  private MvcResult reorderRaw(long id, String body) throws Exception {
    return mockMvc
        .perform(
            put(BASE + "/" + id + "/columns/reorder")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  private MvcResult primaryKeysRaw(long id, String body) throws Exception {
    return mockMvc
        .perform(
            put(BASE + "/" + id + "/primary-keys")
                .header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn();
  }

  /** 상세 조회의 columns 순서(표시 순서)대로 필드 id 를 돌려준다. */
  private List<Long> columnIds(long id) throws Exception {
    List<Long> ids = new ArrayList<>();
    for (JsonNode c : datasetDetail(id).get("columns")) {
      ids.add(c.get("id").asLong());
    }
    return ids;
  }

  private List<Long> primaryKeyIds(long id) throws Exception {
    List<Long> ids = new ArrayList<>();
    for (JsonNode c : datasetDetail(id).get("columns")) {
      if (c.path("isPrimaryKey").asBoolean()) {
        ids.add(c.get("id").asLong());
      }
    }
    return ids;
  }

  private JsonNode datasetDetail(long id) throws Exception {
    MvcResult r =
        mockMvc.perform(get(BASE + "/" + id).header("Authorization", token())).andReturn();
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    return json(r);
  }

  /** 400 이고, errors 의 해당 필드 문구가 기대한 한국어 문장인지 본다 — 웹은 errors 의 첫 문구를 사용자에게 보여 준다. */
  private void assertValidation400(MvcResult r, String field, String expectedMessage)
      throws Exception {
    assertThat(r.getResponse().getStatus())
        .as("응답 본문: %s", r.getResponse().getContentAsString(StandardCharsets.UTF_8))
        .isEqualTo(400);
    JsonNode errors = json(r).get("errors");
    assertThat(errors).as("errors 맵이 있어야 한다").isNotNull();
    assertThat(errors.path(field).asText()).as("필드 %s 문구", field).isEqualTo(expectedMessage);
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token() {
    return "Bearer " + jwt.generateAccessToken(editor, "u" + editor, DEFAULT_TEST_TENANT_ID);
  }
}
