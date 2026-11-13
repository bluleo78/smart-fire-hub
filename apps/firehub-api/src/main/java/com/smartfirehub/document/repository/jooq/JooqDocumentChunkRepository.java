package com.smartfirehub.document.repository.jooq;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.platformdb.ConditionalOnPostgresPlatformDb;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.securitylevel.repository.EmbeddingAiPolicyRepository;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * DocumentChunkRepository 의 jOOQ(PostgreSQL) 구현 — WD-485.
 *
 * <p>document_chunk 본문 + 차원별 벡터 테이블(document_chunk_vec_N) 적재·검색(#713).
 *
 * <p>벡터·트라이그램 SQL 은 벡터 저장소 포트({@link ChunkVectorIndex}, ADR 0002 결정 2)로 나갔다. 이 리포지토리는 청크 본문·표시
 * 열·백로그 판정만 다룬다. 벡터를 쓰거나 지우는 메서드는 포트를 부른 뒤 같은 트랜잭션(클래스 레벨 {@code @Transactional})에서 표시 열을 맞춘다.
 * pgvector 구현은 그 트랜잭션에 합류하므로 청크·벡터·표시 열이 함께 커밋되거나 함께 롤백된다.
 */
@Repository
@ConditionalOnPostgresPlatformDb
@RequiredArgsConstructor
// 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없다. RLS GUC 는 트랜잭션 시작
// 시점에만 주입되므로, 트랜잭션이 없으면 TenantContext 를 세워도 정책이 전 행을 차단한다.
// REQUIRED 라 서비스가 이미 연 트랜잭션에는 합류한다(기존 경로 동작 불변).
@Transactional
public class JooqDocumentChunkRepository implements DocumentChunkRepository {

  private final DSLContext dsl;

  // 임베딩 등급 허용 술어 렌더(WD-485 T10) — 별칭 c.dataset_id 는 이 구현이 소유한다(예전 호출자가 넘기던 값 그대로).
  private final EmbeddingAiPolicyRepository aiPolicyRepository;

  // 벡터 저장소 포트 — 벡터·트라이그램 SQL 은 여기로 나갔다(ADR 0002 결정 2).
  private final ChunkVectorIndex vectorIndex;

  private static final int BATCH_SIZE = 200;

  /** 해당 문서의 기존 청크를 모두 삭제(잡 재시도 시 중복 방지). */
  @Override
  public void deleteByDocumentFileId(Long documentFileId) {
    dsl.deleteFrom(table(name("document_chunk")))
        .where(field(name("document_chunk", "document_file_id"), Long.class).eq(documentFileId))
        .execute();
  }

  /**
   * 청크 본문은 부모 테이블에, 벡터는 포트로 넣는다(한 트랜잭션 — 클래스 레벨 @Transactional, pgvector 구현이 합류). 새 청크라 다른 차원 행이 있을
   * 수 없으므로 삭제 없이 INSERT 한다. 벡터 행은 {@code RETURNING id, chunk_index} 의 <b>chunk_index 로</b>
   * 매핑한다(RETURNING 순서는 VALUES 순서를 보장하지 않는다).
   */
  @Override
  public void insertBatch(
      Long documentFileId,
      Long datasetId,
      List<Chunk> chunks,
      List<float[]> embeddings,
      EmbeddingSpace space) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunks.size() + " vs " + embeddings.size());
    }
    for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunks.size());
      // 표시 열(WD-717)을 부모 INSERT 에 바로 싣는다 — 아래 벡터 INSERT 와 같은 트랜잭션이라 둘 중 하나만 남는 일이 없다.
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, token_count,"
                  + " embedded_dim, embedded_model) VALUES ");
      List<Object> params = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) sql.append(',');
        sql.append("(?,?,?,?,?,?,?)");
        Chunk c = chunks.get(i);
        params.add(documentFileId);
        params.add(datasetId);
        params.add(c.index());
        params.add(c.content());
        params.add(c.tokenCount());
        params.add(space.dimension().size());
        params.add(space.model());
      }
      sql.append(" RETURNING id, chunk_index");
      Map<Integer, Long> idByIndex = new HashMap<>();
      dsl.fetch(sql.toString(), params.toArray())
          .forEach(
              r -> idByIndex.put(r.get("chunk_index", Integer.class), r.get("id", Long.class)));

      // 벡터는 포트로 넣는다 — chunk_index 로 찾은 id 를 청크 순서대로 넘긴다.
      List<Long> sliceIds = new ArrayList<>(end - start);
      for (int i = start; i < end; i++) {
        sliceIds.add(idByIndex.get(chunks.get(i).index()));
      }
      vectorIndex.insertDense(space, datasetId, sliceIds, embeddings.subList(start, end));
    }
  }

  /**
   * 벡터 없이 청크 본문만 넣는다 — 등급이 임베딩 공급자를 허용하지 않는 문서(S3 §4.3). 본문을 외부 공급자로 보내지 않으며, 이후 허용으로 바뀌면 재임베딩
   * 판정식(countMissing/findMissing)이 이 청크들을 집어 벡터를 채운다.
   */
  @Override
  public void insertChunksOnly(Long documentFileId, Long datasetId, List<Chunk> chunks) {
    for (int start = 0; start < chunks.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunks.size());
      StringBuilder sql =
          new StringBuilder(
              "INSERT INTO document_chunk(document_file_id, dataset_id, chunk_index, content, token_count) VALUES ");
      List<Object> params = new ArrayList<>();
      for (int i = start; i < end; i++) {
        if (i > start) sql.append(',');
        sql.append("(?,?,?,?,?)");
        Chunk c = chunks.get(i);
        params.add(documentFileId);
        params.add(datasetId);
        params.add(c.index());
        params.add(c.content());
        params.add(c.tokenCount());
      }
      dsl.execute(sql.toString(), params.toArray());
    }
  }

  /**
   * 기존 청크의 벡터를 {@code space} 로 옮긴다(포트가 다른 차원 행 DELETE + 현재 차원 UPSERT). 같은 트랜잭션에서 표시 열을 새 공간으로 맞춘다.
   * 부모가 사라진 id 는 포트·표시 열 모두 0행이라 조용히 건너뛴다(재임베딩 중 문서 삭제 경합).
   */
  @Override
  public void upsertEmbeddings(
      EmbeddingSpace space, List<Long> chunkIds, List<float[]> embeddings) {
    if (chunkIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunkIds.size() + " vs " + embeddings.size());
    }
    if (chunkIds.isEmpty()) return;
    vectorIndex.upsertDense(space, chunkIds, embeddings);
    for (int start = 0; start < chunkIds.size(); start += BATCH_SIZE) {
      int end = Math.min(start + BATCH_SIZE, chunkIds.size());
      // 표시 열(WD-717)을 같은 트랜잭션에서 새 공간으로 맞춘다. 부모가 없는 id 는 0행.
      List<Long> slice = chunkIds.subList(start, end);
      List<Object> mparams = new ArrayList<>(slice.size() + 2);
      mparams.add(space.dimension().size());
      mparams.add(space.model());
      mparams.addAll(slice);
      dsl.execute(
          "UPDATE document_chunk SET embedded_dim = ?, embedded_model = ? WHERE id IN ("
              + placeholders(slice.size())
              + ")",
          mparams.toArray());
    }
  }

  /** 해당 데이터셋의 청크 (id, content) 를 id 오름차순으로 조회. 검수·청크 목록·데이터셋 단위 재임베딩이 쓴다. */
  @Override
  public List<ChunkContent> findChunkContentsByDataset(long datasetId) {
    return dsl.fetch(
            "SELECT id, content FROM document_chunk WHERE dataset_id = ? ORDER BY id", datasetId)
        .map(r -> new ChunkContent(r.get("id", Long.class), r.get("content", String.class)));
  }

  /**
   * 주어진 청크 id 들 중 해당 데이터셋에 속한 것의 개수(중복 id 는 한 번만 센다). 검수 항목 등록이 클라이언트가 보낸 근거 청크가 정말 그 데이터셋의 것인지 확인할
   * 때 쓴다 — 없는 청크와 다른 데이터셋(또는 다른 테넌트, RLS)의 청크를 구분하지 않는다.
   */
  @Override
  public long countChunksInDataset(long datasetId, Collection<Long> chunkIds) {
    if (chunkIds.isEmpty()) return 0;
    return dsl.fetchOne(
            "SELECT count(*) FROM document_chunk WHERE dataset_id = ? AND id = ANY(?)",
            datasetId,
            chunkIds.toArray(Long[]::new))
        .get(0, Long.class);
  }

  /**
   * 전체 청크 수. 재임베딩 진행률 계산의 분모 — 판정식과 같은 모집단이어야 하므로 {@code allowance}(EmbeddingAiGate #allowance() 가
   * 캡처한 값, 구현이 별칭 {@code c.dataset_id} 로 렌더)로 등급이 임베딩 공급자를 허용하지 않는 데이터셋의 청크를 뺀다(S3 §4.3).
   */
  @Override
  public long countAllChunks(EmbeddingAllowance allowance) {
    return dsl.fetchOne("SELECT COUNT(*) FROM document_chunk c WHERE " + allowedSql(allowance))
        .get(0, Long.class);
  }

  /** {@code space} 로 임베딩된 청크 수(RLS 로 현재 테넌트). 진행률 분자 — 표시 열(WD-717)로 센다. */
  @Override
  public long countEmbedded(EmbeddingSpace space) {
    return dsl.fetchOne(
            "SELECT count(*) FROM document_chunk WHERE embedded_dim = ? AND embedded_model = ?",
            space.dimension().size(),
            space.model())
        .get(0, Long.class);
  }

  /**
   * 재임베딩 판정식: 표시 열이 현재 공간이 아니고 <b>등급이 임베딩 공급자를 허용하는</b> 데이터셋의 청크 수. 표시 열은 벡터 행의 거울이라 예전 "현재 차원 테이블에
   * 현재 모델 벡터가 없음" anti-join 과 같은 결과다(WD-717). 영향도·저장 시 투입·잡이 같은 식을 쓴다. {@code allowance} 는
   * EmbeddingAiGate#allowance() 가 캡처한 값(별칭 {@code c.dataset_id} 렌더는 구현) — 정책 없는 오버로드는 두지 않는다.
   */
  @Override
  public long countMissing(EmbeddingSpace space, EmbeddingAllowance allowance) {
    return dsl.fetchOne(
            "SELECT count(*) FROM document_chunk c WHERE "
                + MISSING_PREDICATE
                + " AND "
                + allowedSql(allowance),
            space.dimension().size(),
            space.model())
        .get(0, Long.class);
  }

  /** 판정식(등급 허용 포함)을 만족하는 청크를 id 순으로 {@code limit} 건(키셋 {@code afterChunkId} 이후). */
  @Override
  public List<ChunkContent> findMissing(
      EmbeddingSpace space, long afterChunkId, int limit, EmbeddingAllowance allowance) {
    return dsl.fetch(
            "SELECT c.id, c.content FROM document_chunk c WHERE c.id > ? AND "
                + MISSING_PREDICATE
                + " AND "
                + allowedSql(allowance)
                + " ORDER BY c.id LIMIT ?",
            afterChunkId,
            space.dimension().size(),
            space.model(),
            limit)
        .map(r -> new ChunkContent(r.get("id", Long.class), r.get("content", String.class)));
  }

  /**
   * 현재 차원이 아닌 테이블의 이 테넌트 벡터를 지운다(재임베딩 완료 뒤 잔여 정리). RLS 만으로도 테넌트 범위지만 {@code WHERE tenant_id = ?} 를
   * 명시한다 — 소유자 커넥션에서 불려도 남의 행을 지우지 않게(조건 없는 DELETE 금지 규율).
   */
  @Override
  public int deleteOtherDimensions(EmbeddingDimension keep) {
    long tenantId = TenantContext.require("다른 차원 벡터 정리");
    int deleted = vectorIndex.deleteOtherDimensions(tenantId, keep);
    // 표시 열도 같은 트랜잭션에서 비운다 — 다른 차원을 가리키던 거울은 방금 지운 벡터를 가리키기 때문이다(WD-717).
    dsl.execute(
        "UPDATE document_chunk SET embedded_dim = NULL, embedded_model = NULL"
            + " WHERE tenant_id = ? AND embedded_dim <> ?",
        tenantId,
        keep.size());
    return deleted;
  }

  /**
   * 여러 데이터셋의 청크 벡터를 모든 차원에서 지운다(청크 본문 content 는 남겨 키워드 검색 유지). 정책 위반 외부 벡터 정리(AiVectorPurgeService,
   * S3 §4.3)용 — 지운 행 수를 돌려준다. {@code tenant_id} 를 명시한다(조건 없는 DELETE 금지 규율).
   */
  @Override
  public int deleteVectorsOf(List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    long tenantId = TenantContext.require("청크 벡터 정리");
    Long[] ids = datasetIds.toArray(Long[]::new);
    int deleted = vectorIndex.deleteDense(tenantId, datasetIds);
    // 표시 열도 같은 트랜잭션에서 비운다(WD-717) — 청크 본문은 남아 키워드 검색은 유지된다.
    dsl.execute(
        "UPDATE document_chunk SET embedded_dim = NULL, embedded_model = NULL"
            + " WHERE tenant_id = ? AND dataset_id = ANY(?)",
        tenantId,
        ids);
    return deleted;
  }

  /**
   * 판정식 — 표시 열(WD-717)이 현재 공간(차원·모델)을 가리키지 않는 청크. 바인드 순서: 차원, 모델. NULL 을 명시적으로 다뤄 {@code IS
   * DISTINCT FROM} 이 없는 DB(Tibero)에도 같은 식을 옮길 수 있게 한다.
   */
  private static final String MISSING_PREDICATE =
      "(c.embedded_dim IS NULL OR c.embedded_dim <> ? OR c.embedded_model IS NULL"
          + " OR c.embedded_model <> ?)";

  private static String placeholders(int n) {
    return java.util.stream.IntStream.range(0, n)
        .mapToObj(i -> "?")
        .collect(Collectors.joining(","));
  }

  /**
   * 등급 허용 술어 — 예전 호출자가 넘기던 {@code aiGate.allowedDatasetSql("c.dataset_id")} 와 바이트 동일한 렌더다. 별칭은 이
   * 클래스 SQL 의 {@code document_chunk c} 에 맞춘 코드 상수다. 호스팅은 호출자가 캡처한 값({@link EmbeddingAllowance})을
   * 그대로 쓴다.
   */
  private String allowedSql(EmbeddingAllowance allowance) {
    return aiPolicyRepository.allowedDatasetSql("c.dataset_id", allowance.hosting());
  }
}
