package com.smartfirehub.securitylevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.support.EmbeddingTestFixtures;
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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/** 스펙 §4.2 1행: 목록·시맨틱(키워드) 검색·태그 집계에서 볼 수 없는 데이터셋이 사라지고, 등급 필터가 서버 쿼리로 동작한다. */
@AutoConfigureMockMvc
class DatasetListVisibilityTest extends IntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private DatasetSearchRepository searchRepository;
  @Autowired private DatasetEmbeddingRepository embeddingRepository;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private long roleId;
  private long hiddenId;
  private long visibleId;
  private String marker;
  private long userId;
  private String token;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "lv" + System.nanoTime();
    long creator = fx.createUser("lv_creator");
    users.add(creator);
    long uid = fx.createUser("lv_user");
    userId = uid;
    users.add(uid);
    fx.removeUserRole(uid);
    roleId = fx.createRole("lv_role_" + marker, fx.levelId("공개"), "dataset:read");
    fx.assignRole(uid, roleId);
    hiddenId = fx.createDatasetRow(marker + "_hidden", fx.levelId("민감"), creator);
    visibleId = fx.createDatasetRow(marker + "_visible", fx.levelId("공개"), creator);
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          dsl.execute(
              "insert into dataset_tag (dataset_id, tag_name) values (?, ?)",
              hiddenId,
              marker + "_htag");
          dsl.execute(
              "insert into dataset_tag (dataset_id, tag_name) values (?, ?)",
              visibleId,
              marker + "_vtag");
          dsl.execute(
              "insert into dataset_embedding (dataset_id, source_text) values (?, ?), (?, ?)",
              hiddenId,
              marker + " 인사 평가 기밀",
              visibleId,
              marker + " 출동 기록 공개");
        });
    token = "Bearer " + jwt.generateAccessToken(uid, "lv" + uid, DEFAULT_TEST_TENANT_ID);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    fx.deleteDatasetRow(hiddenId); // tag·embedding 은 ON DELETE CASCADE
    fx.deleteDatasetRow(visibleId);
    users.forEach(fx::deleteUser);
    fx.deleteRole(roleId);
  }

  private JsonNode getJson(String url) throws Exception {
    return om.readTree(
        mockMvc
            .perform(get(url).header("Authorization", token))
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void list_excludesHidden_andCountsOnlyVisible() throws Exception {
    JsonNode page = getJson("/api/v1/datasets?search=" + marker);
    assertThat(page.get("totalElements").asLong()).isEqualTo(1);
    assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(visibleId);
    JsonNode level = page.get("content").get(0).get("securityLevel");
    assertThat(level.get("name").asText()).isEqualTo("공개");
    assertThat(level.get("allowlistRequired").asBoolean()).isFalse();
  }

  @Test
  void securityLevelFilter_isServerSide() throws Exception {
    JsonNode pub =
        getJson("/api/v1/datasets?search=" + marker + "&securityLevelId=" + fx.levelId("공개"));
    assertThat(pub.get("totalElements").asLong()).isEqualTo(1);
    JsonNode sens =
        getJson("/api/v1/datasets?search=" + marker + "&securityLevelId=" + fx.levelId("민감"));
    assertThat(sens.get("totalElements").asLong()).as("필터가 가시성을 넓히면 안 된다").isZero();
  }

  @Test
  void tags_excludeHiddenDatasetTags() throws Exception {
    JsonNode tags = getJson("/api/v1/datasets/tags");
    List<String> values = new ArrayList<>();
    tags.forEach(t -> values.add(t.asText()));
    assertThat(values).contains(marker + "_vtag").doesNotContain(marker + "_htag");
  }

  @Test
  void keywordSearch_excludesHidden() throws Exception {
    String body = "{\"query\":\"" + marker + "\",\"mode\":\"KEYWORD\",\"topK\":10}";
    JsonNode hits =
        om.readTree(
            mockMvc
                .perform(
                    post("/api/v1/datasets/search")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<Long> ids = new ArrayList<>();
    hits.forEach(h -> ids.add(h.get("datasetId").asLong()));
    assertThat(ids).contains(visibleId).doesNotContain(hiddenId);
  }

  @Test
  void detail_carriesSecurityLevel() throws Exception {
    // 상세 조회는 물리 테이블 행 수를 센다 — 메타 행만 있으면 500 이라 이 테스트에서만 테이블을 만든다.
    String table = marker + "_visible";
    dsl.execute("CREATE TABLE IF NOT EXISTS \"data\".\"" + table + "\" (id bigserial)");
    try {
      JsonNode d = getJson("/api/v1/datasets/" + visibleId);
      assertThat(d.get("securityLevel").get("name").asText()).isEqualTo("공개");
      assertThat(d.get("securityLevelAutoRaisedAt").isNull()).isTrue();
    } finally {
      dsl.execute("DROP TABLE IF EXISTS \"data\".\"" + table + "\"");
    }
  }

  @Test
  void semanticSearch_excludesHidden() {
    // 의미(코사인) 경로는 HTTP 로 부르면 임베딩 공급자가 필요하다 — 같은 SQL 을 만드는 리포지토리를 직접 호출한다.
    EmbeddingSpace space = new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
    List<Long> ids =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID,
            () -> {
              embeddingRepository.upsertEmbedding(
                  space, hiddenId, EmbeddingTestFixtures.axis(1024, 0));
              embeddingRepository.upsertEmbedding(
                  space, visibleId, EmbeddingTestFixtures.axis(1024, 0));
              return searchRepository
                  .searchByCosine(
                      space,
                      EmbeddingTestFixtures.axis(1024, 0),
                      null,
                      20,
                      guard.visibleSql(clearanceResolver.resolve(userId), "d"))
                  .stream()
                  .map(DatasetSearchHit::datasetId)
                  .toList();
            });
    assertThat(ids).contains(visibleId).doesNotContain(hiddenId);
  }
}
