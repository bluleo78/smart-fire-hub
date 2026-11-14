package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.CANDIDATE_FACTOR;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.allOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.anyOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenant;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenantKey;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.ValueFactory.value;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.DatasetVectorIndex;
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
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * {@link DatasetVectorIndex} 의 Qdrant 구현(ADR 0002 결정 2·5·7, WD-720). vector-store=qdrant 일 때만
 * 뜬다(플랫폼 DB 와 무관).
 *
 * <p>컬렉션: dense 는 차원별 {@code fh_datasets_{dim}}, 키워드는 차원과 무관한 {@code fh_datasets_kw}(A안). 점 id 는
 * (테넌트, 데이터셋)의 UUIDv5 라 다시 쓰면 덮어쓴다. 원문은 싣지 않는다 — id·벡터·필터 payload 만 있다.
 *
 * <p><b>검색은 가시성 2단계다.</b> ① 플랫폼 DB 에서 볼 수 있는 데이터셋 id 를 구해 필터로 넘기고 topK×{@value
 * QdrantSupport#CANDIDATE_FACTOR} 후보를 받는다. ② 후보를 플랫폼 DB 에서 지금 SQL 조건 전부로 다시 확인하고 표시 필드를 읽는다. ② 전에는
 * 결과를 밖으로 내보내지 않는다. 의미 검색에는 {@code tenant_id}+{@code model} 필터가, 키워드 검색에는 {@code tenant_id} 필터가 반드시
 * 붙는다. 테넌트 문맥이 없으면 예외다.
 *
 * <p>점수 척도: 의미 검색은 코사인 유사도로 pgvector 와 같다. 키워드는 sparse IDF 내적이라 pg_trgm(0~1)과 다르다 — 서비스 융합은 순위
 * 기반(RRF)이라 영향이 없다(ADR 0002 결정 8).
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantDatasetVectorIndex implements DatasetVectorIndex {

  private final QdrantClient client;
  private final QdrantProperties props;
  private final SearchCandidateRepository candidates;

  public QdrantDatasetVectorIndex(
      QdrantClient client, QdrantProperties props, SearchCandidateRepository candidates) {
    this.client = client;
    this.props = props;
    this.candidates = candidates;
  }

  @Override
  public List<DatasetSearchHit> semantic(
      EmbeddingSpace space,
      float[] queryEmbedding,
      String storageType,
      int topK,
      DatasetVisibility visibility) {
    long tenantId = TenantContext.require("Qdrant 데이터셋 의미 검색");
    List<Long> visible = candidates.findVisibleDatasetIds(visibility, storageType, null);
    if (visible.isEmpty() || topK <= 0) return List.of();
    List<ScoredPoint> points =
        query(
            QueryPoints.newBuilder()
                .setCollectionName(space.dimension().datasetCollection())
                .setUsing(QdrantCollectionSpec.DENSE)
                .setQuery(QueryFactory.nearest(queryEmbedding))
                .setFilter(
                    allOf(
                        tenant(tenantId),
                        matchKeyword(QdrantCollectionSpec.MODEL, space.model()),
                        anyOf(QdrantCollectionSpec.DATASET_ID, visible)))
                .setParams(QdrantSupport.rescore())
                .setLimit(topK * CANDIDATE_FACTOR),
            "데이터셋 의미 검색");
    return confirm(
        points, topK, ids -> candidates.findDatasetHits(ids, visibility, storageType, space));
  }

  @Override
  public List<DatasetSearchHit> keyword(
      String query, String storageType, int topK, DatasetVisibility visibility) {
    long tenantId = TenantContext.require("Qdrant 데이터셋 키워드 검색");
    BigramSparseEncoder.SparseTerms terms = BigramSparseEncoder.query(query);
    if (terms.indices().length == 0 || topK <= 0) return List.of();
    List<Long> visible = candidates.findVisibleDatasetIds(visibility, storageType, null);
    if (visible.isEmpty()) return List.of();
    List<ScoredPoint> points =
        query(
            QueryPoints.newBuilder()
                .setCollectionName(QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION)
                .setUsing(QdrantCollectionSpec.KW_BIGRAM)
                .setQuery(QueryFactory.nearest(terms.valueList(), terms.indexList()))
                .setFilter(allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, visible)))
                .setLimit(topK * CANDIDATE_FACTOR),
            "데이터셋 키워드 검색");
    return confirm(
        points, topK, ids -> candidates.findDatasetHits(ids, visibility, storageType, null));
  }

  /**
   * dense 를 {@code space} 의 컬렉션에 쓰고(덮어쓰기), 같은 데이터셋의 다른 차원 점은 커밋 뒤 지운다. 쓰기는 바로 한다 — 실패하면 예외가 트랜잭션을
   * 롤백해 표시 열이 차지 않고 백로그가 다시 잡는다. 부모가 없는 id 는 호출한 리포지토리가 거른다.
   */
  @Override
  public void upsertDense(EmbeddingSpace space, List<Long> datasetIds, List<float[]> embeddings) {
    if (datasetIds.size() != embeddings.size()) {
      throw new IllegalArgumentException(
          "데이터셋 수와 임베딩 수 불일치: " + datasetIds.size() + " vs " + embeddings.size());
    }
    if (datasetIds.isEmpty()) return;
    long tenantId = TenantContext.require("Qdrant 데이터셋 벡터 쓰기");
    List<PointStruct> points = new ArrayList<>(datasetIds.size());
    for (int i = 0; i < datasetIds.size(); i++) {
      long id = datasetIds.get(i);
      points.add(
          PointStruct.newBuilder()
              .setId(pointId(tenantId, id))
              .setVectors(
                  VectorsFactory.namedVectors(
                      Map.of(QdrantCollectionSpec.DENSE, VectorFactory.vector(embeddings.get(i)))))
              .putAllPayload(
                  Map.of(
                      QdrantCollectionSpec.TENANT_ID, value(tenantKey(tenantId)),
                      QdrantCollectionSpec.DATASET_ID, value(id),
                      QdrantCollectionSpec.MODEL, value(space.model())))
              .build());
    }
    await(
        client.upsertAsync(space.dimension().datasetCollection(), points, props.timeout()),
        props.timeout(),
        "데이터셋 벡터 쓰기");
    Filter mine = allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, datasetIds));
    QdrantSupport.deleteAfterCommit(
        "다른 차원 데이터셋 벡터",
        () -> {
          for (EmbeddingDimension d : EmbeddingDimension.values()) {
            if (d != space.dimension()) delete(d.datasetCollection(), mine);
          }
        });
  }

  /** 모든 차원 컬렉션에서 이 데이터셋들의 dense 점을 지운다(키워드 컬렉션은 그대로). 지울 점 수를 지금 세어 돌려주고 삭제는 커밋 뒤에 한다. */
  @Override
  public int deleteDense(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return 0;
    Filter mine = allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, datasetIds));
    return deleteFromDenseCollections(mine, d -> true, "데이터셋 dense 정리");
  }

  @Override
  public int deleteOtherDimensions(long tenantId, EmbeddingDimension keep) {
    return deleteFromDenseCollections(allOf(tenant(tenantId)), d -> d != keep, "다른 차원 데이터셋 벡터 정리");
  }

  /** 데이터셋 삭제 — dense·키워드 컬렉션 모두에서 지운다(커밋 뒤). */
  @Override
  public void deleteAll(long tenantId, List<Long> datasetIds) {
    if (datasetIds.isEmpty()) return;
    Filter mine = allOf(tenant(tenantId), anyOf(QdrantCollectionSpec.DATASET_ID, datasetIds));
    QdrantSupport.deleteAfterCommit(
        "데이터셋 색인 전부",
        () -> {
          for (EmbeddingDimension d : EmbeddingDimension.values()) {
            delete(d.datasetCollection(), mine);
          }
          delete(QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION, mine);
        });
  }

  @Override
  public boolean separateKeywordIndex() {
    return true;
  }

  @Override
  public String keywordRule() {
    return BigramSparseEncoder.KEYWORD_RULE;
  }

  /** 키워드 sparse 를 키워드 컬렉션에 쓴다. 낱말이 없는 원문(빈 문자열 등)은 그 점을 지운다. */
  @Override
  public void upsertKeyword(long tenantId, List<KeywordSource> sources) {
    QdrantKeywordWriter.write(
        client,
        props,
        QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION,
        tenantId,
        sources,
        BigramSparseEncoder.Profile.DATASET,
        s -> pointId(tenantId, s.id()),
        s -> Map.of(QdrantCollectionSpec.DATASET_ID, value(s.datasetId())));
  }

  private static PointId pointId(long tenantId, long datasetId) {
    return PointIdFactory.id(QdrantPointIds.dataset(tenantId, datasetId));
  }

  private List<ScoredPoint> query(QueryPoints.Builder q, String what) {
    q.setWithPayload(WithPayloadSelectorFactory.include(List.of(QdrantCollectionSpec.DATASET_ID)));
    return await(client.queryAsync(q.build(), props.timeout()), props.timeout(), what);
  }

  /** 사후 확인 — 후보 id 를 플랫폼 DB 로 다시 확인해 통과한 것만 Qdrant 점수 순서로 topK 까지 돌려준다. 저장소 점수를 표시 필드 히트에 붙인다. */
  private static List<DatasetSearchHit> confirm(
      List<ScoredPoint> points, int topK, Function<List<Long>, List<DatasetSearchHit>> lookup) {
    Map<Long, Float> scoreById = new LinkedHashMap<>();
    for (ScoredPoint p : points) {
      Long id = QdrantSupport.longPayload(p, QdrantCollectionSpec.DATASET_ID);
      if (id != null) scoreById.putIfAbsent(id, p.getScore());
    }
    if (scoreById.isEmpty()) return List.of();
    Map<Long, DatasetSearchHit> confirmed =
        lookup.apply(new ArrayList<>(scoreById.keySet())).stream()
            .collect(Collectors.toMap(DatasetSearchHit::datasetId, h -> h, (a, b) -> a));
    List<DatasetSearchHit> out = new ArrayList<>();
    for (Map.Entry<Long, Float> e : scoreById.entrySet()) {
      DatasetSearchHit h = confirmed.get(e.getKey());
      if (h == null) continue;
      out.add(
          new DatasetSearchHit(
              h.datasetId(),
              h.name(),
              h.description(),
              h.storageType(),
              h.originType(),
              h.tableName(),
              h.category(),
              e.getValue()));
      if (out.size() == topK) break;
    }
    return out;
  }

  /** 고른 차원 컬렉션에서 필터에 맞는 점 수를 지금 세고, 삭제는 커밋 뒤로 미룬다. */
  private int deleteFromDenseCollections(
      Filter filter, java.util.function.Predicate<EmbeddingDimension> which, String what) {
    long total = 0;
    List<String> targets = new ArrayList<>();
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (!which.test(d)) continue;
      targets.add(d.datasetCollection());
      total +=
          await(
              client.countAsync(d.datasetCollection(), filter, true, props.timeout()),
              props.timeout(),
              what + " 세기");
    }
    QdrantSupport.deleteAfterCommit(what, () -> targets.forEach(c -> delete(c, filter)));
    return Math.toIntExact(total);
  }

  private void delete(String collection, Filter filter) {
    await(
        client.deleteAsync(collection, filter, props.timeout()),
        props.timeout(),
        collection + " 삭제");
  }
}
