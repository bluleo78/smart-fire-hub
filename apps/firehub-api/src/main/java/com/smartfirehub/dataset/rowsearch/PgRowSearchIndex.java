package com.smartfirehub.dataset.rowsearch;

import com.smartfirehub.embedding.repository.VectorLiterals;
import com.smartfirehub.global.datastore.DataStoreDb;
import com.smartfirehub.global.tenant.DataSchema;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.smartfirehub.global.tenant.TenantSchemaProvisioner;
import com.smartfirehub.vectorstore.ConditionalOnPlatformVectorStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * pgvector(HNSW) + pg_trgm(GiST, WD-725) 기반 행 검색 색인.
 *
 * <p>색인 테이블은 원본과 같은 테넌트 데이터 스키마에 둔다(스키마 단위 테넌트 격리를 그대로 따른다). FK 는 두지 않는다 — TRUNCATE·swap 이 깨지기
 * 때문(설계 3.4). vector/pg_trgm 은 public 에 있고 일부 경로의 search_path 에 public 이 없으므로 타입·연산자 클래스를 public.
 * 으로 한정한다.
 *
 * <p>벡터 저장소가 {@code platform} 일 때만 뜬다. qdrant 설치본은 {@code QdrantRowSearchIndex} 가 대신한다(WD-722).
 */
@Slf4j
@Repository
@Transactional
@ConditionalOnPlatformVectorStore
public class PgRowSearchIndex implements RowSearchIndex {

  private final DSLContext dsl;

  /**
   * 색인 테이블(청크 단위) 행 수가 이 값 이하면 의미 검색을 HNSW 대신 **정확 스캔**으로 한다.
   *
   * <p>왜: 비슷한 문장이 대부분인 데이터셋(템플릿성 민원 등)에서는 HNSW 그래프가 튀는 행으로 잘 이어지지 않아, 정확 계산으로는 1위인 행이 상위 결과에서 통째로
   * 빠졌다(2026-09-27 라이브 검증 — 1.2만 행에서 ef_search 400, m=32 도 놓침). 근사 검색의 이득은 규모가 클 때만 의미가 있으므로 작은 색인은
   * 정확도를 택한다. 실측 비용: 1.2만 행 약 54ms, 10만 행 warm 약 160ms·cold 약 1s(1024차원).
   */
  private final long exactScanMaxRows;

  /** HNSW 경로의 후보 폭. pgvector 기본 40 은 비슷한 벡터가 몰린 색인에서 재현율이 낮다. */
  static final int HNSW_EF_SEARCH = 200;

  public PgRowSearchIndex(
      @DataStoreDb DSLContext dsl,
      @Value("${row-search.search.exact-scan-max-rows:100000}") long exactScanMaxRows) {
    this.dsl = dsl;
    this.exactScanMaxRows = exactScanMaxRows;
  }

  /** HNSW 가 지원하는 최대 차원(pgvector 0.8). */
  public static final int MAX_DIM = 2000;

  /**
   * 색인 행 upsert 의 충돌 갱신 절 — {@link #upsert} 와 {@link #upsertReusing} 이 함께 쓴다. indexed_at 의 DEFAULT
   * 는 INSERT 에만 적용되므로 갱신 경로에서 명시한다.
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
  public void startGeneration(IndexRef ref, int dim) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.prevTable()));
    if (tableExists(ref.indexTable())) {
      // RENAME TO 의 새 이름에는 스키마를 붙이지 않는다(PostgreSQL 문법). 인덱스 이름은 자동 명명이라 충돌하지 않는다.
      dsl.execute(
          "ALTER TABLE "
              + DataSchema.qualify(ref.indexTable())
              + " RENAME TO \""
              + ref.prevTable()
              + "\"");
    }
    // _prev 는 원래 createIndexTable 로 만든 테이블을 개명한 것이라 권한 회수 상태(ACL)가 그대로 따라온다.
    createIndexTable(ref, dim);
  }

  /** GiST 키워드 색인을 확인(또는 만든) 색인 테이블 — 「테넌트:스키마.테이블」. 확인은 JVM 마다 테이블당 한 번이다. */
  private final Set<String> keywordIndexChecked = ConcurrentHashMap.newKeySet();

  /**
   * 옛 GIN 키워드 색인 테이블을 GiST 로 올린다(WD-725). 이 변경 전에 만든 색인 테이블은 GIN 이라 거리 순 색인 스캔(KNN)을 못 해 키워드 검색이
   * seq scan 이다. 새 테이블은 {@link #createIndexTable} 이 GiST 로 만들므로, 여기서는 GiST 가 없을 때만 만들고 GIN 을 지운다.
   *
   * <p>동기화(임대를 쥔 쓰기 경로)에서만 부른다 — 색인을 만드는 동안 이 테이블 쓰기만 잠기고 검색(읽기)은 된다. 테이블을 만든 롤과 같은 연결이라 소유자 권한이
   * 있다.
   */
  @Override
  public void ensureKeywordIndex(IndexRef ref) {
    requireCurrentTenant(ref);
    String q = DataSchema.qualify(ref.indexTable());
    String key = ref.tenantId() + ":" + q;
    if (keywordIndexChecked.contains(key) || !tableExists(ref.indexTable())) return;
    // 이 테이블의 trgm 색인을 접근 방법(gist·gin)별로 찾는다.
    List<Record> trgm =
        dsl.fetch(
            "SELECT ic.relname, am.amname FROM pg_index i"
                + " JOIN pg_class ic ON ic.oid = i.indexrelid"
                + " JOIN pg_am am ON am.oid = ic.relam"
                + " JOIN pg_opclass oc ON oc.oid = i.indclass[0]"
                + " WHERE i.indrelid = to_regclass(?) AND oc.opcname IN ('gist_trgm_ops', 'gin_trgm_ops')",
            q);
    boolean hasGist = trgm.stream().anyMatch(r -> "gist".equals(r.get(1, String.class)));
    if (!hasGist) {
      dsl.execute("CREATE INDEX ON " + q + " USING gist (source_text public.gist_trgm_ops)");
      log.info("행 검색 키워드 색인을 GiST 로 올림: tenant={} table={}", ref.tenantId(), q);
    }
    // GIN 은 거르기도 GiST 가 맡게 되어 쓰기 비용만 남으므로 지운다. 색인은 테이블과 같은 스키마에 있다.
    for (Record r : trgm) {
      if ("gin".equals(r.get(1, String.class))) {
        dsl.execute(
            "DROP INDEX IF EXISTS "
                + DataSchema.current()
                + ".\""
                + r.get(0, String.class).replace("\"", "\"\"")
                + "\"");
      }
    }
    keywordIndexChecked.add(key);
  }

  @Override
  public boolean hasPreviousGeneration(IndexRef ref) {
    requireCurrentTenant(ref);
    return tableExists(ref.prevTable());
  }

  @Override
  public void dropPreviousGenerations(IndexRef ref) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.prevTable()));
  }

  @Override
  public void drop(IndexRef ref) {
    requireCurrentTenant(ref);
    dsl.execute("DROP TABLE IF EXISTS " + DataSchema.qualify(ref.indexTable()));
    dropPreviousGenerations(ref);
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
  public Set<Long> upsertReusing(IndexRef ref, List<ReuseCandidate> rows, String model) {
    requireCurrentTenant(ref);
    if (rows.isEmpty()) return Set.of();
    // _prev 에서 같은 내용·같은 모델의 벡터를 후보마다 한 건 골라 한 문장으로 복사한다(swap 후 전량 재임베딩 방지, WD-743).
    // 후보는 배열 셋을 unnest 로 펼치고, LATERAL 로 해시마다 이전 세대 벡터 한 건을 고른다 — 없는 후보는 조인에서 빠진다.
    Long[] ids = new Long[rows.size()];
    String[] texts = new String[rows.size()];
    String[] hashes = new String[rows.size()];
    for (int i = 0; i < rows.size(); i++) {
      ReuseCandidate c = rows.get(i);
      ids[i] = c.rowId();
      texts[i] = c.sourceText();
      hashes[i] = c.sourceHash();
    }
    Set<Long> reused = new HashSet<>();
    dsl.fetch(
            "INSERT INTO "
                + DataSchema.qualify(ref.indexTable())
                + " (row_id, chunk_no, source_text, source_hash, embedding, embedding_model, indexed_at)"
                + " SELECT c.row_id, 0, c.source_text, c.source_hash, p.embedding, p.embedding_model, now()"
                + " FROM unnest(?::bigint[], ?::text[], ?::text[]) AS c(row_id, source_text, source_hash)"
                + " CROSS JOIN LATERAL (SELECT q.embedding, q.embedding_model FROM "
                + DataSchema.qualify(ref.prevTable())
                + " q WHERE q.source_hash = c.source_hash AND q.embedding_model = ?"
                + " AND q.embedding IS NOT NULL LIMIT 1) p"
                + ON_CONFLICT_UPDATE
                + " RETURNING row_id",
            ids,
            texts,
            hashes,
            model)
        .forEach(r -> reused.add(r.get(0, Long.class)));
    return reused;
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
              // 키워드 전용 색인(S3)은 벡터 없이 넣는다 — NULL::vector 는 허용되고 HNSW 는 NULL 을 건너뛴다.
              r.embedding() == null ? null : VectorLiterals.toVectorLiteral(r.embedding()),
              r.embeddingModel());
    }
    batch.execute();
  }

  @Override
  public void clearEmbeddings(IndexRef ref) {
    requireCurrentTenant(ref);
    // 테이블이 없으면(첫 스윕 전) 할 일 없음.
    if (!tableExists(ref.indexTable())) return;
    dsl.execute(
        "UPDATE "
            + DataSchema.qualify(ref.indexTable())
            + " SET embedding = NULL, embedding_model = NULL");
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
  public List<RowHit> semantic(
      IndexRef ref, float[] queryVec, RowQueryFilter rowFilter, int limit) {
    requireCurrentTenant(ref);
    CompiledFilter filter = rowFilter.toPostgres();
    String vector = VectorLiterals.toVectorLiteral(queryVec);
    boolean exact = useExactScan(ref);
    List<Object> params = new ArrayList<>();
    String sql = semanticSql(ref, filter, exact, vector, limit, params);
    return dsl.transactionResult(
        cfg -> {
          var tx = DSL.using(cfg);
          if (!exact) {
            // 필터로 후보가 걸러져도 limit 을 채우도록 HNSW 가 반복 탐색한다(pgvector 0.8+).
            // relaxed_order 는 순서가 약간 어긋날 수 있어 아래에서 점수로 다시 정렬한다.
            tx.execute("SET LOCAL hnsw.iterative_scan = relaxed_order");
            tx.execute("SET LOCAL hnsw.ef_search = " + HNSW_EF_SEARCH);
          }
          List<RowHit> hits =
              new ArrayList<>(tx.fetch(sql, params.toArray()).map(PgRowSearchIndex::toHit));
          hits.sort((a, b) -> Double.compare(b.score(), a.score()));
          return hits;
        });
  }

  /**
   * 의미 검색 SQL. {@code exact} 이면 정렬식을 {@code (거리) + 0} 으로 감싸 HNSW 인덱스가 정렬을 맡지 못하게 한다 — {@code
   * enable_indexscan} 을 끄면 필터 조인이 쓰는 원본 PK 인덱스까지 막히므로 정렬식만 비튼다. package-private — 실행 계획 단언 테스트가 쓴다.
   */
  String semanticSql(
      IndexRef ref,
      CompiledFilter filter,
      boolean exact,
      String vector,
      int limit,
      List<Object> params) {
    StringBuilder sql =
        new StringBuilder("SELECT s.row_id, 1 - (s.embedding <=> ?::public.vector) AS score FROM ")
            .append(DataSchema.qualify(ref.indexTable()))
            .append(" s");
    params.add(vector);
    appendFilter(ref, filter, sql, params, " WHERE s.embedding IS NOT NULL");
    sql.append(
        exact
            ? " ORDER BY (s.embedding <=> ?::public.vector) + 0"
            : " ORDER BY s.embedding <=> ?::public.vector");
    sql.append(" LIMIT ?");
    params.add(vector);
    params.add(limit);
    return sql.toString();
  }

  /**
   * 정확 스캔 여부. 행 수는 매 질의 {@code count(*)} 대신 통계({@code reltuples})로 본다. 한 번도 ANALYZE 되지 않아 통계가
   * 없으면(-1) 임계값+1 행까지만 세는 상한 카운트로 판정한다 — 큰 색인에서도 비용이 임계값에 묶인다.
   */
  boolean useExactScan(IndexRef ref) {
    String table = DataSchema.qualify(ref.indexTable());
    Long estimate =
        dsl.fetchOne("SELECT reltuples::bigint FROM pg_class WHERE oid = to_regclass(?)", table)
            .get(0, Long.class);
    long rows =
        estimate != null && estimate >= 0
            ? estimate
            : dsl.fetchOne(
                    "SELECT count(*) FROM (SELECT 1 FROM " + table + " LIMIT ?) x",
                    exactScanMaxRows + 1)
                .get(0, Long.class);
    return rows <= exactScanMaxRows;
  }

  @Override
  public List<RowHit> keyword(IndexRef ref, String query, RowQueryFilter rowFilter, int limit) {
    requireCurrentTenant(ref);
    CompiledFilter filter = rowFilter.toPostgres();
    StringBuilder sql =
        new StringBuilder("SELECT s.row_id, word_similarity(?, s.source_text) AS score FROM ")
            .append(DataSchema.qualify(ref.indexTable()))
            .append(" s");
    List<Object> params = new ArrayList<>();
    params.add(query);
    // %> 는 컬럼이 좌변이어야 trgm 인덱스를 탄다(DocumentChunkRepository 실측 주석 참고).
    appendFilter(ref, filter, sql, params, " WHERE s.source_text %> ?");
    params.add(1, query); // %> 우변 — WHERE 절 첫 바인딩(필터 바인딩보다 앞)
    // 거리 순 색인 스캔(GiST KNN, WD-725) — 상위 limit 개만 읽는다. score 내림차순과 순위가 같다(거리 = 1 - word_similarity).
    sql.append(" ORDER BY s.source_text <->> ? LIMIT ?");
    params.add(query);
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
      IndexRef ref,
      CompiledFilter filter,
      StringBuilder sql,
      List<Object> params,
      String baseWhere) {
    if (!filter.isEmpty()) {
      sql.append(" JOIN ")
          .append(DataSchema.qualify(ref.sourceTable()))
          .append(" t ON t.id = s.row_id");
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
   * <p>색인 테이블의 스키마는 {@link DataSchema#qualify} 가 <b>현재 컨텍스트</b>에서 정한다(전역 규칙). 그래서 {@code
   * ref.tenantId()} 와 컨텍스트가 어긋나면 아무 오류 없이 다른 테넌트의 색인을 읽고 쓰거나 DROP 하게 된다. 테넌트별로 도는 배경 스윕에서 이런 불일치가
   * 생기기 쉬우므로, SQL 을 실행하기 전에 모든 공개 메서드에서 막는다. 컨텍스트가 비어 있으면(null) 역시 불일치로 거부한다(fail-closed).
   */
  private void requireCurrentTenant(IndexRef ref) {
    Long current = TenantContext.get();
    if (!Objects.equals(current, ref.tenantId())) {
      throw new IllegalStateException(
          "색인 참조의 테넌트(" + ref.tenantId() + ")와 현재 테넌트 컨텍스트(" + current + ")가 다릅니다");
    }
  }

  /** 색인 테이블 + HNSW(의미) + GIN trgm(키워드) + source_hash(재사용 조회) 인덱스를 만들고, 파이프라인 실행 롤의 권한을 회수한다. */
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
    // 인덱스 이름은 자동 명명 — startGeneration 이 테이블을 _prev 로 바꿔도 새 테이블 인덱스와 충돌하지 않는다.
    dsl.execute("CREATE INDEX ON " + q + " USING hnsw (embedding public.vector_cosine_ops)");
    // 같은 이름으로 새로 만든 테이블이라 「확인함」 기억을 지운다 — 다음 ensureKeywordIndex 가 한 번 다시 본다(새 테이블은 GiST 라 할 일 없음).
    keywordIndexChecked.remove(ref.tenantId() + ":" + q);
    // 키워드 색인은 GiST — 거리 순 색인 스캔(KNN)으로 상위 k 개만 읽는다(WD-725). 옛 GIN 테이블은 ensureKeywordIndex 가 올린다.
    dsl.execute("CREATE INDEX ON " + q + " USING gist (source_text public.gist_trgm_ops)");
    dsl.execute("CREATE INDEX ON " + q + " (source_hash)");
    revokeFromPipelineExecutor(ref, q);
  }

  /**
   * 테넌트 데이터 스키마의 기본 권한(ALTER DEFAULT PRIVILEGES, TenantSchemaProvisioner)은 새 테이블마다 파이프라인 실행 롤에
   * SELECT/INSERT/UPDATE/DELETE 를 준다. 색인 테이블은 사용자 데이터가 아니라 내부 파생물이므로, 사용자 파이프라인 SQL 이 읽거나 오염시키지 못하게
   * 만든 직후 모두 회수한다.
   *
   * <p>실행 롤은 자동 프로비저닝이 꺼진 환경 등에서 아직 없을 수 있다 — 없으면 줄 권한도 없으므로 건너뛴다(없는 롤에 REVOKE 하면 오류). 롤 이름·존재 판정은
   * 정본 헬퍼({@link TenantPipelineRole#roleName}, {@link TenantSchemaProvisioner#roleExists})를 그대로 쓴다.
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
