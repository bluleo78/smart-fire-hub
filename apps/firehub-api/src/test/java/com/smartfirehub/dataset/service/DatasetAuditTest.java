package com.smartfirehub.dataset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TestUsers;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 데이터셋 변경 감사(WD-463) — 데이터셋 정보 수정·상태 변경·필드 추가/수정/삭제·기본 키·필드 순서·태그 추가/제거·데이터 탭 행 추가/수정/일괄 삭제.
 *
 * <p>MockMvc 로 실제 엔드포인트를 부른다 — 행위자(userId)가 컨트롤러에서 서비스로 제대로 넘어가는지, 권한 거부(403)가 감사를 남기지 않는지까지 한 번에
 * 확인하려는 것이다.
 *
 * <p>클래스 레벨 {@code @Transactional} 을 쓰지 않는다 — 감사 행은 요청 트랜잭션에서 쓰이므로, 커밋된 행을 별도 트랜잭션({@code
 * inTenantFixture})으로 읽어야 「성공하면 남고, 거부되면 롤백돼 남지 않는다」의 증거가 된다.
 *
 * <p>값 누출 검사: 데이터셋 설명·상태 메모·필드 설명·행 값에 {@link #MARKER} 를 넣고, 감사 설명·metadata 어디에도 나오지 않는지 본다.
 */
@AutoConfigureMockMvc
class DatasetAuditTest extends IntegrationTestBase {

  /** 값이 감사에 새는지 확인할 표식 — 설명·메모·행 값에만 넣고 이름에는 넣지 않는다. */
  private static final String MARKER = "wd463secretmarker";

  private static final String BASE = "/api/v1/datasets";

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private PasswordEncoder passwordEncoder;

  private SecurityFixture fx;
  private long editor;
  private String editorName;
  private long noPermUser;
  private String unique;
  private final List<Long> datasets = new ArrayList<>();

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, passwordEncoder);
    unique = String.valueOf(System.nanoTime());
    editor = fx.createUser("wd463-editor-");
    TestUsers.grantRole(dsl, fixtureTransactionTemplate, editor, DEFAULT_TEST_TENANT_ID, "ADMIN");
    editorName = dsl.fetchValue("select username from \"user\" where id = ?", editor).toString();
    // 역할이 하나도 없는 멤버 — 쓰기 요청이 권한 검사(403)에서 막힌다.
    noPermUser = fx.createUser("wd463-noperm-");
    fx.removeUserRole(noPermUser);
  }

  @AfterEach
  void tearDown() throws Exception {
    for (Long id : datasets) {
      mockMvc.perform(delete(BASE + "/" + id).header("Authorization", token(editor)));
    }
    // 감사 행(user_id FK)·역할·멤버십·사용자 순으로 지운다(TestUsers.cleanup).
    fx.deleteUser(editor);
    fx.deleteUser(noPermUser);
  }

  // ── 픽스처 ──────────────────────────────────────────────────────────

  /** title(TEXT)·amount(INTEGER) 두 필드의 표 데이터셋을 만든다. 설명에는 표식을 넣는다. */
  private long createDataset(String suffix) throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", "WD463 감사 " + suffix + " " + unique);
    body.put("tableName", "wd463_" + suffix + "_" + unique);
    body.put("description", "설명 " + MARKER);
    body.put("columns", List.of(column("title", "TEXT"), column("amount", "INTEGER")));
    MvcResult r = send(post(BASE), body, editor);
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    long id = json(r).get("id").asLong();
    datasets.add(id);
    return id;
  }

  private static Map<String, Object> column(String name, String type) {
    Map<String, Object> c = new LinkedHashMap<>();
    c.put("columnName", name);
    c.put("displayName", name);
    c.put("dataType", type);
    c.put("isNullable", true);
    c.put("isIndexed", false);
    c.put("isPrimaryKey", false);
    return c;
  }

  private JsonNode detail(long datasetId) throws Exception {
    MvcResult r =
        mockMvc
            .perform(get(BASE + "/" + datasetId).header("Authorization", token(editor)))
            .andReturn();
    return json(r);
  }

  private long columnId(long datasetId, String columnName) throws Exception {
    for (JsonNode c : detail(datasetId).get("columns")) {
      if (columnName.equals(c.get("columnName").asText())) {
        return c.get("id").asLong();
      }
    }
    throw new AssertionError("필드가 없다: " + columnName);
  }

  private long addRow(long datasetId, String title, int amount) throws Exception {
    MvcResult r =
        send(
            post(BASE + "/" + datasetId + "/data/rows"),
            Map.of("data", Map.of("title", title, "amount", amount)),
            editor);
    assertThat(r.getResponse().getStatus()).isEqualTo(201);
    return json(r).get("id").asLong();
  }

  private MvcResult send(MockHttpServletRequestBuilder req, Object body, long userId)
      throws Exception {
    return mockMvc
        .perform(
            req.header("Authorization", token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andReturn();
  }

  private JsonNode json(MvcResult r) throws Exception {
    return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  private String token(long userId) {
    return "Bearer " + jwt.generateAccessToken(userId, "u" + userId, DEFAULT_TEST_TENANT_ID);
  }

  /** 지금까지 이 데이터셋에 남은 감사 행 중 가장 큰 id — 이후 요청이 남긴 행만 보려는 기준점. */
  private long mark() {
    return inTenantFixture(
        () ->
            dsl.fetch("select coalesce(max(id), 0) as m from audit_log")
                .get(0)
                .get("m", Long.class));
  }

  /** 기준점 이후 이 데이터셋(resource=dataset, resourceId=id)에 남은 감사 행(id 순). */
  private List<Record> auditsSince(long mark, long datasetId) {
    return inTenantFixture(
        () ->
            dsl.fetch(
                "select user_id, username, action_type, resource, resource_id, description, result,"
                    + " ip_address, metadata::text as metadata from audit_log"
                    + " where id > ? and resource = 'dataset' and resource_id = ? order by id",
                mark,
                String.valueOf(datasetId)));
  }

  /** 정확히 한 행이 남았고 공통 항목(행위자·결과·IP 없음)이 맞으며 값이 새지 않았는지 보고 그 행을 돌려준다. */
  private Record single(long mark, long datasetId, String action) throws Exception {
    List<Record> rows = auditsSince(mark, datasetId);
    assertThat(rows).hasSize(1);
    Record row = rows.get(0);
    assertThat(row.get("action_type", String.class)).isEqualTo(action);
    assertThat(row.get("user_id", Long.class)).isEqualTo(editor);
    assertThat(row.get("username", String.class)).isEqualTo(editorName);
    assertThat(row.get("result", String.class)).isEqualTo("SUCCESS");
    assertThat(row.get("ip_address", String.class)).isNull();
    assertThat(row.get("description", String.class)).doesNotContain(MARKER);
    assertThat(row.get("metadata", String.class)).doesNotContain(MARKER);
    return row;
  }

  private JsonNode meta(Record row) throws Exception {
    return objectMapper.readTree(row.get("metadata", String.class));
  }

  private static String desc(Record row) {
    return row.get("description", String.class);
  }

  private String datasetName(long id) throws Exception {
    return detail(id).get("name").asText();
  }

  // ── 데이터셋 정보 수정 ─────────────────────────────────────────────

  @Test
  void updateDataset_recordsChangedFieldNamesOnly_andNoOpRecordsNothing() throws Exception {
    long ds = createDataset("info");
    String oldName = datasetName(ds);
    String newName = "WD463 바뀐 이름 " + unique;

    long m = mark();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", newName);
    body.put("description", "새 설명 " + MARKER + " 두번째");
    assertThat(send(put(BASE + "/" + ds), body, editor).getResponse().getStatus()).isEqualTo(204);

    Record row = single(m, ds, "UPDATE");
    assertThat(desc(row)).isEqualTo("데이터셋 수정: " + newName + " (바뀐 항목: 이름·설명)");
    JsonNode meta = meta(row);
    assertThat(meta.get("change").asText()).isEqualTo("DATASET_UPDATE");
    assertThat(meta.get("datasetName").asText()).isEqualTo(newName);
    assertThat(meta.get("changedFields").toString()).isEqualTo("[\"name\",\"description\"]");
    // 옛 이름(바뀐 항목의 이전 값)도 싣지 않는다.
    assertThat(row.get("metadata", String.class)).doesNotContain(oldName);

    // 같은 값으로 다시 저장 — 바뀐 것이 없어 남기지 않는다.
    long m2 = mark();
    assertThat(send(put(BASE + "/" + ds), body, editor).getResponse().getStatus()).isEqualTo(204);
    assertThat(auditsSince(m2, ds)).isEmpty();
  }

  @Test
  void updateDataset_duplicateName409_andNoPermission403_recordNothing() throws Exception {
    long ds = createDataset("dup");
    long other = createDataset("dup2");
    long m = mark();

    MvcResult conflict = send(put(BASE + "/" + ds), Map.of("name", datasetName(other)), editor);
    assertThat(conflict.getResponse().getStatus()).isEqualTo(409);

    MvcResult forbidden =
        send(put(BASE + "/" + ds), Map.of("name", "WD463 권한없음 " + unique), noPermUser);
    assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);

    assertThat(auditsSince(m, ds)).isEmpty();
  }

  // ── 상태 변경 ─────────────────────────────────────────────────────

  @Test
  void updateStatus_recordsTransition_withoutNote_andNoOpRecordsNothing() throws Exception {
    long ds = createDataset("status");
    String name = datasetName(ds);
    Map<String, Object> body = Map.of("status", "DEPRECATED", "note", "메모 " + MARKER);

    long m = mark();
    assertThat(send(put(BASE + "/" + ds + "/status"), body, editor).getResponse().getStatus())
        .isEqualTo(200);
    Record row = single(m, ds, "STATUS_CHANGE");
    assertThat(desc(row)).isEqualTo("데이터셋 상태 변경: " + name + " (없음 → 사용 중단)");
    JsonNode meta = meta(row);
    assertThat(meta.get("change").asText()).isEqualTo("STATUS_CHANGE");
    assertThat(meta.get("previousStatus").asText()).isEqualTo("NONE");
    assertThat(meta.get("status").asText()).isEqualTo("DEPRECATED");
    assertThat(meta.get("changedFields").toString()).isEqualTo("[\"status\",\"statusNote\"]");

    // 같은 상태·메모 — 남기지 않는다.
    long m2 = mark();
    assertThat(send(put(BASE + "/" + ds + "/status"), body, editor).getResponse().getStatus())
        .isEqualTo(200);
    assertThat(auditsSince(m2, ds)).isEmpty();

    // 잘못된 상태 값(400) — 남기지 않는다.
    assertThat(
            send(put(BASE + "/" + ds + "/status"), Map.of("status", "BOGUS"), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(auditsSince(m2, ds)).isEmpty();
  }

  // ── 필드 ──────────────────────────────────────────────────────────

  @Test
  void columnAddUpdateDelete_recordFieldNamesOnly() throws Exception {
    long ds = createDataset("field");
    String name = datasetName(ds);

    // 추가
    long m = mark();
    Map<String, Object> add = column("memo", "TEXT");
    add.put("description", "필드 설명 " + MARKER);
    MvcResult added = send(post(BASE + "/" + ds + "/columns"), add, editor);
    assertThat(added.getResponse().getStatus()).isEqualTo(201);
    long memoId = json(added).get("id").asLong();
    Record addRow = single(m, ds, "UPDATE");
    assertThat(desc(addRow)).isEqualTo("필드 추가: " + name + ".memo");
    assertThat(meta(addRow).get("change").asText()).isEqualTo("FIELD_ADD");
    assertThat(meta(addRow).get("columnId").asLong()).isEqualTo(memoId);
    assertThat(meta(addRow).get("columnName").asText()).isEqualTo("memo");

    // 수정 — 표시 이름·설명만 바꾼다(나머지 칸은 그대로 다시 보낸다).
    long m2 = mark();
    Map<String, Object> upd = new LinkedHashMap<>();
    upd.put("columnName", "memo");
    upd.put("displayName", "메모");
    upd.put("dataType", "TEXT");
    upd.put("isNullable", true);
    upd.put("isIndexed", false);
    upd.put("description", "새 필드 설명 " + MARKER + " 2");
    assertThat(
            send(put(BASE + "/" + ds + "/columns/" + memoId), upd, editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record updRow = single(m2, ds, "UPDATE");
    assertThat(desc(updRow)).isEqualTo("필드 수정: " + name + ".memo (바뀐 항목: 표시 이름·설명)");
    assertThat(meta(updRow).get("change").asText()).isEqualTo("FIELD_UPDATE");
    assertThat(meta(updRow).get("changedFields").toString())
        .isEqualTo("[\"displayName\",\"description\"]");
    assertThat(row(updRow)).doesNotContain("메모\"");

    // 같은 값으로 다시 — 남기지 않는다.
    long m3 = mark();
    assertThat(
            send(put(BASE + "/" + ds + "/columns/" + memoId), upd, editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    assertThat(auditsSince(m3, ds)).isEmpty();

    // 잘못된 타입(400) — 남기지 않는다.
    Map<String, Object> bad = new LinkedHashMap<>(upd);
    bad.put("dataType", "NOPE");
    assertThat(
            send(put(BASE + "/" + ds + "/columns/" + memoId), bad, editor)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    // 같은 이름 필드 추가(409) — 남기지 않는다.
    assertThat(
            send(post(BASE + "/" + ds + "/columns"), column("memo", "TEXT"), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(auditsSince(m3, ds)).isEmpty();

    // 삭제 — 데이터가 사라지므로 DELETE
    long m4 = mark();
    assertThat(
            mockMvc
                .perform(
                    delete(BASE + "/" + ds + "/columns/" + memoId)
                        .header("Authorization", token(editor)))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record delRow = single(m4, ds, "DELETE");
    assertThat(desc(delRow)).isEqualTo("필드 삭제: " + name + ".memo");
    assertThat(meta(delRow).get("change").asText()).isEqualTo("FIELD_DELETE");
    assertThat(meta(delRow).get("columnName").asText()).isEqualTo("memo");
  }

  /** 표시 이름 「메모」(바뀐 값)가 metadata 에 값으로 실리지 않았는지 볼 때 쓰는 metadata 원문. */
  private static String row(Record r) {
    return r.get("metadata", String.class) + r.get("description", String.class);
  }

  @Test
  void primaryKeysAndReorder_recordOnlyWhenChanged() throws Exception {
    long ds = createDataset("pk");
    String name = datasetName(ds);
    long titleId = columnId(ds, "title");
    long amountId = columnId(ds, "amount");

    // 기본 키는 NOT NULL 필드여야 한다 — title 을 NOT NULL 로 바꾼다(이 수정도 감사 대상).
    Map<String, Object> notNull = new LinkedHashMap<>();
    notNull.put("isNullable", false);
    send(put(BASE + "/" + ds + "/columns/" + titleId), notNull, editor);

    long m = mark();
    assertThat(
            send(
                    put(BASE + "/" + ds + "/primary-keys"),
                    Map.of("columnIds", List.of(titleId)),
                    editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record pk = single(m, ds, "UPDATE");
    assertThat(desc(pk)).isEqualTo("기본 키 변경: " + name + " (기본 키: title)");
    assertThat(meta(pk).get("change").asText()).isEqualTo("PRIMARY_KEY_CHANGE");
    assertThat(meta(pk).get("primaryKeyColumns").toString()).isEqualTo("[\"title\"]");

    // 같은 집합 — 남기지 않는다.
    long m2 = mark();
    send(put(BASE + "/" + ds + "/primary-keys"), Map.of("columnIds", List.of(titleId)), editor);
    assertThat(auditsSince(m2, ds)).isEmpty();

    // 순서 변경
    long m3 = mark();
    assertThat(
            send(
                    put(BASE + "/" + ds + "/columns/reorder"),
                    Map.of("columnIds", List.of(amountId, titleId)),
                    editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record order = single(m3, ds, "UPDATE");
    assertThat(desc(order)).isEqualTo("필드 순서 변경: " + name);
    assertThat(meta(order).get("change").asText()).isEqualTo("FIELD_REORDER");

    // 같은 순서 — 남기지 않는다.
    long m4 = mark();
    send(
        put(BASE + "/" + ds + "/columns/reorder"),
        Map.of("columnIds", List.of(amountId, titleId)),
        editor);
    assertThat(auditsSince(m4, ds)).isEmpty();
  }

  // ── 태그 ──────────────────────────────────────────────────────────

  @Test
  void tagAddRemove_recorded_duplicate409AndMissing404RecordNothing() throws Exception {
    long ds = createDataset("tag");
    String name = datasetName(ds);
    String tag = "wd463tag";

    long m = mark();
    assertThat(
            send(post(BASE + "/" + ds + "/tags"), Map.of("tagName", tag), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(201);
    Record add = single(m, ds, "UPDATE");
    assertThat(desc(add)).isEqualTo("태그 추가: " + name + " — " + tag);
    assertThat(meta(add).get("change").asText()).isEqualTo("TAG_ADD");
    assertThat(meta(add).get("tag").asText()).isEqualTo(tag);

    long m2 = mark();
    assertThat(
            send(post(BASE + "/" + ds + "/tags"), Map.of("tagName", tag), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(409);
    assertThat(
            mockMvc
                .perform(
                    delete(BASE + "/" + ds + "/tags/wd463missing")
                        .header("Authorization", token(editor)))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(auditsSince(m2, ds)).isEmpty();

    long m3 = mark();
    assertThat(
            mockMvc
                .perform(
                    delete(BASE + "/" + ds + "/tags/" + tag).header("Authorization", token(editor)))
                .andReturn()
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record remove = single(m3, ds, "UPDATE");
    assertThat(desc(remove)).isEqualTo("태그 제거: " + name + " — " + tag);
    assertThat(meta(remove).get("change").asText()).isEqualTo("TAG_REMOVE");
  }

  // ── 데이터 탭 행 ──────────────────────────────────────────────────

  @Test
  void rowAddAndUpdate_recordRowIdAndFieldNames_neverValues() throws Exception {
    long ds = createDataset("row");
    String name = datasetName(ds);

    long m = mark();
    long rowId = addRow(ds, "값 " + MARKER, 4631);
    Record add = single(m, ds, "UPDATE");
    assertThat(desc(add)).isEqualTo("행 추가: " + name + " (행 ID " + rowId + ")");
    JsonNode addMeta = meta(add);
    assertThat(addMeta.get("change").asText()).isEqualTo("ROW_ADD");
    assertThat(addMeta.get("count").asInt()).isEqualTo(1);
    assertThat(addMeta.get("rowIds").toString()).isEqualTo("[" + rowId + "]");
    assertThat(addMeta.get("changedFields").toString()).isEqualTo("[\"title\",\"amount\"]");
    assertThat(row(add)).doesNotContain("4631");

    // 바뀐 칸만 보내는 편집 창과 같게 title 만 바꾼다.
    long m2 = mark();
    assertThat(
            send(
                    put(BASE + "/" + ds + "/data/rows/" + rowId),
                    Map.of("data", Map.of("title", "새 값 " + MARKER)),
                    editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    Record upd = single(m2, ds, "UPDATE");
    assertThat(desc(upd)).isEqualTo("행 수정: " + name + " (행 ID " + rowId + ", 바뀐 항목: title)");
    assertThat(meta(upd).get("change").asText()).isEqualTo("ROW_UPDATE");
    assertThat(meta(upd).get("changedFields").toString()).isEqualTo("[\"title\"]");

    // 바꿀 칸이 없는 요청(쓰기 없음) — 남기지 않는다.
    long m3 = mark();
    assertThat(
            send(put(BASE + "/" + ds + "/data/rows/" + rowId), Map.of("data", Map.of()), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(204);
    // 없는 행(404)·잘못된 값(400) — 남기지 않는다.
    assertThat(
            send(
                    put(BASE + "/" + ds + "/data/rows/999999999"),
                    Map.of("data", Map.of("title", "x")),
                    editor)
                .getResponse()
                .getStatus())
        .isEqualTo(404);
    assertThat(
            send(
                    post(BASE + "/" + ds + "/data/rows"),
                    Map.of("data", Map.of("amount", "숫자아님")),
                    editor)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    // 권한 없는 사용자의 행 추가(403) — 남기지 않는다.
    assertThat(
            send(
                    post(BASE + "/" + ds + "/data/rows"),
                    Map.of("data", Map.of("title", "x")),
                    noPermUser)
                .getResponse()
                .getStatus())
        .isEqualTo(403);
    assertThat(auditsSince(m3, ds)).isEmpty();
  }

  @Test
  void bulkRowDelete_isOneRecordPerRequest_withCountAndCappedRowIds() throws Exception {
    long ds = createDataset("bulk");
    String name = datasetName(ds);
    List<Long> rowIds = new ArrayList<>();
    for (int i = 0; i < 7; i++) {
      rowIds.add(addRow(ds, MARKER + i, 100 + i));
    }

    long m = mark();
    MvcResult r = send(post(BASE + "/" + ds + "/data/delete"), Map.of("rowIds", rowIds), editor);
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    assertThat(json(r).get("deletedCount").asInt()).isEqualTo(7);

    Record del = single(m, ds, "DELETE");
    assertThat(desc(del)).isEqualTo("행 일괄 삭제: " + name + " (7건)");
    JsonNode meta = meta(del);
    assertThat(meta.get("change").asText()).isEqualTo("ROWS_DELETE");
    assertThat(meta.get("count").asInt()).isEqualTo(7);
    assertThat(meta.get("rowIds").size()).isEqualTo(DatasetAuditRecorder.MAX_ROW_IDS);
    assertThat(meta.get("rowIds").get(0).asLong()).isEqualTo(rowIds.get(0));

    // 이미 지운 행을 다시 지움(0건) — 바뀐 것이 없어 남기지 않는다. 빈 목록(400)도 남기지 않는다.
    long m2 = mark();
    send(post(BASE + "/" + ds + "/data/delete"), Map.of("rowIds", rowIds), editor);
    assertThat(
            send(post(BASE + "/" + ds + "/data/delete"), Map.of("rowIds", List.of()), editor)
                .getResponse()
                .getStatus())
        .isEqualTo(400);
    assertThat(auditsSince(m2, ds)).isEmpty();
  }

  @Test
  void cappedRowIds_keepsFirstDistinctIdsInRequestOrder() {
    assertThat(DatasetAuditRecorder.cappedRowIds(List.of(9L, 3L, 9L, 1L, 2L, 8L, 7L)))
        .containsExactly(9L, 3L, 1L, 2L, 8L);
  }
}
