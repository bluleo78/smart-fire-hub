package com.smartfirehub.embedding.repository.jooq;

import static com.smartfirehub.support.EmbeddingTestFixtures.axis;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository.SourceTextRow;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import com.smartfirehub.support.EmbeddingTestFixtures;
import com.smartfirehub.support.EmbeddingTestFixtures.DocFixture;
import com.smartfirehub.support.IntegrationTestBase;
import com.smartfirehub.support.PostgresTestContainer;
import com.smartfirehub.support.TenantRlsTestSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 임베딩 표시 열(WD-717)이 벡터 행의 거울로 유지되고, 표시 열 기반 판정이 예전 차원 테이블 anti-join 판정과 같은 결과를 내는지 고정한다.
 *
 * <p>왜 필요한가: WD-717 은 재임베딩 백로그(countMissing/findMissing)와 진행률 분자(countEmbedded)의 근거를 차원 테이블(*_vec_N)에서
 * 부모 행의 표시 열로 옮겼다. 벡터를 다른 저장소로 옮기기 위한 준비라 pgvector 설치본의 결과는 바뀌면 안 된다. 그래서 벡터를 쓰거나 지우는 모든 리포지토리
 * 경로를 한 번씩 밟으며, 매 단계마다 (1) 표시 열 ↔ 실제 벡터 행 불일치 0건, (2) 새 판정 = 예전 판정을 단언한다.
 *
 * <p>V160 백필(UPDATE … FROM *_vec_N)은 Flyway 가 테스트 DB 를 만들 때 데이터 없이 이미 돌았으므로, 같은 파일의 UPDATE 문을 소유자
 * 커넥션으로 다시 돌려 "표시 열이 찬 행 = 벡터 행" 을 따로 확인한다.
 */
class EmbeddingMarkerEquivalenceTest extends IntegrationTestBase {

  private static final EmbeddingSpace S1024 =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  // 같은 차원 안의 다른 모델 — 모델 교체 경로와 #392 모델 필터를 함께 본다.
  private static final EmbeddingSpace S1024_OTHER =
      new EmbeddingSpace(EmbeddingDimension.D1024, "other-model");
  private static final EmbeddingSpace S1536 = new EmbeddingSpace(EmbeddingDimension.D1536, "m2");
  private static final List<EmbeddingSpace> SPACES = List.of(S1024, S1024_OTHER, S1536);

  // 가장 넓은 실제 허용 값(자체 호스팅). 예전 판정식에도 같은 허용 술어를 붙여 비교한다.
  private static final ProviderHosting HOSTING = ProviderHosting.SELF_HOSTED;
  private static final EmbeddingAllowance ALLOW = new EmbeddingAllowance(HOSTING);

  @Autowired private DatasetEmbeddingRepository datasetRepo;
  @Autowired private DocumentChunkRepository chunkRepo;
  @Autowired private EmbeddingAiPolicyRepository aiPolicyRepository;
  @Autowired private DSLContext dsl;

  private long tenantA;
  private long tenantB;
  private DocFixture docA;
  private DocFixture docA2;
  private DocFixture docB;

  @BeforeEach
  void seed() {
    tenantA = TenantRlsTestSupport.createActiveTenant(dsl, "mark-a");
    tenantB = TenantRlsTestSupport.createActiveTenant(dsl, "mark-b");
    docA = inTenantFixture(tenantA, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "marka"));
    docA2 =
        inTenantFixture(tenantA, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "marka2"));
    docB = inTenantFixture(tenantB, () -> EmbeddingTestFixtures.createDocumentDataset(dsl, "markb"));
    TenantContext.runScoped(
        tenantA,
        () -> {
          datasetRepo.upsertSourceText(docA.datasetId(), "화재 통계");
          datasetRepo.upsertSourceText(docA2.datasetId(), "소방 점검");
          // 벡터 없는 청크 두 개(AI 불허 문서 경로)와 벡터와 함께 넣는 청크 하나.
          chunkRepo.insertChunksOnly(
              docA.fileId(),
              docA.datasetId(),
              List.of(new Chunk(0, "a-0", 1), new Chunk(1, "a-1", 1)));
          chunkRepo.insertBatch(
              docA2.fileId(),
              docA2.datasetId(),
              List.of(new Chunk(0, "a2-0", 1)),
              List.of(axis(1024, 0)),
              S1024);
        });
    TenantContext.runScoped(
        tenantB,
        () -> {
          datasetRepo.upsertSourceText(docB.datasetId(), "남의 데이터셋");
          datasetRepo.upsertEmbedding(S1024, docB.datasetId(), axis(1024, 2));
          chunkRepo.insertBatch(
              docB.fileId(),
              docB.datasetId(),
              List.of(new Chunk(0, "b-0", 1)),
              List.of(axis(1024, 3)),
              S1024);
        });
  }

  @AfterEach
  void cleanup() {
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantA);
    TenantRlsTestSupport.deleteOwnDatasetRows(dsl, fixtureTransactionTemplate, tenantB);
    TenantRlsTestSupport.deleteTenants(dsl, tenantA, tenantB);
    TenantRlsTestSupport.deleteUser(dsl, docA.userId());
    TenantRlsTestSupport.deleteUser(dsl, docA2.userId());
    TenantRlsTestSupport.deleteUser(dsl, docB.userId());
  }

  @Test
  void 벡터를_쓰고_지우는_모든_경로에서_표시_열이_거울로_남고_판정이_예전과_같다() {
    assertMirroredAndEquivalent("시드");

    // 데이터셋: 배치 쓰기(부모가 없는 id 하나 섞음 — 삭제 경합 0행)
    TenantContext.runScoped(
        tenantA,
        () ->
            datasetRepo.upsertEmbeddings(
                S1024,
                List.of(docA.datasetId(), Long.MAX_VALUE, docA2.datasetId()),
                List.of(axis(1024, 0), axis(1024, 1), axis(1024, 2))));
    assertMirroredAndEquivalent("데이터셋 배치 쓰기");

    // 데이터셋: 같은 차원 안 모델 교체 → 차원 교체
    TenantContext.runScoped(
        tenantA, () -> datasetRepo.upsertEmbedding(S1024_OTHER, docA.datasetId(), axis(1024, 3)));
    assertMirroredAndEquivalent("데이터셋 모델 교체");
    TenantContext.runScoped(
        tenantA, () -> datasetRepo.upsertEmbedding(S1536, docA.datasetId(), axis(1536, 0)));
    assertMirroredAndEquivalent("데이터셋 차원 교체");

    // 청크: 벡터 없던 청크 채우기 → 한 청크만 차원 교체
    List<Long> chunkIdsA =
        inTenantFixture(
            tenantA,
            () ->
                dsl.fetch(
                        "SELECT id FROM document_chunk WHERE dataset_id = ? ORDER BY id",
                        docA.datasetId())
                    .getValues(0, Long.class));
    TenantContext.runScoped(
        tenantA,
        () -> chunkRepo.upsertEmbeddings(S1024, chunkIdsA, List.of(axis(1024, 0), axis(1024, 1))));
    assertMirroredAndEquivalent("청크 채우기");
    TenantContext.runScoped(
        tenantA,
        () ->
            chunkRepo.upsertEmbeddings(
                S1536, List.of(chunkIdsA.get(0), Long.MAX_VALUE), List.of(axis(1536, 0), axis(1536, 1))));
    assertMirroredAndEquivalent("청크 차원 교체");

    // 다른 차원 정리 — 남의 테넌트(B, 1024)의 벡터·표시 열은 그대로여야 한다.
    TenantContext.runScoped(
        tenantA,
        () -> {
          datasetRepo.deleteOtherDimensions(EmbeddingDimension.D1536);
          chunkRepo.deleteOtherDimensions(EmbeddingDimension.D1536);
        });
    assertMirroredAndEquivalent("다른 차원 정리");
    assertThat(TenantContext.runScopedGet(tenantB, () -> datasetRepo.countEmbedded(S1024)))
        .isEqualTo(1);
    assertThat(TenantContext.runScopedGet(tenantB, () -> chunkRepo.countEmbedded(S1024)))
        .isEqualTo(1);

    // 외부 벡터 정리(단건·여러 건) — 원문은 남고 표시 열은 비워진다.
    TenantContext.runScoped(tenantA, () -> datasetRepo.deleteVectors(docA.datasetId()));
    assertMirroredAndEquivalent("데이터셋 단건 벡터 정리");
    TenantContext.runScoped(
        tenantA,
        () -> {
          datasetRepo.deleteVectorsOf(List.of(docA2.datasetId()));
          chunkRepo.deleteVectorsOf(List.of(docA.datasetId(), docA2.datasetId()));
        });
    assertMirroredAndEquivalent("여러 건 벡터 정리");
    assertThat(TenantContext.runScopedGet(tenantA, () -> datasetRepo.countMissing(S1024, ALLOW)))
        .isEqualTo(2);
  }

  @Test
  void V160_백필은_벡터_행이_있는_부모에만_그_공간을_적는다() throws IOException {
    // 벡터 세 종류를 만든다: 1024·bge-m3, 1536·m2, V131 이 남긴 '<unknown>' 모델(계속 백로그로 남아야 한다).
    TenantContext.runScoped(
        tenantA,
        () -> {
          datasetRepo.upsertEmbedding(S1536, docA.datasetId(), axis(1536, 0));
          datasetRepo.upsertEmbedding(
              new EmbeddingSpace(EmbeddingDimension.D1024, "<unknown>"),
              docA2.datasetId(),
              axis(1024, 0));
        });
    DSLContext owner = DSL.using(PostgresTestContainer.platformOwnerDataSource(), SQLDialect.POSTGRES);
    // 백필 전 상태를 만든다 — 표시 열을 전부 비운다(소유자 커넥션, 모든 테넌트).
    owner.execute("UPDATE dataset_embedding SET embedded_dim = NULL, embedded_model = NULL");
    owner.execute("UPDATE document_chunk SET embedded_dim = NULL, embedded_model = NULL");

    for (String update : backfillUpdates()) {
      owner.execute(update);
    }

    assertMirroredAndEquivalent("백필 후");
    long markedDatasets =
        owner.fetchOne("SELECT count(*) FROM dataset_embedding WHERE embedded_dim IS NOT NULL")
            .get(0, Long.class);
    long datasetVectors =
        owner.fetchOne(
                "SELECT (SELECT count(*) FROM dataset_embedding_vec_1024)"
                    + " + (SELECT count(*) FROM dataset_embedding_vec_1536)")
            .get(0, Long.class);
    assertThat(markedDatasets).isEqualTo(datasetVectors);
    long markedChunks =
        owner.fetchOne("SELECT count(*) FROM document_chunk WHERE embedded_dim IS NOT NULL")
            .get(0, Long.class);
    long chunkVectors =
        owner.fetchOne(
                "SELECT (SELECT count(*) FROM document_chunk_vec_1024)"
                    + " + (SELECT count(*) FROM document_chunk_vec_1536)")
            .get(0, Long.class);
    assertThat(markedChunks).isEqualTo(chunkVectors);
    // '<unknown>' 모델은 그대로 옮겨져 현재 모델(bge-m3) 기준으로 계속 백로그다.
    assertThat(
            TenantContext.runScopedGet(
                tenantA,
                () -> datasetRepo.findMissing(S1024, 0L, 10, ALLOW).stream()
                    .map(SourceTextRow::datasetId)
                    .toList()))
        .contains(docA2.datasetId());
  }

  // ---------- 단언 ----------

  /** 두 테넌트 모두에서 거울 불일치 0건, 모든 공간에서 새 판정 = 예전 판정. */
  private void assertMirroredAndEquivalent(String step) {
    for (long tenant : new long[] {tenantA, tenantB}) {
      assertThat(inTenantFixture(tenant, () -> count(datasetMirrorMismatchSql())))
          .as("%s: 데이터셋 표시 열 ↔ 벡터 행 불일치(tenant=%d)", step, tenant)
          .isZero();
      assertThat(inTenantFixture(tenant, () -> count(chunkMirrorMismatchSql())))
          .as("%s: 청크 표시 열 ↔ 벡터 행 불일치(tenant=%d)", step, tenant)
          .isZero();
      for (EmbeddingSpace space : SPACES) {
        assertDatasetEquivalent(step, tenant, space);
        assertChunkEquivalent(step, tenant, space);
      }
    }
  }

  private void assertDatasetEquivalent(String step, long tenant, EmbeddingSpace space) {
    String allowed = aiPolicyRepository.allowedDatasetSql("de.dataset_id", HOSTING);
    String oldMissing =
        "NOT EXISTS (SELECT 1 FROM "
            + space.dimension().datasetTable()
            + " v WHERE v.dataset_id = de.dataset_id AND v.embedding_model = ?)";
    long oldCount =
        inTenantFixture(
            tenant,
            () ->
                count(
                    "SELECT count(*) FROM dataset_embedding de WHERE "
                        + oldMissing
                        + " AND "
                        + allowed,
                    space.model()));
    List<Long> oldIds =
        inTenantFixture(
            tenant,
            () ->
                dsl.fetch(
                        "SELECT de.dataset_id FROM dataset_embedding de WHERE "
                            + oldMissing
                            + " AND "
                            + allowed
                            + " ORDER BY de.dataset_id",
                        space.model())
                    .getValues(0, Long.class));
    long oldEmbedded =
        inTenantFixture(
            tenant,
            () ->
                count(
                    "SELECT count(*) FROM "
                        + space.dimension().datasetTable()
                        + " WHERE embedding_model = ?",
                    space.model()));

    assertThat(TenantContext.runScopedGet(tenant, () -> datasetRepo.countMissing(space, ALLOW)))
        .as("%s: 데이터셋 countMissing %s", step, space)
        .isEqualTo(oldCount);
    assertThat(
            TenantContext.runScopedGet(
                tenant,
                () -> datasetRepo.findMissing(space, 0L, 100, ALLOW).stream()
                    .map(SourceTextRow::datasetId)
                    .toList()))
        .as("%s: 데이터셋 findMissing %s", step, space)
        .isEqualTo(oldIds);
    assertThat(TenantContext.runScopedGet(tenant, () -> datasetRepo.countEmbedded(space)))
        .as("%s: 데이터셋 countEmbedded %s", step, space)
        .isEqualTo(oldEmbedded);
  }

  private void assertChunkEquivalent(String step, long tenant, EmbeddingSpace space) {
    String allowed = aiPolicyRepository.allowedDatasetSql("c.dataset_id", HOSTING);
    String oldMissing =
        "NOT EXISTS (SELECT 1 FROM "
            + space.dimension().chunkTable()
            + " v WHERE v.chunk_id = c.id AND v.embedding_model = ?)";
    long oldCount =
        inTenantFixture(
            tenant,
            () ->
                count(
                    "SELECT count(*) FROM document_chunk c WHERE " + oldMissing + " AND " + allowed,
                    space.model()));
    List<Long> oldIds =
        inTenantFixture(
            tenant,
            () ->
                dsl.fetch(
                        "SELECT c.id FROM document_chunk c WHERE "
                            + oldMissing
                            + " AND "
                            + allowed
                            + " ORDER BY c.id",
                        space.model())
                    .getValues(0, Long.class));
    long oldEmbedded =
        inTenantFixture(
            tenant,
            () ->
                count(
                    "SELECT count(*) FROM "
                        + space.dimension().chunkTable()
                        + " WHERE embedding_model = ?",
                    space.model()));

    assertThat(TenantContext.runScopedGet(tenant, () -> chunkRepo.countMissing(space, ALLOW)))
        .as("%s: 청크 countMissing %s", step, space)
        .isEqualTo(oldCount);
    assertThat(
            TenantContext.runScopedGet(
                tenant,
                () -> chunkRepo.findMissing(space, 0L, 100, ALLOW).stream()
                    .map(ChunkContent::chunkId)
                    .toList()))
        .as("%s: 청크 findMissing %s", step, space)
        .isEqualTo(oldIds);
    assertThat(TenantContext.runScopedGet(tenant, () -> chunkRepo.countEmbedded(space)))
        .as("%s: 청크 countEmbedded %s", step, space)
        .isEqualTo(oldEmbedded);
  }

  // ---------- SQL 도우미 ----------

  private long count(String sql, Object... binds) {
    return dsl.fetchOne(sql, binds).get(0, Long.class);
  }

  /**
   * 표시 열이 벡터 행을 정확히 비추지 않는 데이터셋 수. 거울이면: 표시 열이 NULL 이고 어느 차원에도 벡터가 없거나, 표시 열의 차원 테이블에 그 모델 벡터가
   * 있고 다른 차원에는 없다.
   */
  private static String datasetMirrorMismatchSql() {
    return mirrorMismatchSql("dataset_embedding", "de", "dataset_id", "dataset_id", true);
  }

  private static String chunkMirrorMismatchSql() {
    return mirrorMismatchSql("document_chunk", "c", "id", "chunk_id", false);
  }

  private static String mirrorMismatchSql(
      String parent, String alias, String parentKey, String vecKey, boolean datasetTables) {
    String noVector =
        Arrays.stream(EmbeddingDimension.values())
            .map(d -> "NOT " + exists(d, alias, parentKey, vecKey, datasetTables, false))
            .collect(Collectors.joining(" AND "));
    String perDimension =
        Arrays.stream(EmbeddingDimension.values())
            .map(
                d -> {
                  String others =
                      Arrays.stream(EmbeddingDimension.values())
                          .filter(o -> o != d)
                          .map(o -> " AND NOT " + exists(o, alias, parentKey, vecKey, datasetTables, false))
                          .collect(Collectors.joining());
                  return "("
                      + alias
                      + ".embedded_dim = "
                      + d.size()
                      + " AND "
                      + exists(d, alias, parentKey, vecKey, datasetTables, true)
                      + others
                      + ")";
                })
            .collect(Collectors.joining(" OR "));
    return "SELECT count(*) FROM "
        + parent
        + " "
        + alias
        + " WHERE NOT (("
        + alias
        + ".embedded_dim IS NULL AND "
        + alias
        + ".embedded_model IS NULL AND "
        + noVector
        + ") OR "
        + perDimension
        + ")";
  }

  private static String exists(
      EmbeddingDimension d,
      String alias,
      String parentKey,
      String vecKey,
      boolean datasetTables,
      boolean sameModel) {
    String table = datasetTables ? d.datasetTable() : d.chunkTable();
    return "EXISTS (SELECT 1 FROM "
        + table
        + " v WHERE v."
        + vecKey
        + " = "
        + alias
        + "."
        + parentKey
        + (sameModel ? " AND v.embedding_model = " + alias + ".embedded_model" : "")
        + ")";
  }

  /** V160 파일의 백필 UPDATE 문(PG). 파일과 따로 복사해 두면 마이그레이션을 고쳐도 이 테스트가 옛 문장을 검증하게 되므로 파일에서 읽는다. */
  private static List<String> backfillUpdates() throws IOException {
    try (InputStream in =
        EmbeddingMarkerEquivalenceTest.class.getResourceAsStream(
            "/db/migration/V160__embedding_marker_columns.sql")) {
      assertThat(in).as("V160 마이그레이션 파일").isNotNull();
      String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      String withoutComments =
          Arrays.stream(sql.split("\n"))
              .filter(line -> !line.stripLeading().startsWith("--"))
              .collect(Collectors.joining("\n"));
      List<String> updates =
          Arrays.stream(withoutComments.split(";"))
              .map(String::strip)
              .filter(s -> s.startsWith("UPDATE"))
              .toList();
      assertThat(updates).as("백필 UPDATE 문").hasSize(4);
      return updates;
    }
  }
}
