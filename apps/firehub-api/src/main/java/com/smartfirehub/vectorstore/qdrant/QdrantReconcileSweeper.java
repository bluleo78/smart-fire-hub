package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.allOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.seqBefore;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenant;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorMarkerRepository;
import com.smartfirehub.vectorstore.VectorMarkerRepository.Marker;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.GetPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongFunction;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대조 스윕(WD-721, ADR 0002 결정 6) — 플랫폼 DB 표시 열과 Qdrant 점을 양방향으로 맞춘다. 아웃박스 행이 사라졌거나(수동 정리·버그) 쓰기 경로가
 * 중간에 죽어 둘이 어긋난 것을 주기마다 바로잡는다.
 *
 * <ol>
 *   <li><b>저장소 → DB:</b> 표시가 이 점을 가리키지 않는 점을 지운다. dense 는 표시가 그 차원·모델일 때만 남긴다 — 등급 불허로 표시를 비웠는데
 *       아웃박스가 유실된 벡터도 여기서 지워진다. 키워드는 원문 행이 있으면 남긴다(키워드는 정리 대상이 아니고, 표시만 빠진 점은 키워드 백로그가 다시 쓴다).
 *   <li><b>DB → 저장소:</b> 표시는 찼는데 점이 없거나 모델이 다르면 표시를 비워 백로그로 돌린다. 쓰기 경로는 점을 쓴 뒤 같은 트랜잭션에서 표시를 채우고,
 *       삭제 경로는 아웃박스를 쌓는 트랜잭션에서 표시를 비우므로, 표시가 찼다면 점이 있어야 한다.
 * </ol>
 *
 * <p><b>울타리 순번.</b> 1번은 쓰기 경로와 경합한다 — 점을 쓰고 아직 표시를 채우는 트랜잭션이 커밋되기 전에 스윕이 보면 "표시 없는 점" 이다. 그래서 이번
 * 회차는 <b>지난 회차를 시작할 때 받은 순번보다 작은 점</b>만 본다(울타리). 그만큼 오래된 쓰기는 이미 커밋됐거나 롤백됐다. 울타리는 파드 메모리에 두므로 재기동 뒤
 * 첫 회차는 울타리만 세우고 1번을 건너뛴다. 지울 때도 같은 울타리를 조건에 붙여, 훑은 뒤 그 id 로 다시 쓴 점은 지우지 않는다.
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class QdrantReconcileSweeper {

  /** 한 번에 훑는 점·표시 행 수. */
  static final int PAGE = 256;

  /** 회차 결과. */
  public record Result(int removedPoints, int clearedMarkers) {}

  private final QdrantClient client;
  private final QdrantProperties props;
  private final TenantScopedRunner tenantRunner;
  private final VectorOutboxRepository outbox;
  private final VectorMarkerRepository markers;
  private final VectorStoreFeatures features;
  private final boolean enabled;

  /** 테넌트별 지난 회차 울타리(파드 메모리). */
  private final Map<Long, Long> fences = new ConcurrentHashMap<>();

  public QdrantReconcileSweeper(
      QdrantClient client,
      QdrantProperties props,
      TenantScopedRunner tenantRunner,
      VectorOutboxRepository outbox,
      VectorMarkerRepository markers,
      VectorStoreFeatures features,
      @org.springframework.beans.factory.annotation.Value(
              "${firehub.vector-store.reconcile-sweep-enabled:true}")
          boolean enabled) {
    this.client = client;
    this.props = props;
    this.tenantRunner = tenantRunner;
    this.outbox = outbox;
    this.markers = markers;
    this.features = features;
    this.enabled = enabled;
  }

  /** 기본 30분 주기. 한 테넌트의 예외는 {@link TenantScopedRunner} 가 격리한다. */
  @Scheduled(
      initialDelayString = "${firehub.vector-store.reconcile-sweep-interval-ms:1800000}",
      fixedDelayString = "${firehub.vector-store.reconcile-sweep-interval-ms:1800000}")
  public void sweep() {
    if (!enabled) return;
    // 저장소가 내려가 있으면 건너뛴다(WD-724). 울타리는 그대로 두므로 다음 회차가 지난 회차 기준으로 이어 한다.
    if (!features.available()) return;
    tenantRunner.forEachActiveTenant(
        tenantId -> {
          long next = outbox.nextSeq();
          Long fence = fences.put(tenantId, next);
          Result r = reconcileCurrentTenant(fence);
          if (r.removedPoints() > 0 || r.clearedMarkers() > 0) {
            log.info("벡터 대조 스윕: tenant={}, 결과={}", tenantId, r);
          }
        });
  }

  /**
   * 현재 테넌트를 대조한다.
   *
   * @param fence 이 순번보다 작은 점만 저장소 → DB 방향으로 본다. null 이면 그 방향을 건너뛴다(재기동 뒤 첫 회차)
   */
  public Result reconcileCurrentTenant(Long fence) {
    long tenantId = TenantContext.require("벡터 대조 스윕");
    int removed = 0;
    if (fence != null) {
      for (EmbeddingDimension d : EmbeddingDimension.values()) {
        removed +=
            sweepStore(
                tenantId,
                fence,
                d.datasetCollection(),
                QdrantCollectionSpec.DATASET_ID,
                markers::findDatasetMarkers,
                d);
        removed +=
            sweepStore(
                tenantId,
                fence,
                d.chunkCollection(),
                QdrantCollectionSpec.CHUNK_ID,
                markers::findChunkMarkers,
                d);
      }
      removed +=
          sweepStore(
              tenantId,
              fence,
              QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION,
              QdrantCollectionSpec.DATASET_ID,
              markers::findDatasetMarkers,
              null);
      removed +=
          sweepStore(
              tenantId,
              fence,
              QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION,
              QdrantCollectionSpec.CHUNK_ID,
              markers::findChunkMarkers,
              null);
    }
    int cleared = sweepDatasetMarkers(tenantId) + sweepChunkMarkers(tenantId);
    return new Result(removed, cleared);
  }

  /**
   * 저장소 → DB 한 컬렉션. 울타리 안쪽 점을 훑어 표시가 가리키지 않는 점을 지운다.
   *
   * @param idField 점을 플랫폼 DB 행으로 되짚는 payload(데이터셋 id 또는 청크 id)
   * @param dense dense 컬렉션이면 그 차원(표시 차원·모델 일치를 본다), 키워드면 null(행만 있으면 남긴다)
   */
  private int sweepStore(
      long tenantId,
      long fence,
      String collection,
      String idField,
      Function<List<Long>, List<Marker>> lookup,
      EmbeddingDimension dense) {
    int removed = 0;
    PointId offset = null;
    do {
      ScrollPoints.Builder q =
          ScrollPoints.newBuilder()
              .setCollectionName(collection)
              .setFilter(allOf(tenant(tenantId), seqBefore(fence)))
              .setWithPayload(
                  WithPayloadSelectorFactory.include(List.of(idField, QdrantCollectionSpec.MODEL)))
              .setWithVectors(WithVectorsSelectorFactory.enable(false))
              .setLimit(PAGE);
      if (offset != null) q.setOffset(offset);
      ScrollResponse page =
          await(
              client.scrollAsync(q.build(), props.timeout()), props.timeout(), collection + " 훑기");
      List<Long> ids = new ArrayList<>();
      for (RetrievedPoint p : page.getResultList()) {
        Long id = longPayload(p, idField);
        if (id != null) ids.add(id);
      }
      Map<Long, Marker> byId =
          lookup.apply(ids).stream().collect(Collectors.toMap(Marker::id, m -> m, (a, b) -> a));
      List<PointId> orphans = new ArrayList<>();
      for (RetrievedPoint p : page.getResultList()) {
        Long id = longPayload(p, idField);
        Marker m = id == null ? null : byId.get(id);
        if (!keeps(m, p, dense)) orphans.add(p.getId());
      }
      if (!orphans.isEmpty()) {
        // 같은 울타리를 붙여 지운다 — 훑은 뒤 같은 id 로 다시 쓴 점(더 큰 순번)은 남는다.
        await(
            client.deleteAsync(
                collection,
                allOf(tenant(tenantId), seqBefore(fence), ConditionFactory.hasId(orphans)),
                props.timeout()),
            props.timeout(),
            collection + " 대조 삭제");
        removed += orphans.size();
      }
      offset = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
    } while (offset != null);
    return removed;
  }

  /** 표시가 이 점을 가리키는가. dense 는 차원·모델까지, 키워드는 행만 있으면 된다. */
  private static boolean keeps(Marker m, RetrievedPoint p, EmbeddingDimension dense) {
    if (m == null) return false;
    if (dense == null) return true;
    Value model = p.getPayloadMap().get(QdrantCollectionSpec.MODEL);
    return m.dim() != null
        && m.dim() == dense.size()
        && model != null
        && Objects.equals(m.model(), model.getStringValue());
  }

  /** DB → 저장소, 데이터셋. */
  private int sweepDatasetMarkers(long tenantId) {
    return sweepMarkers(
        markers::pageMarkedDatasets,
        id -> PointIdFactory.id(QdrantPointIds.dataset(tenantId, id)),
        EmbeddingDimension::datasetCollection,
        QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION,
        (m) -> markers.clearDatasetDense(m.id(), m.dim(), m.model()),
        (m) -> markers.clearDatasetKeyword(m.id()),
        (m) ->
            hasTerms(markers.findDatasetSourceText(m.id()), BigramSparseEncoder.Profile.DATASET));
  }

  /** DB → 저장소, 청크. */
  private int sweepChunkMarkers(long tenantId) {
    return sweepMarkers(
        markers::pageMarkedChunks,
        id -> PointIdFactory.id(QdrantPointIds.chunk(tenantId, id)),
        EmbeddingDimension::chunkCollection,
        QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION,
        (m) -> markers.clearChunkDense(m.id(), m.dim(), m.model()),
        (m) -> markers.clearChunkKeyword(m.id()),
        (m) -> hasTerms(markers.findChunkContent(m.id()), BigramSparseEncoder.Profile.CHUNK));
  }

  /** 표시 행을 키셋으로 훑어 점이 없거나 모델이 다른 표시를 비운다. */
  private int sweepMarkers(
      PageReader pages,
      LongFunction<PointId> pointId,
      Function<EmbeddingDimension, String> denseCollection,
      String keywordCollection,
      Function<Marker, Integer> clearDense,
      Function<Marker, Integer> clearKeyword,
      java.util.function.Predicate<Marker> keywordExpected) {
    int cleared = 0;
    long after = 0;
    while (true) {
      List<Marker> page = pages.read(after, PAGE);
      if (page.isEmpty()) return cleared;
      after = page.get(page.size() - 1).id();

      // dense — 차원별로 모아 한 번에 가져온다. 지원하지 않는 차원 표시는 가리킬 컬렉션이 없으니 비운다.
      Map<EmbeddingDimension, List<Marker>> byDim = new HashMap<>();
      for (Marker m : page) {
        if (m.dim() == null) continue;
        EmbeddingDimension d = dimensionOrNull(m.dim());
        if (d == null) {
          cleared += clearDense.apply(m);
        } else {
          byDim.computeIfAbsent(d, k -> new ArrayList<>()).add(m);
        }
      }
      for (Map.Entry<EmbeddingDimension, List<Marker>> e : byDim.entrySet()) {
        Map<String, RetrievedPoint> found =
            retrieve(denseCollection.apply(e.getKey()), e.getValue(), pointId, true);
        for (Marker m : e.getValue()) {
          RetrievedPoint p = found.get(uuid(pointId.apply(m.id())));
          Value model = p == null ? null : p.getPayloadMap().get(QdrantCollectionSpec.MODEL);
          if (model == null || !Objects.equals(m.model(), model.getStringValue())) {
            cleared += clearDense.apply(m);
          }
        }
      }

      // 키워드.
      List<Marker> kw = page.stream().filter(Marker::keyword).toList();
      if (!kw.isEmpty()) {
        Map<String, RetrievedPoint> found = retrieve(keywordCollection, kw, pointId, false);
        for (Marker m : kw) {
          // 낱말이 없는 원문은 키워드 쓰기가 점을 지우고 표시만 채운다 — 점이 없는 게 정상이라 비우지 않는다(비우면 키워드 백로그가 다시 채우고
          // 이 스윕이 또 비우기를 끝없이 되풀이한다).
          if (!found.containsKey(uuid(pointId.apply(m.id()))) && keywordExpected.test(m)) {
            cleared += clearKeyword.apply(m);
          }
        }
      }
      if (page.size() < PAGE) return cleared;
    }
  }

  /** 점 id 로 가져온다(uuid 문자열 → 점). */
  private Map<String, RetrievedPoint> retrieve(
      String collection, List<Marker> rows, LongFunction<PointId> pointId, boolean withModel) {
    List<PointId> ids = rows.stream().map(m -> pointId.apply(m.id())).toList();
    List<RetrievedPoint> points =
        await(
            client.retrieveAsync(
                GetPoints.newBuilder()
                    .setCollectionName(collection)
                    .addAllIds(ids)
                    .setWithPayload(
                        withModel
                            ? WithPayloadSelectorFactory.include(
                                List.of(QdrantCollectionSpec.MODEL))
                            : WithPayloadSelectorFactory.enable(false))
                    .setWithVectors(WithVectorsSelectorFactory.enable(false))
                    .build(),
                props.timeout()),
            props.timeout(),
            collection + " 대조 조회");
    Map<String, RetrievedPoint> out = new HashMap<>();
    for (RetrievedPoint p : points) out.put(uuid(p.getId()), p);
    return out;
  }

  /** 원문에서 키워드 낱말이 하나라도 나오는가(키워드 쓰기와 같은 규칙). 원문이 없으면 거짓. */
  private static boolean hasTerms(String text, BigramSparseEncoder.Profile profile) {
    return text != null && BigramSparseEncoder.document(text, profile).indices().length > 0;
  }

  private static String uuid(PointId id) {
    return id.getUuid();
  }

  private static Long longPayload(RetrievedPoint p, String field) {
    Value v = p.getPayloadMap().get(field);
    return v == null || !v.hasIntegerValue() ? null : v.getIntegerValue();
  }

  private static EmbeddingDimension dimensionOrNull(int size) {
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (d.size() == size) return d;
    }
    return null;
  }

  /** 표시 행 키셋 페이지 읽기. */
  @FunctionalInterface
  private interface PageReader {
    List<Marker> read(long afterId, int limit);
  }
}
