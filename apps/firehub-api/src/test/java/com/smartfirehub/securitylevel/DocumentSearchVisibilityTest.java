package com.smartfirehub.securitylevel;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.security.JwtTokenProvider;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
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

/**
 * 스펙 §4.2(판정 C6): {@code POST /api/v1/documents/search} 는 데이터셋 ID 가 없는 <b>전역</b> 청크 검색이라 인터셉터가 막지
 * 못한다 — 볼 수 없는 문서 데이터셋의 청크 본문이 결과에 나오면 안 된다. 키워드(HTTP)와 의미(리포지토리) 두 경로 모두 고정하고, 열람 가능한 사용자에게는 같은
 * 청크가 보이는 양성 대조를 둔다.
 */
@AutoConfigureMockMvc
class DocumentSearchVisibilityTest extends IntegrationTestBase {

  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");

  @Autowired private MockMvc mockMvc;
  @Autowired private DSLContext dsl;
  @Autowired private PasswordEncoder encoder;
  @Autowired private JwtTokenProvider jwt;
  @Autowired private ObjectMapper om;
  @Autowired private DocumentChunkRepository chunkRepository;
  @Autowired private DatasetAccessGuard guard;
  @Autowired private ClearanceResolver clearanceResolver;

  private SecurityFixture fx;
  private final List<Long> users = new ArrayList<>();
  private final List<Long> roles = new ArrayList<>();
  private long creator;
  private long unclearedUser;
  private long clearedUser;
  private long hiddenDatasetId;
  private long visibleDatasetId;
  private String marker;

  @BeforeEach
  void setUp() {
    fx = new SecurityFixture(dsl, fixtureTransactionTemplate, encoder);
    marker = "dsv" + System.nanoTime();
    creator = fx.createUser("dsv_creator");
    users.add(creator);
    // 자격 '공개' 사용자(볼 수 없음)와 자격 '민감' 사용자(볼 수 있음) — 같은 권한(dataset:read), 자격만 다르다.
    unclearedUser = newUserWithLevel("dsv_low", "공개");
    clearedUser = newUserWithLevel("dsv_high", "민감");
    hiddenDatasetId = fx.createDatasetRow(marker + "_hidden", fx.levelId("민감"), creator);
    visibleDatasetId = fx.createDatasetRow(marker + "_visible", fx.levelId("공개"), creator);
    insertChunk(hiddenDatasetId, marker + " 인사평가 기밀 문서 내용", 0);
    insertChunk(visibleDatasetId, marker + " 공개 안내 문서 내용", 1);
  }

  @AfterEach
  void tearDown() {
    TenantContext.set(DEFAULT_TEST_TENANT_ID);
    // document_file·document_chunk·벡터는 데이터셋 삭제에 연쇄 삭제된다.
    fx.deleteDatasetRow(hiddenDatasetId);
    fx.deleteDatasetRow(visibleDatasetId);
    users.forEach(fx::deleteUser);
    roles.forEach(fx::deleteRole);
  }

  private long newUserWithLevel(String prefix, String levelName) {
    long uid = fx.createUser(prefix);
    users.add(uid);
    fx.removeUserRole(uid);
    long roleId = fx.createRole(prefix + "_role_" + marker, fx.levelId(levelName), "dataset:read");
    roles.add(roleId);
    fx.assignRole(uid, roleId);
    return uid;
  }

  /** 문서 파일 1개 + 청크 1개(+ 벡터). 청크 벡터는 같은 축이라 의미 검색에서 서로 같은 거리다. */
  private void insertChunk(long datasetId, String content, int index) {
    TenantRlsTestSupport.runInTenantTransaction(
        fixtureTransactionTemplate,
        DEFAULT_TEST_TENANT_ID,
        () -> {
          long fileId =
              dsl.fetchOne(
                      "INSERT INTO document_file(dataset_id, original_name, mime_type, file_size,"
                          + " storage_path, status, uploaded_by)"
                          + " VALUES (?, 'f.txt', 'text/plain', 1, '/tmp/f', 'COMPLETED', ?)"
                          + " RETURNING id",
                      datasetId,
                      creator)
                  .get(0, Long.class);
          chunkRepository.insertBatch(
              fileId,
              datasetId,
              List.of(new Chunk(index, content, 1)),
              List.of(axis(1024, 0)),
              SPACE);
        });
  }

  private List<Long> keywordHitDatasetIds(long userId) throws Exception {
    String token =
        "Bearer " + jwt.generateAccessToken(userId, "dsv" + userId, DEFAULT_TEST_TENANT_ID);
    String body = "{\"query\":\"" + marker + "\",\"mode\":\"KEYWORD\",\"topK\":20}";
    JsonNode hits =
        om.readTree(
            mockMvc
                .perform(
                    post("/api/v1/documents/search")
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn()
                .getResponse()
                .getContentAsString());
    List<Long> ids = new ArrayList<>();
    hits.forEach(h -> ids.add(h.get("datasetId").asLong()));
    return ids;
  }

  @Test
  void keywordSearch_hidesChunksOfInvisibleDataset() throws Exception {
    assertThat(keywordHitDatasetIds(unclearedUser))
        .contains(visibleDatasetId)
        .doesNotContain(hiddenDatasetId);
  }

  @Test
  void keywordSearch_positiveControl_clearedUserSeesHiddenDatasetChunks() throws Exception {
    // 양성 대조: 같은 질의·같은 데이터에서 자격 있는 사용자는 숨김 데이터셋 청크도 본다 — 위 단언이 공허하지 않음을 보인다.
    assertThat(keywordHitDatasetIds(clearedUser))
        .contains(visibleDatasetId)
        .contains(hiddenDatasetId);
  }

  @Test
  void semanticSearch_hidesChunksOfInvisibleDataset_andShowsThemToClearedUser() {
    List<Long> forLow =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID,
            () ->
                chunkRepository
                    .searchByCosine(
                        SPACE,
                        axis(1024, 0),
                        List.of(hiddenDatasetId, visibleDatasetId),
                        10,
                        guard.visibleSql(clearanceResolver.resolve(unclearedUser), "d"))
                    .stream()
                    .map(h -> h.datasetId())
                    .toList());
    List<Long> forHigh =
        TenantContext.runScopedGet(
            DEFAULT_TEST_TENANT_ID,
            () ->
                chunkRepository
                    .searchByCosine(
                        SPACE,
                        axis(1024, 0),
                        List.of(hiddenDatasetId, visibleDatasetId),
                        10,
                        guard.visibleSql(clearanceResolver.resolve(clearedUser), "d"))
                    .stream()
                    .map(h -> h.datasetId())
                    .toList());
    assertThat(forLow).containsExactly(visibleDatasetId);
    assertThat(forHigh).containsExactlyInAnyOrder(visibleDatasetId, hiddenDatasetId);
  }
}
