package com.smartfirehub.securitylevel.repository.jooq;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.repository.jooq.JooqDatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.jooq.JooqDatasetSearchRepository;
import com.smartfirehub.document.repository.jooq.JooqDocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiCall;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * WD-485 T10 골든: 검색·임베딩 리포지토리가 SQL 문자열 인자 대신 도메인 값(DatasetVisibility·EmbeddingAllowance)을 받게 바뀐 뒤에도
 * <b>실행되는 SQL 과 바인드가 예전과 바이트 동일</b>하다.
 *
 * <p>방법: jOOQ {@link MockConnection} 으로 실제로 jOOQ 에 넘어간 SQL·바인드를 캡처한다. 같은 DSL 로 (a) 새 시그니처 메서드를 실행하고
 * (b) 예전 서비스가 넘기던 술어 문자열(예전 {@code guard.visibleSql(c, "d")}·{@code
 * aiGate.allowedDatasetSql("de.dataset_id" / "c.dataset_id")} 와 같은 렌더)을 예전 SQL 템플릿에 그대로 이어 붙여 실행한 뒤
 * 둘을 비교한다. 예전 템플릿은 변경 전 코드(4f48181b)에서 그대로 옮겼다 — 구현의 별칭이 예전 호출자 별칭과 다르면 여기서 깨진다(서비스 단위 테스트의 별칭 확인이
 * 이 테스트로 옮겨 왔다).
 *
 * <p>DB 없이 도는 순수 단위 테스트다(렌더러는 실제 JooqDatasetAccessRepository·JooqEmbeddingAiPolicyRepository).
 */
class SearchEmbeddingPredicateGoldenTest {

  private static final EmbeddingSpace SPACE =
      new EmbeddingSpace(EmbeddingDimension.D1024, "bge-m3");
  private static final String VEC = "[1.0,0.0]";

  /** 실행된 SQL 1건(SQL + 바인드). */
  private record Executed(String sql, List<Object> binds) {}

  /** jOOQ 가 MockConnection 으로 보낸 문장을 모은다. count 질의에는 1행(0)을, 나머지는 빈 결과를 준다. */
  private final List<Executed> captured = new ArrayList<>();

  private final DSLContext dsl =
      DSL.using(
          new MockConnection(
              ctx -> {
                captured.add(new Executed(ctx.sql(), Arrays.asList(ctx.bindings())));
                String sql = ctx.sql().toLowerCase(Locale.ROOT);
                if (!sql.startsWith("select")) {
                  return new MockResult[] {new MockResult(0)};
                }
                if (sql.startsWith("select count(*)")) {
                  DSLContext r = DSL.using(SQLDialect.POSTGRES);
                  Field<Long> count = DSL.field("count", Long.class);
                  Result<Record1<Long>> res = r.newResult(count);
                  res.add(r.newRecord(count).values(0L));
                  return new MockResult[] {new MockResult(1, res)};
                }
                return new MockResult[] {
                  new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())
                };
              }),
          SQLDialect.POSTGRES);

  // 렌더러 — 렌더 전용(쿼리 실행 없음)이라 연결 없는 PG DSL 로 충분하다. 예전 서비스 경로도 같은 구현을 거쳤다.
  private final JooqDatasetAccessRepository accessRepo =
      new JooqDatasetAccessRepository(DSL.using(SQLDialect.POSTGRES));
  private final JooqEmbeddingAiPolicyRepository policyRepo =
      new JooqEmbeddingAiPolicyRepository(DSL.using(SQLDialect.POSTGRES));

  /** 허용 목록·역할·AI(+SHARE) 문맥까지 든 가시성 — 렌더 결과가 단순 상수가 되지 않게 술어의 모든 갈래를 켠다. */
  private static final DatasetVisibility VISIBILITY =
      new DatasetVisibility(
          new Clearance(7L, 1L, 2, Set.of(11L, 12L), true, Set.of()),
          new AiCall(ProviderHosting.EXTERNAL, true));

  /** 한 실행에서 SELECT 문장만 꺼낸다(SET LOCAL 등 부수 문장 제외). */
  private List<Executed> run(Runnable r) {
    captured.clear();
    r.run();
    return captured.stream()
        .filter(e -> e.sql().toLowerCase(Locale.ROOT).startsWith("select"))
        .toList();
  }

  /** 새 경로와 예전 경로의 캡처가 완전히 같다(SQL 바이트 + 바인드) — 그리고 비어 있지 않다(공허한 일치 방지). */
  private void assertSame(Runnable newPath, Consumer<DSLContext> oldPath) {
    List<Executed> actual = run(newPath);
    List<Executed> expected = run(() -> oldPath.accept(dsl));
    assertThat(actual).isNotEmpty();
    assertThat(actual).isEqualTo(expected);
  }

  // ---------- 가시성(별칭 d) ----------

  @Test
  void 데이터셋_검색_두_메서드는_예전_visibleSql_d_를_넘기던_SQL_과_같다() {
    var repo = new JooqDatasetSearchRepository(dsl, accessRepo);
    // 예전 서비스: String visibility = datasetAccessGuard.visibleSql(clearance, "d")
    String oldVis = accessRepo.visibleSql(VISIBILITY, "d");

    assertSame(
        () -> repo.searchByCosine(SPACE, new float[] {1f, 0f}, "DOCUMENT", 5, VISIBILITY),
        d ->
            d.fetch(
                "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                    + " c.name AS category_name, 1 - (v.embedding <=> ?::vector) AS score FROM "
                    + SPACE.dimension().datasetTable()
                    + " v JOIN dataset d ON d.id = v.dataset_id"
                    + " LEFT JOIN dataset_category c ON c.id = d.category_id"
                    + " WHERE v.embedding_model = ?"
                    + " AND "
                    + oldVis
                    + " AND d.storage_type = ?"
                    + " ORDER BY v.embedding <=> ?::vector LIMIT ?",
                VEC,
                SPACE.model(),
                "DOCUMENT",
                VEC,
                5));

    assertSame(
        () -> repo.searchByTrigram("화재", null, 7, VISIBILITY),
        d ->
            d.fetch(
                "SELECT d.id, d.name, d.description, d.storage_type, d.origin_type, d.table_name,"
                    + " c.name AS category_name, word_similarity(?, de.source_text) AS score"
                    + " FROM dataset_embedding de"
                    + " JOIN dataset d ON d.id = de.dataset_id"
                    + " LEFT JOIN dataset_category c ON c.id = d.category_id"
                    + " WHERE de.source_text %> ?"
                    + " AND "
                    + oldVis
                    + " ORDER BY score DESC LIMIT ?",
                "화재",
                "화재",
                7));
  }

  @Test
  void 문서_청크_검색_두_메서드는_예전_visibleSql_d_를_넘기던_SQL_과_같다() {
    var repo = new JooqDocumentChunkRepository(dsl, policyRepo, accessRepo);
    String oldVis = accessRepo.visibleSql(VISIBILITY, "d");

    assertSame(
        () -> repo.searchByCosine(SPACE, new float[] {1f, 0f}, List.of(3L, 4L), 5, VISIBILITY),
        d ->
            d.fetch(
                "SELECT c.id, c.document_file_id, c.dataset_id, df.original_name, c.chunk_index, c.content,"
                    + " 1 - (v.embedding <=> ?::vector) AS score FROM "
                    + SPACE.dimension().chunkTable()
                    + " v JOIN document_chunk c ON c.id = v.chunk_id"
                    + " JOIN document_file df ON df.id = c.document_file_id"
                    + " JOIN dataset d ON d.id = c.dataset_id"
                    + " WHERE df.status = 'COMPLETED' AND v.embedding_model = ?"
                    + " AND "
                    + oldVis
                    + " AND v.dataset_id IN (?,?)"
                    + " ORDER BY v.embedding <=> ?::vector LIMIT ?",
                VEC,
                SPACE.model(),
                3L,
                4L,
                VEC,
                5));

    assertSame(
        () -> repo.searchByTrigram("질의", List.of(3L), 5, VISIBILITY),
        d ->
            d.fetch(
                "SELECT dc.id, dc.document_file_id, dc.dataset_id, df.original_name,"
                    + " dc.chunk_index, dc.content, word_similarity(?, dc.content) AS score"
                    + " FROM document_chunk dc"
                    + " JOIN document_file df ON df.id = dc.document_file_id"
                    + " JOIN dataset d ON d.id = dc.dataset_id"
                    + " WHERE df.status = 'COMPLETED'"
                    + " AND "
                    + oldVis
                    + " AND dc.dataset_id IN (?)"
                    + " AND dc.content %> ?"
                    + " ORDER BY score DESC LIMIT ?",
                "질의",
                3L,
                "질의",
                5));
  }

  // ---------- 임베딩 허용(별칭 de.dataset_id / c.dataset_id) ----------

  @ParameterizedTest
  @EnumSource(ProviderHosting.class)
  void 카탈로그_임베딩_세_메서드는_예전_allowedDatasetSql_de_를_넘기던_SQL_과_같다(ProviderHosting h) {
    var repo = new JooqDatasetEmbeddingRepository(dsl, policyRepo);
    EmbeddingAllowance allowance = new EmbeddingAllowance(h);
    // 예전 호출자: aiGate.allowedDatasetSql("de.dataset_id") = policyRepository.allowedDatasetSql(expr,
    // 호스팅)
    String oldAllowed = policyRepo.allowedDatasetSql("de.dataset_id", h);
    // 판정식 자체는 WD-717 에서 차원 테이블 anti-join 에서 표시 열 비교로 바뀌었다(바인드: 차원, 모델). 이 골든이 지키는 것은 등급 허용 술어의
    // 별칭·호스팅 전달이다. 두 판정식의 결과 동등성은 EmbeddingMarkerEquivalenceTest 가 실제 DB 로 고정한다.
    String missing =
        "(de.embedded_dim IS NULL OR de.embedded_dim <> ? OR de.embedded_model IS NULL"
            + " OR de.embedded_model <> ?)";

    assertSame(
        () -> repo.countAll(allowance),
        d -> d.fetchOne("SELECT count(*) FROM dataset_embedding de WHERE " + oldAllowed));
    assertSame(
        () -> repo.countMissing(SPACE, allowance),
        d ->
            d.fetchOne(
                "SELECT count(*) FROM dataset_embedding de WHERE " + missing + " AND " + oldAllowed,
                SPACE.dimension().size(),
                SPACE.model()));
    assertSame(
        () -> repo.findMissing(SPACE, 10L, 50, allowance),
        d ->
            d.fetch(
                "SELECT de.dataset_id, de.source_text FROM dataset_embedding de WHERE de.dataset_id > ? AND "
                    + missing
                    + " AND "
                    + oldAllowed
                    + " ORDER BY de.dataset_id LIMIT ?",
                10L,
                SPACE.dimension().size(),
                SPACE.model(),
                50));
  }

  @ParameterizedTest
  @EnumSource(ProviderHosting.class)
  void 문서_청크_임베딩_세_메서드는_예전_allowedDatasetSql_c_를_넘기던_SQL_과_같다(ProviderHosting h) {
    var repo = new JooqDocumentChunkRepository(dsl, policyRepo, accessRepo);
    EmbeddingAllowance allowance = new EmbeddingAllowance(h);
    String oldAllowed = policyRepo.allowedDatasetSql("c.dataset_id", h);
    // 판정식은 WD-717 에서 표시 열 비교로 바뀌었다 — 위 카탈로그 쪽 주석과 같다.
    String missing =
        "(c.embedded_dim IS NULL OR c.embedded_dim <> ? OR c.embedded_model IS NULL"
            + " OR c.embedded_model <> ?)";

    assertSame(
        () -> repo.countAllChunks(allowance),
        d -> d.fetchOne("SELECT COUNT(*) FROM document_chunk c WHERE " + oldAllowed));
    assertSame(
        () -> repo.countMissing(SPACE, allowance),
        d ->
            d.fetchOne(
                "SELECT count(*) FROM document_chunk c WHERE " + missing + " AND " + oldAllowed,
                SPACE.dimension().size(),
                SPACE.model()));
    assertSame(
        () -> repo.findMissing(SPACE, 10L, 50, allowance),
        d ->
            d.fetch(
                "SELECT c.id, c.content FROM document_chunk c WHERE c.id > ? AND "
                    + missing
                    + " AND "
                    + oldAllowed
                    + " ORDER BY c.id LIMIT ?",
                10L,
                SPACE.dimension().size(),
                SPACE.model(),
                50));
  }

  /** 호스팅이 술어에 실제로 반영된다 — 두 호스팅의 렌더가 다르므로 위 비교가 호스팅 전달까지 고정한다(공허한 일치 방지). */
  @Test
  void 호스팅별_술어가_달라_비교가_호스팅_전달을_고정한다() {
    assertThat(policyRepo.allowedDatasetSql("de.dataset_id", ProviderHosting.EXTERNAL))
        .isNotEqualTo(policyRepo.allowedDatasetSql("de.dataset_id", ProviderHosting.SELF_HOSTED))
        .contains("de.dataset_id");
    assertThat(policyRepo.allowedDatasetSql("c.dataset_id", ProviderHosting.EXTERNAL))
        .contains("c.dataset_id");
    assertThat(accessRepo.visibleSql(VISIBILITY, "d")).contains("\"d\".\"id\"");
  }
}
