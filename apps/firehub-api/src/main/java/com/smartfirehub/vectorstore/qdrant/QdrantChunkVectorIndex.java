package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.CANDIDATE_FACTOR;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.allOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.anyOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenant;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenantKey;
import static io.qdrant.client.ConditionFactory.match;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ValueFactory.value;

import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.vectorstore.ChunkRef;
import com.smartfirehub.vectorstore.ChunkVectorIndex;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.KeywordSource;
import com.smartfirehub.vectorstore.SearchCandidateRepository;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link ChunkVectorIndex} 의 Qdrant 구현(WD-720) — {@link QdrantDatasetVectorIndex} 와 같은 일을 문서 청크에
 * 한다.
 *
 * <p>컬렉션: dense 는 {@code fh_chunks_{dim}}, 키워드는 {@code fh_chunks_kw}. 점 payload 에 데이터셋·문서 id 를 실어
 * 데이터셋 단위 dense 정리와 문서 삭제를 필터로 한다. 검색은 가시성 2단계이고, 사후 확인이 문서 {@code COMPLETED}·요청 데이터셋·가시성·공간을 다시 본다
 * — 그래야 적재 중이거나 실패한 문서의 청크가 섞이지 않는다(ADR 0002 결정 5).
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantChunkVectorIndex implements ChunkVectorIndex {

  private final QdrantClient client;
  private final QdrantProperties props;
  private final SearchCandidateRepository candidates;

  public QdrantChunkVectorIndex(
      QdrantClient client, QdrantProperties props, SearchCandidateRepository candidates) {
    this.client = client;
    this.props = props;
    this.candidates = candidates;
  }

  @Override
  public List<DocumentSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      List<Long> datasetIds,
      int topK,
      DatasetVisibility visibility) {
    long tenantId = TenantContext.require("Qdrant 청크 의미 검색");
    // 요청 데이터셋을 가시성 목록에 미리 교집합해 둔다 — topK×3 후보를 다른 데이터셋이 다 차지하지 않게.
    List<Long> visible = candidates.findVisibleDatasetIds(visibility, null, datasetIds);
    if (visible.isEmpty() || topK <= 0) return List.of();
    List<ScoredPoint> points =
        query(
            QueryPoints.newBuilder()
                .setCollectionName(space.dimension().chunkCollection())
                .setUsing(QdrantCollectionSpec.DENSE)
                .setQuery(QueryFactory.nearest(queryEmbedding))
                .setFilter(
                    allOf(
                        tenant(tenantId),
                        matchKeyword(QdrantCollectionSpec.MODEL, space.model()),
                        anyOf(QdrantCollectionSpec.DATASET_ID, visible)))
                .setParams(QdrantSupport.rescore())
                .setLimit(topK * CANDIDATE_FACTOR),
            "청크 의미 검색");
    return confirm(
        points, topK, ids -> candidates.findChunkHits(ids, visibility, datasetIds, space));
  }

  @Override
  public List<DocumentSearchHit> keyword(
      String query, List<Long> datasetIds, int topK, DatasetVisibility visibility) {
    long tenantId = TenantContext.require("Qdrant 청크 키워드 검색");
    BigramSparseEncoder.SparseTerms terms = BigramSparseEncoder.query(query);
    if (terms.indices().length == 0 || topK <= 0) return List.of();
    List<Long> visible = candidates.findVisibleDatasetIds(visibility, null, datasetIds);
    if (visible.isEmpty()) return List.of();
    List<ScoredPoint> points =
        query(
            QueryPoints.newBuilder()
                .setCollectionName(QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION)
                .setUsing(QdrantCollectionSpec.KW_BIGRAM)
                .setQuery(QueryFactory.nearest(terms.valueList(), terms.indexList()))
                .setFilter(allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, visible)))
                .setLimit(topK * CANDIDATE_FACTOR),
            "청크 키워드 검색");
    return confirm(
        points, topK, ids -> candidates.findChunkHits(ids, visibility, datasetIds, null));
  }

  /** dense 를 {@code space} 의 컬렉션에 쓰고(덮어쓰기), 같은 청크의 다른 차원 점은 커밋 뒤 지운다. */
  @Override
  public void upsertDense(EmbeddingSpace space, List<ChunkRef> chunks, List<float[]> embeddings) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "청크 수와 임베딩 수 불일치: " + chunks.size() + " vs " + embeddings.size());
    }
    if (chunks.isEmpty()) return;
    long tenantId = TenantContext.require("Qdrant 청크 벡터 쓰기");
    List<PointStruct> points = new ArrayList<>(chunks.size());
    for (int i = 0; i < chunks.size(); i++) {
      ChunkRef ref = chunks.get(i);
      points.add(
          PointStruct.newBuilder()
              .setId(pointId(tenantId, ref.chunkId()))
              .setVectors(
                  VectorsFactory.namedVectors(
                      Map.of(QdrantCollectionSpec.DENSE, VectorFactory.vector(embeddings.get(i)))))
              .putAllPayload(
                  Map.of(
                      QdrantCollectionSpec.TENANT_ID, value(tenantKey(tenantId)),
                      QdrantCollectionSpec.DATASET_ID, value(ref.datasetId()),
                      QdrantCollectionSpec.DOCUMENT_ID, value(ref.documentFileId()),
                      QdrantCollectionSpec.CHUNK_ID, value(ref.chunkId()),
                      QdrantCollectionSpec.MODEL, value(space.model())))
              .build());
    }
    await(
        client.upsertAsync(space.dimension().chunkCollection(), points, props.timeout()),
        props.timeout(),
        "청크 벡터 쓰기");
    List<PointId> ids = chunks.stream().map(r -> pointId(tenantId, r.chunkId())).toList();
    QdrantSupport.deleteAfterCommit(
        "다른 차원 청크 벡터",
        () -> {
          for (EmbeddingDimension d : EmbeddingDimension.values()) {
            if (d == space.dimension()) continue;
            await(
                client.deleteAsync(d.chunkCollection(), ids, props.timeout()),
                props.timeout(),
                d.chunkCollection() + " 삭제");
          }
        });
  }

  @Override
  public int deleteDense(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    return deleteFromDenseCollections(
        allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, datasetIds)),
        d -> true,
        "청크 dense 정리");
  }

  @Override
  public int deleteOtherDimensions(long tenantId, EmbeddingDimension keep) {
    return deleteFromDenseCollections(allOf(tenant(tenantId)), d -> d != keep, "다른 차원 청크 벡터 정리");
  }

  /** 문서 삭제·재적재 — dense·키워드 컬렉션 모두에서 그 문서의 점을 지운다(트랜잭션 안이면 커밋 뒤). */
  @Override
  public void deleteAllOfDocument(long tenantId, long documentFileId) {
    deleteEverywhere(
        allOf(tenant(tenantId), match(QdrantCollectionSpec.DOCUMENT_ID, documentFileId)),
        "문서 청크 색인 전부");
  }

  @Override
  public void deleteAllOfDatasets(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return;
    deleteEverywhere(
        allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, datasetIds)),
        "데이터셋 청크 색인 전부");
  }

  @Override
  public boolean separateKeywordIndex() {
    return true;
  }

  @Override
  public String keywordRule() {
    return BigramSparseEncoder.KEYWORD_RULE;
  }

  @Override
  public void upsertKeyword(long tenantId, List<KeywordSource> sources) {
    QdrantKeywordWriter.write(
        client,
        props,
        QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION,
        tenantId,
        sources,
        BigramSparseEncoder.Profile.CHUNK,
        s -> pointId(tenantId, s.id()),
        s ->
            Map.of(
                QdrantCollectionSpec.DATASET_ID, value(s.datasetId()),
                QdrantCollectionSpec.DOCUMENT_ID, value(s.documentFileId()),
                QdrantCollectionSpec.CHUNK_ID, value(s.id())));
  }

  private static PointId pointId(long tenantId, long chunkId) {
    return PointIdFactory.id(QdrantPointIds.chunk(tenantId, chunkId));
  }

  private List<ScoredPoint> query(QueryPoints.Builder q, String what) {
    q.setWithPayload(WithPayloadSelectorFactory.include(List.of(QdrantCollectionSpec.CHUNK_ID)));
    return await(client.queryAsync(q.build(), props.timeout()), props.timeout(), what);
  }

  /** 사후 확인 — 후보 청크를 플랫폼 DB 로 다시 확인해 통과한 것만 Qdrant 점수 순서로 topK 까지 돌려준다. */
  private static List<DocumentSearchHit> confirm(
      List<ScoredPoint> points, int topK, Function<List<Long>, List<DocumentSearchHit>> lookup) {
    Map<Long, Float> scoreById = new LinkedHashMap<>();
    for (ScoredPoint p : points) {
      Long id = QdrantSupport.longPayload(p, QdrantCollectionSpec.CHUNK_ID);
      if (id != null) scoreById.putIfAbsent(id, p.getScore());
    }
    if (scoreById.isEmpty()) return List.of();
    Map<Long, DocumentSearchHit> confirmed =
        lookup.apply(new ArrayList<>(scoreById.keySet())).stream()
            .collect(Collectors.toMap(DocumentSearchHit::chunkId, h -> h, (a, b) -> a));
    List<DocumentSearchHit> out = new ArrayList<>();
    for (Map.Entry<Long, Float> e : scoreById.entrySet()) {
      DocumentSearchHit h = confirmed.get(e.getKey());
      if (h == null) continue;
      out.add(
          new DocumentSearchHit(
              h.chunkId(),
              h.documentFileId(),
              h.datasetId(),
              h.fileName(),
              h.chunkIndex(),
              h.content(),
              e.getValue()));
      if (out.size() == topK) break;
    }
    return out;
  }

  private int deleteFromDenseCollections(
      Filter filter, Predicate<EmbeddingDimension> which, String what) {
    long total = 0;
    List<String> targets = new ArrayList<>();
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (!which.test(d)) continue;
      targets.add(d.chunkCollection());
      total +=
          await(
              client.countAsync(d.chunkCollection(), filter, true, props.timeout()),
              props.timeout(),
              what + " 세기");
    }
    QdrantSupport.deleteAfterCommit(
        what,
        () ->
            targets.forEach(
                c ->
                    await(
                        client.deleteAsync(c, filter, props.timeout()),
                        props.timeout(),
                        c + " 삭제")));
    return Math.toIntExact(total);
  }

  private void deleteEverywhere(Filter filter, String what) {
    QdrantSupport.deleteAfterCommit(
        what,
        () -> {
          for (EmbeddingDimension d : EmbeddingDimension.values()) {
            await(
                client.deleteAsync(d.chunkCollection(), filter, props.timeout()),
                props.timeout(),
                d.chunkCollection() + " 삭제");
          }
          await(
              client.deleteAsync(
                  QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION, filter, props.timeout()),
              props.timeout(),
              "청크 키워드 삭제");
        });
  }
}
