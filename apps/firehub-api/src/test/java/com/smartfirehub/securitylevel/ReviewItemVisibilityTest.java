package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 최종 리뷰 C2: GraphRAG 검수 항목은 출처 데이터셋의 청크 원문·엔티티 이름을 싣는데 경로가 {@code /api/v1/datasets/**} 밖이라 인터셉터가 닿지
 * 않는다. 출처 데이터셋을 볼 수 없는 조회자에게는 목록에서 빠지고, 근거·승인·거부는 없는 항목과 같은 404 여야 한다. 볼 수 있는 조회자에게는 같은 항목이 보이는 양성
 * 대조를 둔다(필터가 전부를 지우는 공허한 통과 방지).
 */
@AutoConfigureMockMvc
class ReviewItemVisibilityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private ReviewItemRepository reviewRepo;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private final List<Long> datasets = new ArrayList<>();
  private String marker;
  private String itemType;
  private long low;
  private long high;
  private long hiddenItem;
  private long visibleItem;
  private long hiddenDecidedItem;
  private long creator;
  private long hiddenDs;
  private long visibleDs;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "riv" + System.nanoTime();
    // 서비스는 item_type 값을 검증하지 않으므로 테스트 전용 타입으로 목록을 격리한다(공유 DB 의 다른 항목과 섞이지 않게).
    itemType = "test_" + marker;
    creator = fx.createUser("riv_c");
    users.add(creator);
    // 같은 권한(dataset:read·write), 자격만 다른 두 사용자.
    low = userAt("riv_low", "공개");
    high = userAt("riv_high", "민감");
    hiddenDs = fx.createDatasetRow(marker + "_hidden", fx.levelId("민감"), creator);
    visibleDs = fx.createDatasetRow(marker + "_visible", fx.levelId("공개"), creator);
    datasets.add(hiddenDs);
    datasets.add(visibleDs);
    hiddenItem = insertItem(hiddenDs, "h");
    visibleItem = insertItem(visibleDs, "v");
    hiddenDecidedItem = insertItem(hiddenDs, "hd");
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> reviewRepo.updateStatus(hiddenDecidedItem, "rejected", creator));
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // decided_by FK 가 사용자 삭제를 막지 않도록 항목을 먼저 지운다.
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            dsl.execute(
                // 결정 조회·등록 테스트는 실제 item_type 을 쓰므로 marker 가 든 dedupe_key 로도 지운다(공유 DB 누수 방지).
                "delete from graph_review_item where item_type = ? or dedupe_key like ?",
                itemType,
                "%" + marker + "%"));
    datasets.forEach(fx::deleteDatasetRow);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private long userAt(String prefix, String level) {
    long uid = fx.createUser(prefix);
    users.add(uid);
    fx.removeUserRole(uid);
    long rid =
        fx.createRole(prefix + "_r_" + marker, fx.levelId(level), "dataset:read", "dataset:write");
    roles.add(rid);
    fx.assignRole(uid, rid);
    return uid;
  }

  private long insertItem(long datasetId, String key) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          reviewRepo.upsertPending(
              itemType,
              marker + "|" + key,
              datasetId,
              "similarity",
              0.5,
              "근거",
              "{\"entityType\":\"T\",\"nameA\":\"기밀이름\",\"nameB\":\"b\",\"sourceChunkIds\":[]}");
          return ((Number)
                  dsl.fetchValue(
                      "select id from graph_review_item where item_type = ? and dedupe_key = ?",
                      itemType,
                      marker + "|" + key))
              .longValue();
        });
  }

  private String token(long userId) {
    return "Bearer " + jwt.generateAccessToken(userId, "riv" + userId, DEFAULT_TEST_TENANT_ID);
  }

  private List<Long> listIds(long userId, String status) throws Exception {
    JsonNode arr =
        om.readTree(
            mockMvc
                .perform(
                    get("/api/v1/graphrag/review-items")
                        .param("status", status)
                        .param("itemType", itemType)
                        .header("Authorization", token(userId)))
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<Long> ids = new ArrayList<>();
    arr.forEach(n -> ids.add(n.get("id").asLong()));
    return ids;
  }

  private MockHttpServletResponse call(String method, String path, long userId) throws Exception {
    var req =
        "GET".equals(method)
            ? get(path).header("Authorization", token(userId))
            : post(path).header("Authorization", token(userId));
    return mockMvc.perform(req).andReturn().getResponse();
  }

  /** 응답 본문에서 경로·시각을 뺀 비교용 표현 — 숨김 항목과 없는 항목의 응답이 같아야 한다. */
  private String normalized(MockHttpServletResponse r, long id) throws Exception {
    JsonNode n = om.readTree(r.getContentAsString());
    return r.getStatus()
        + "|"
        + n.path("code").asText()
        + "|"
        + n.path("message").asText().replace(String.valueOf(id), "<id>");
  }

  @Test
  void list_excludesItemsOfHiddenDatasets_butClearedUserSeesThem() throws Exception {
    assertThat(listIds(low, "pending")).contains(visibleItem).doesNotContain(hiddenItem);
    assertThat(listIds(high, "pending")).contains(visibleItem, hiddenItem);
    assertThat(listIds(low, "rejected")).doesNotContain(hiddenDecidedItem);
    assertThat(listIds(high, "rejected")).contains(hiddenDecidedItem);
  }

  @Test
  void evidence_ofHiddenItem_isSame404AsMissingItem() throws Exception {
    long missing = Long.MAX_VALUE - 7;
    var hidden = call("GET", "/api/v1/graphrag/review-items/" + hiddenItem + "/evidence", low);
    var none = call("GET", "/api/v1/graphrag/review-items/" + missing + "/evidence", low);
    assertThat(hidden.getStatus()).isEqualTo(404);
    assertThat(normalized(hidden, hiddenItem)).isEqualTo(normalized(none, missing));
    assertThat(hidden.getContentAsString()).doesNotContain("기밀이름");
    // 양성 대조: 자격이 충분하면 같은 항목 근거가 열린다.
    assertThat(
            call("GET", "/api/v1/graphrag/review-items/" + hiddenItem + "/evidence", high)
                .getStatus())
        .isEqualTo(200);
  }

  @Test
  void approveAndReject_ofHiddenItem_are404_andLeaveItPending() throws Exception {
    long missing = Long.MAX_VALUE - 7;
    var approve = call("POST", "/api/v1/graphrag/review-items/" + hiddenItem + "/approve", low);
    var reject = call("POST", "/api/v1/graphrag/review-items/" + hiddenItem + "/reject", low);
    var none = call("POST", "/api/v1/graphrag/review-items/" + missing + "/reject", low);
    assertThat(approve.getStatus()).isEqualTo(404);
    assertThat(normalized(reject, hiddenItem)).isEqualTo(normalized(none, missing));
    // 이미 처리된 숨김 항목도 "이미 처리된 항목(status=…)" 이 아니라 같은 404 — 상태 검사보다 가시성 검사가 먼저다.
    assertThat(
            normalized(
                call("POST", "/api/v1/graphrag/review-items/" + hiddenDecidedItem + "/reject", low),
                hiddenDecidedItem))
        .isEqualTo(normalized(none, missing));
    String status =
        TenantRlsTestSupport.runInTenantTransaction(
            fixtureTransactionTemplate,
            DEFAULT_TEST_TENANT_ID,
            () ->
                dsl.fetchValue("select status from graph_review_item where id = ?", hiddenItem)
                    .toString());
    assertThat(status).isEqualTo("pending");
    // 양성 대조: 자격이 충분한 사용자는 거부할 수 있다.
    assertThat(
            call("POST", "/api/v1/graphrag/review-items/" + hiddenItem + "/reject", high)
                .getStatus())
        .isEqualTo(200);
  }

  // ── 후속 F2: 이름/키 기반 결정 조회(lookup) 존재 오라클 ──────────────────────────────────────

  /** 실제 item_type·dedupe_key 로 검수 항목을 만든다(lookup 은 item_type 이 고정이라 테스트 전용 타입을 못 쓴다). */
  private void insertDecision(String type, String dedupeKey, long datasetId, String status) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          reviewRepo.upsertPending(type, dedupeKey, datasetId, "low_confidence", 0.4, "근거", "{}");
          if (!"pending".equals(status)) {
            long id =
                ((Number)
                        dsl.fetchValue(
                            "select id from graph_review_item where item_type = ? and dedupe_key = ?",
                            type,
                            dedupeKey))
                    .longValue();
            reviewRepo.updateStatus(id, status, creator);
          }
        });
  }

  /** lookup 응답 — 상태 코드와 본문 원문 그대로(바이트 비교용). */
  private String lookup(String path, Map<String, String> params, long userId) throws Exception {
    var req = get("/api/v1/graphrag/review-items/" + path).header("Authorization", token(userId));
    params.forEach(req::param);
    MockHttpServletResponse r = mockMvc.perform(req).andReturn().getResponse();
    return r.getStatus() + "|" + r.getContentAsString();
  }

  @Test
  void lookups_ofHiddenDatasetItems_areByteIdenticalToMissingKeys_butClearedUserSeesStatus()
      throws Exception {
    String et = "E" + marker;
    String rs = "R" + marker + "s";
    // 엔티티: 숨김 pending · 숨김 approved · 공개 pending(낮은 자격 양성 대조 — 필터가 전부를 지우지 않음).
    insertDecision("entity_extraction", et + "|기밀이름", hiddenDs, "pending");
    insertDecision("entity_extraction", et + "|기밀결정", hiddenDs, "approved");
    insertDecision("entity_extraction", et + "|공개이름", visibleDs, "pending");
    // 동의어(정규화 정렬된 a|b) · 관계(opaque s|r|o) — 숨김 rejected.
    insertDecision("synonym_merge", et + "|가|나", hiddenDs, "rejected");
    insertDecision("relation_extraction", rs + "|CAUSED_BY|" + rs + "o", hiddenDs, "rejected");

    String missingEntity = lookup("entity/lookup", Map.of("entityType", et, "name", "없는이름"), low);
    assertThat(missingEntity).isEqualTo("200|{\"status\":\"none\"}");
    for (String name : List.of("기밀이름", "기밀결정")) {
      Map<String, String> p = Map.of("entityType", et, "name", name);
      assertThat(lookup("entity/lookup", p, low)).as(name).isEqualTo(missingEntity);
    }
    Map<String, String> syn = Map.of("entityType", et, "nameA", "나", "nameB", "가");
    Map<String, String> synMissing = Map.of("entityType", et, "nameA", "다", "nameB", "가");
    assertThat(lookup("synonym/lookup", syn, low))
        .isEqualTo(lookup("synonym/lookup", synMissing, low));
    Map<String, String> rel =
        Map.of("subjectKey", rs, "relType", "CAUSED_BY", "objectKey", rs + "o");
    Map<String, String> relMissing =
        Map.of("subjectKey", rs, "relType", "CAUSED_BY", "objectKey", rs + "x");
    assertThat(lookup("relation/lookup", rel, low))
        .isEqualTo(lookup("relation/lookup", relMissing, low));

    // 양성 대조: 낮은 자격도 볼 수 있는 데이터셋의 항목은 상태가 보이고, 자격이 충분하면 숨김 항목 상태가 보인다.
    assertThat(lookup("entity/lookup", Map.of("entityType", et, "name", "공개이름"), low))
        .isEqualTo("200|{\"status\":\"pending\"}");
    assertThat(lookup("entity/lookup", Map.of("entityType", et, "name", "기밀이름"), high))
        .isEqualTo("200|{\"status\":\"pending\"}");
    assertThat(lookup("entity/lookup", Map.of("entityType", et, "name", "기밀결정"), high))
        .isEqualTo("200|{\"status\":\"approved\"}");
    assertThat(lookup("synonym/lookup", syn, high)).isEqualTo("200|{\"status\":\"rejected\"}");
    assertThat(lookup("relation/lookup", rel, high)).isEqualTo("200|{\"status\":\"rejected\"}");
  }

  // ── 후속 F3: 검수 대기 등록(POST)이 클라이언트 datasetId·근거 청크를 그대로 믿던 문제 ─────────────

  /** 데이터셋에 문서 1건 + 청크 1건을 만들고 청크 id 를 돌려준다(데이터셋 삭제 시 CASCADE 로 함께 지워진다). */
  private long insertChunk(long datasetId) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long fileId =
              ((Number)
                      dsl.fetchValue(
                          "insert into document_file (dataset_id, original_name, mime_type,"
                              + " file_size, storage_path, uploaded_by) values (?, 'f.txt',"
                              + " 'text/plain', 1, 'p', ?) returning id",
                          datasetId,
                          creator))
                  .longValue();
          return ((Number)
                  dsl.fetchValue(
                      "insert into document_chunk (document_file_id, dataset_id, chunk_index,"
                          + " content) values (?, ?, 0, '기밀 원문') returning id",
                      fileId,
                      datasetId))
              .longValue();
        });
  }

  private MockHttpServletResponse postJson(String path, String body, long userId) throws Exception {
    return mockMvc
        .perform(
            post("/api/v1/graphrag/review-items/" + path)
                .header("Authorization", token(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andReturn()
        .getResponse();
  }

  /** 오류 응답 비교용 — 시각만 빼고, 메시지에 실린 요청 id 를 자리표시자로 바꾼 나머지 본문 전체. */
  private String errorBody(MockHttpServletResponse r, long id) throws Exception {
    ObjectNode n = (ObjectNode) om.readTree(r.getContentAsString());
    n.remove("timestamp");
    // 메시지 끝의 id 만 바꾼다(경로 "/api/v1" 같은 다른 숫자를 건드리지 않게).
    return r.getStatus() + "|" + n.toString().replace(": " + id + "\"", ": <id>\"");
  }

  private long countByDedupe(String like) {
    return TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () ->
            ((Number)
                    dsl.fetchValue(
                        "select count(*) from graph_review_item where dedupe_key like ?", like))
                .longValue());
  }

  /** 네 등록 엔드포인트의 본문 — datasetId·청크 id 만 바꿔 끼운다. 이름·키에 marker 를 넣어 생성 여부를 dedupe_key 로 센다. */
  private Map<String, String> pendingBodies(Long datasetId, Long chunkId) {
    String ds = String.valueOf(datasetId);
    String chunks = chunkId == null ? "[]" : "[" + chunkId + "]";
    String chunk = String.valueOf(chunkId);
    String m = "P" + marker;
    return Map.of(
        "entity/pending",
        "{\"datasetId\":"
            + ds
            + ",\"entityType\":\""
            + m
            + "\",\"name\":\"침투\","
            + "\"sourceChunkIds\":"
            + chunks
            + ",\"confidence\":0.3,\"relations\":[]}",
        "relation/pending",
        "{\"datasetId\":"
            + ds
            + ",\"subjectKey\":\""
            + m
            + "s\",\"relType\":\"R\","
            + "\"objectKey\":\""
            + m
            + "o\",\"subjectName\":\"a\",\"objectName\":\"b\","
            + "\"sourceChunkIds\":"
            + chunks
            + ",\"confidence\":0.3}",
        "synonym/pending",
        "{\"entityType\":\""
            + m
            + "\",\"nameA\":\"가\",\"nameB\":\"나\",\"similarity\":0.6,"
            + "\"rationale\":\"r\",\"datasetId\":"
            + ds
            + ",\"sourceChunkIds\":"
            + chunks
            + "}",
        "property/pending",
        "{\"datasetId\":"
            + ds
            + ",\"chunkId\":"
            + chunk
            + ",\"entityKey\":\""
            + m
            + "k\","
            + "\"entityType\":\"T\",\"propertyName\":\"p\",\"dataType\":\"number\","
            + "\"rawText\":\"x\"}");
  }

  @Test
  void pendingPost_toHiddenDataset_isSame404AsMissingDataset_andCreatesNothing() throws Exception {
    long missing = Long.MAX_VALUE - 7;
    long hiddenChunk = insertChunk(hiddenDs);
    var hiddenBodies = pendingBodies(hiddenDs, hiddenChunk);
    var missingBodies = pendingBodies(missing, hiddenChunk);
    for (String path : hiddenBodies.keySet()) {
      var hidden = postJson(path, hiddenBodies.get(path), low);
      var none = postJson(path, missingBodies.get(path), low);
      assertThat(hidden.getStatus()).as(path).isEqualTo(404);
      assertThat(errorBody(hidden, hiddenDs)).as(path).isEqualTo(errorBody(none, missing));
    }
    assertThat(countByDedupe("%P" + marker + "%")).isZero();

    // 양성 대조: 자격이 충분하면 같은 요청(그 데이터셋의 청크)이 등록된다 — 검증이 정상 호출을 막지 않는다.
    for (String path : hiddenBodies.keySet()) {
      assertThat(postJson(path, hiddenBodies.get(path), high).getStatus()).as(path).isEqualTo(200);
    }
    assertThat(countByDedupe("%P" + marker + "%")).isEqualTo(4);
  }

  @Test
  void pendingPost_withChunkOfAnotherDataset_isSame400AsMissingChunk_andCreatesNothing()
      throws Exception {
    long hiddenChunk = insertChunk(hiddenDs);
    long missingChunk = Long.MAX_VALUE - 9;
    var foreign = pendingBodies(visibleDs, hiddenChunk);
    var absent = pendingBodies(visibleDs, missingChunk);
    for (String path : foreign.keySet()) {
      // 자격이 충분한 사용자라도(두 데이터셋 모두 보임) 다른 데이터셋의 청크를 근거로 붙일 수 없다.
      var f = postJson(path, foreign.get(path), high);
      var a = postJson(path, absent.get(path), high);
      assertThat(f.getStatus()).as(path).isEqualTo(400);
      assertThat(errorBody(f, hiddenChunk)).as(path).isEqualTo(errorBody(a, missingChunk));
    }
    // datasetId 없이 청크만 보내면 근거를 판정할 데이터셋이 없어 400.
    var orphan = pendingBodies(null, hiddenChunk);
    for (String path : orphan.keySet()) {
      assertThat(postJson(path, orphan.get(path), high).getStatus()).as(path).isEqualTo(400);
    }
    assertThat(countByDedupe("%P" + marker + "%")).isZero();

    // 양성 대조: 자기 데이터셋의 청크면 낮은 자격 사용자도 등록된다.
    var own = pendingBodies(visibleDs, insertChunk(visibleDs));
    for (String path : own.keySet()) {
      assertThat(postJson(path, own.get(path), low).getStatus()).as(path).isEqualTo(200);
    }
    assertThat(countByDedupe("%P" + marker + "%")).isEqualTo(4);
  }
}
