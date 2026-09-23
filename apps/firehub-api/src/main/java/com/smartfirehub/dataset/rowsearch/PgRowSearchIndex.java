package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.dataset.search.VectorLiterals;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector(HNSW) + pg_trgm(GIN) 기반 행 검색 색인.
 *
 * <p>색인 테이블은 원본과 같은 테넌트 데이터 스키마에 둔다(스키마 단위 테넌트 격리를 그대로 따른다). FK 는 두지 않는다 —
 * TRUNCATE·swap 이 깨지기 때문(설계 3.4). vector/pg_trgm 은 public 에 있고 일부 경로의 search_path 에 public 이
 * 없으므로 타입·연산자 클래스를 public. 으로 한정한다.
 */
@Repository
@Transactional
@RequiredArgsConstructor
public class PgRowSearchIndex implements RowSearchIndex {

  private final DSLContext dsl;

  /** HNSW 가 지원하는 최대 차원(pgvector 0.8). */
  public static final int MAX_DIM = 2000;

  /**
   * 색인 행 upsert 의 충돌 갱신 절 — {@link #upsert} 와 {@link #upsertReusing} 이 함께 쓴다. indexed_at 의 DEFAULT 는
   * INSERT 에만 적용되므로 갱신 경로에서 명시한다.
   */
  private static final String ON_CONFLICT_UPDATE =
      " ON CONFLICT (row_id, chunk_no) DO UPDATE SET source_text = EXCLUDED.source_text,"
          + " source_hash = EXCLUDED.source_hash, embedding = EXCLUDED.embedding,"
          + " embedding_model = EXCLUDED.embedding_model, indexed_at = now()";

  @Override
  public void recreate(IndexRef ref, int dim) {
    requireCurrentTenant(ref);
    drop(ref);
    createIndexTable(ref, dim);
  }

  @Override
  public void rebuildReusing(IndexRef ref, int dim) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.prevTable()));
    if (tableExists(ref.indexTable())) {
      // RENAME TO 의 새 이름에는 스키마를 붙이지 않는다(PostgreSQL 문법). 인덱스 이름은 자동 명명이라 충돌하지 않는다.
      dsl.execute(
          "ALTER TABLE " + DataSchema.qualify(ref.indexTable()) + " RENAME TO \"" + ref.prevTable() + "\"");
    }
    // _prev 는 원래 createIndexTable 로 만든 테이블을 개명한 것이라 권한 회수 상태(ACL)가 그대로 따라온다.
    createIndexTable(ref, dim);
  }

  @Override
  public boolean hasPrev(IndexRef ref) {
    requireCurrentTenant(ref);
    return tableExists(ref.prevTable());
  }

  @Override
  public void dropPrev(IndexRef ref) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.prevTable()));
  }

  @Override
  public void drop(IndexRef ref) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.indexTable()));
    dropPrev(ref);
  }

  @Override
  public Map<Long, String> existingHashes(IndexRef ref, Collection<Long> rowIds) {
    requireCurrentTenant(ref);
    Map<Long, String> result = new HashMap<>();
    if (rowIds.isEmpty()) return result;
    dsl.fetch(
            "SELECT row_id, source_hash FROM "
                + DataSchema.qualify(ref.indexTable())
                + " WHERE chunk_no = 0 AND row_id = ANY(?)",
            (Object) rowIds.toArray(Long[]::new))
        .forEach(r -> result.put(r.get(0, Long.class), r.get(1, String.class)));
    return result;
  }

  @Override
  public boolean upsertReusing(
      IndexRef ref, long rowId, String sourceText, String sourceHash, String model) {
    requireCurrentTenant(ref);
    // _prev 에서 같은 내용·같은 모델의 벡터를 한 건 골라 복사한다(swap 후 전량 재임베딩 방지).
    int inserted =
        dsl.execute(
            "INSERT INTO "
                + DataSchema.qualify(ref.indexTable())
                + " (row_id, chunk_no, source_text, source_hash, embedding, embedding_model, indexed_at)"
                + " SELECT ?, 0, ?, ?, p.embedding, p.embedding_model, now() FROM "
                + DataSchema.qualify(ref.prevTable())
                + " p WHERE p.source_hash = ? AND p.embedding_model = ? AND p.embedding IS NOT NULL LIMIT 1"
                + ON_CONFLICT_UPDATE,
            rowId,
            sourceText,
            sourceHash,
            sourceHash,
            model);
    return inserted > 0;
  }

  @Override
  public void upsert(IndexRef ref, List<IndexedRow> rows) {
    requireCurrentTenant(ref);
    if (rows.isEmpty()) return;
    String sql =
        "INSERT INTO "
            + DataSchema.qualify(ref.indexTable())
            + " (row_id, chunk_no, source_text, source_hash, embedding, embedding_model, indexed_at)"
            + " VALUES (?, 0, ?, ?, ?::public.vector, ?, now())"
            + ON_CONFLICT_UPDATE;
    var batch = dsl.batch(sql);
    for (IndexedRow r : rows) {
      batch =
          batch.bind(
              r.rowId(),
              r.sourceText(),
              r.sourceHash(),
              VectorLiterals.toVectorLiteral(r.embedding()),
              r.embeddingModel());
    }
    batch.execute();
  }

  @Override
  public void deleteRows(IndexRef ref, Collection<Long> rowIds) {
    requireCurrentTenant(ref);
    if (rowIds.isEmpty()) return;
    dsl.execute(
        "DELETE FROM " + DataSchema.qualify(ref.indexTable()) + " WHERE row_id = ANY(?)",
        (Object) rowIds.toArray(Long[]::new));
  }

  @Override
  public int deleteMissing(IndexRef ref) {
    requireCurrentTenant(ref);
    return dsl.execute(
        "DELETE FROM "
            + DataSchema.qualify(ref.indexTable())
            + " s WHERE NOT EXISTS (SELECT 1 FROM "
            + DataSchema.qualify(ref.sourceTable())
            + " t WHERE t.id = s.row_id)");
  }

  @Override
  public long count(IndexRef ref) {
    requireCurrentTenant(ref);
    if (!tableExists(ref.indexTable())) return 0;
    return dsl.fetchOne("SELECT count(*) FROM " + DataSchema.qualify(ref.indexTable()))
        .get(0, Long.class);
  }

  @Override
  public List<RowHit> semantic(IndexRef ref, float[] queryVec, CompiledFilter filter, int limit) {
    requireCurrentTenant(ref);
    String vector = VectorLiterals.toVectorLiteral(queryVec);
    StringBuilder sql =
        new StringBuilder("SELECT s.row_id, 1 - (s.embedding <=> ?::public.vector) AS score FROM ")
            .append(DataSchema.qualify(ref.indexTable()))
            .append(" s");
    List<Object> params = new ArrayList<>();
    params.add(vector);
    appendFilter(ref, filter, sql, params, " WHERE s.embedding IS NOT NULL");
    sql.append(" ORDER BY s.embedding <=> ?::public.vector LIMIT ?");
    params.add(vector);
    params.add(limit);
    return dsl.transactionResult(
        cfg -> {
          var tx = DSL.using(cfg);
          // 필터로 후보가 걸러져도 limit 을 채우도록 HNSW 가 반복 탐색한다(pgvector 0.8+).
          // relaxed_order 는 순서가 약간 어긋날 수 있어 아래에서 점수로 다시 정렬한다.
          tx.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
          List<RowHit> hits = new ArrayList<>(tx.fetch(sql.toString(), params.toArray()).map(PgRowSearchIndex::toHit));
          hits.sort((a, b) -> Double.compare(b.score(), a.score()));
          return hits;
        });
  }

  @Override
  public List<RowHit> keyword(IndexRef ref, String query, CompiledFilter filter, int limit) {
    requireCurrentTenant(ref);
    StringBuilder sql =
        new StringBuilder("SELECT s.row_id, word_similarity(?, s.source_text) AS score FROM ")
            .append(DataSchema.qualify(ref.indexTable()))
            .append(" s");
    List<Object> params = new ArrayList<>();
    params.add(query);
    // %> 는 컬럼이 좌변이어야 GIN trgm 인덱스를 탄다(DocumentChunkRepository 실측 주석 참고).
    appendFilter(ref, filter, sql, params, " WHERE s.source_text %> ?");
    params.add(1, query); // %> 우변 — WHERE 절 첫 바인딩(필터 바인딩보다 앞)
    sql.append(" ORDER BY score DESC LIMIT ?");
    params.add(limit);
    return dsl.transactionResult(
        cfg -> {
          var tx = DSL.using(cfg);
          tx.execute("SET LOCAL pg_trgm.word_similarity_threshold = 0.1");
          return tx.fetch(sql.toString(), params.toArray()).map(PgRowSearchIndex::toHit);
        });
  }

  /** 필터가 있으면 원본(t)과 조인한 뒤 WHERE 에 base 조건과 필터를 AND 로 붙인다. */
  private void appendFilter(
      IndexRef ref, CompiledFilter filter, StringBuilder sql, List<Object> params, String baseWhere) {
    if (!filter.isEmpty()) {
      sql.append(" JOIN ").append(DataSchema.qualify(ref.sourceTable())).append(" t ON t.id = s.row_id");
    }
    sql.append(baseWhere);
    if (!filter.isEmpty()) {
      sql.append(" AND ").append(filter.sql());
      params.addAll(filter.params());
    }
  }

  private static RowHit toHit(org.jooq.Record r) {
    Double score = r.get("score", Double.class);
    return new RowHit(r.get("row_id", Long.class), score == null ? 0.0 : score);
  }

  /**
   * 참조의 테넌트가 현재 테넌트 컨텍스트와 같은지 확인한다.
   *
   * <p>색인 테이블의 스키마는 {@link DataSchema#qualify} 가 <b>현재 컨텍스트</b>에서 정한다(전역 규칙). 그래서
   * {@code ref.tenantId()} 와 컨텍스트가 어긋나면 아무 오류 없이 다른 테넌트의 색인을 읽고 쓰거나 DROP 하게 된다.
   * 테넌트별로 도는 배경 스윕에서 이런 불일치가 생기기 쉬우므로, SQL 을 실행하기 전에 모든 공개 메서드에서 막는다.
   * 컨텍스트가 비어 있으면(null) 역시 불일치로 거부한다(fail-closed).
   */
  private void requireCurrentTenant(IndexRef ref) {
    Long current = TenantContext.get();
    if (!Objects.equals(current, ref.tenantId())) {
      throw new IllegalStateException(
          "색인 참조의 테넌트(" + ref.tenantId() + ")와 현재 테넌트 컨텍스트(" + current + ")가 다릅니다");
    }
  }

  /**
   * 색인 테이블 + HNSW(의미) + GIN trgm(키워드) + source_hash(재사용 조회) 인덱스를 만들고, 파이프라인 실행 롤의 권한을
   * 회수한다.
   */
  private void createIndexTable(IndexRef ref, int dim) {
    if (dim < 1 || dim > MAX_DIM) {
      throw new IllegalArgumentException("지원하지 않는 임베딩 차원입니다: " + dim + " (1~" + MAX_DIM + ")");
    }
    String q = DataSchema.qualify(ref.indexTable());
    dsl.execute(
        "CREATE TABLE "
            + q
            + " (row_id BIGINT NOT NULL, chunk_no SMALLINT NOT NULL DEFAULT 0,"
            + " source_text TEXT NOT NULL, source_hash VARCHAR(64) NOT NULL,"
            + " embedding public.vector("
            + dim
            + "), embedding_model VARCHAR(100),"
            + " indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (row_id, chunk_no))");
    // 인덱스 이름은 자동 명명 — rebuildReusing 이 테이블을 _prev 로 바꿔도 새 테이블 인덱스와 충돌하지 않는다.
    dsl.execute("CREATE INDEX ON " + q + " USING hnsw (embedding public.vector_cosine_ops)");
    dsl.execute("CREATE INDEX ON " + q + " USING gin (source_text public.gin_trgm_ops)");
    dsl.execute("CREATE INDEX ON " + q + " (source_hash)");
    revokeFromPipelineExecutor(ref, q);
  }

  /**
   * 테넌트 데이터 스키마의 기본 권한(ALTER DEFAULT PRIVILEGES, TenantSchemaProvisioner)은 새 테이블마다 파이프라인 실행
   * 롤에 SELECT/INSERT/UPDATE/DELETE 를 준다. 색인 테이블은 사용자 데이터가 아니라 내부 파생물이므로, 사용자 파이프라인
   * SQL 이 읽거나 오염시키지 못하게 만든 직후 모두 회수한다.
   *
   * <p>실행 롤은 자동 프로비저닝이 꺼진 환경 등에서 아직 없을 수 있다 — 없으면 줄 권한도 없으므로 건너뛴다(없는 롤에
   * REVOKE 하면 오류). 롤 이름·존재 판정은 정본 헬퍼({@link TenantPipelineRole#roleName},
   * {@link TenantSchemaProvisioner#roleExists})를 그대로 쓴다.
   */
  private void revokeFromPipelineExecutor(IndexRef ref, String qualifiedTable) {
    String executorRole = TenantPipelineRole.roleName(ref.tenantId());
    if (!TenantSchemaProvisioner.roleExists(dsl, executorRole)) return;
    dsl.execute("REVOKE ALL ON TABLE " + qualifiedTable + " FROM {0}", DSL.name(executorRole));
  }

  /** 현재 테넌트 데이터 스키마에 테이블이 있는지(to_regclass 는 없으면 NULL). */
  private boolean tableExists(String table) {
    return dsl.fetchOne("SELECT to_regclass(?) IS NOT NULL", DataSchema.qualify(table))
        .get(0, Boolean.class);
  }
}
