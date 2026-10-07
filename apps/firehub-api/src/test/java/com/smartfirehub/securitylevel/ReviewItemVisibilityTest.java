package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.SecurityFixture;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "riv" + System.nanoTime();
    // 서비스는 item_type 값을 검증하지 않으므로 테스트 전용 타입으로 목록을 격리한다(공유 DB 의 다른 항목과 섞이지 않게).
    itemType = "test_" + marker;
    long creator = fx.createUser("riv_c");
    users.add(creator);
    // 같은 권한(dataset:read·write), 자격만 다른 두 사용자.
    low = userAt("riv_low", "공개");
    high = userAt("riv_high", "민감");
    long hiddenDs = fx.createDatasetRow(marker + "_hidden", fx.levelId("민감"), creator);
    long visibleDs = fx.createDatasetRow(marker + "_visible", fx.levelId("공개"), creator);
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
        () -> dsl.execute("delete from graph_review_item where item_type = ?", itemType));
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
}
