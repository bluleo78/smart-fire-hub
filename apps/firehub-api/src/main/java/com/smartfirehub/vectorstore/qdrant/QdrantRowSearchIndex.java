package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.allOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.anyOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenant;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenantKey;
import static io.qdrant.client.ValueFactory.value;

import com.smartfirehub.dataset.rowsearch.IndexRef;
import com.smartfirehub.dataset.rowsearch.IndexedRow;
import com.smartfirehub.dataset.rowsearch.ReuseCandidate;
import com.smartfirehub.dataset.rowsearch.RowHit;
import com.smartfirehub.dataset.rowsearch.RowQueryFilter;
import com.smartfirehub.dataset.rowsearch.RowSearchIndex;
import com.smartfirehub.dataset.rowsearch.SearchSourceReader;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorOutboxKind;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QueryFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.WithVectorsSelectorFactory;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.Common.Range;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.QuantizationSearchParams;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.RetrievedPoint;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import io.qdrant.client.grpc.Points.ScrollResponse;
import io.qdrant.client.grpc.Points.SearchParams;
import io.qdrant.client.grpc.Points.VectorOutput;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@link RowSearchIndex} 의 Qdrant 구현(ADR 0002 결정 2·9, WD-722). vector-store=qdrant 일 때만 뜬다.
 *
 * <p><b>테넌트별 컬렉션</b>(2026-11-14 사용자 결정 ③ — 원본 행이 RDB 에서 테넌트 스키마이므로 같은 수준). dense 는 {@code
 * fh_rows_t{테넌트}_{차원}}, 키워드는 {@code fh_rows_t{테넌트}_kw}. 읽기·쓰기는 그 테넌트 컬렉션만 허용한 토큰을 단 클라이언트({@link
 * QdrantRowClients})로 해서, 앱 필터가 틀려도 다른 테넌트 컬렉션은 Qdrant 가 거부한다. 그래도 모든 질의에 {@code tenant_id} 필터를 함께
 * 붙인다.
 *
 * <p><b>점:</b> 색인된 모든 행은 키워드 컬렉션에 점이 있다(낱말이 없는 원문은 벡터 없는 점) — 원문 해시 조회({@link #existingHashes})와 행
 * 수의 기준이다. 의미 벡터가 있으면 dense 컬렉션에도 점을 둔다. 쓰기는 dense 를 먼저, 키워드를 나중에 한다: 키워드 점(해시)이 있으면 「이미 색인됨」으로
 * 보므로, dense 쓰기가 실패했는데 해시만 남으면 그 행의 벡터가 영영 빠진다. 원문 텍스트는 싣지 않는다(결정 5).
 *
 * <p><b>세대:</b> 원본 교체(swap) 뒤에는 row_id 가 새로 매겨지므로 점 payload 에 세대를 싣고 검색·해시 조회는 지금 세대({@link
 * IndexRef#generation()})로만 거른다. 이전 세대 점은 같은 원문(source_hash)의 벡터를 읽어 다시 쓰는 재사용에만 쓰고 완주하면 지운다.
 *
 * <p><b>필터 2단계(결정 9):</b> 구조화 필터는 원본 열이라 Qdrant 가 평가할 수 없다. 데이터 저장소에서 필터를 만족하는 row_id 를 먼저 구해
 * {@value #FILTER_ID_CAP} 개 이하면 id 필터로 넘기고, 넘으면 후보를 넉넉히 받아 데이터 저장소에서 사후 확인한다. 열 값을 payload 로 복제하지
 * 않는다 (원문 미보관).
 *
 * <p><b>삭제:</b> 데이터셋 전체 삭제·벡터 비우기는 아웃박스에 쌓는다(호출 트랜잭션과 함께 커밋, 저장소가 내려가도 실패하지 않음). 동기화 중 행 단위 삭제·세대
 * 정리·재색인 비우기는 바로 한다 — 실패하면 동기화가 백오프로 다시 한다. 점마다 순번({@code seq})을 실어 늦게 도착한 아웃박스 삭제가 새 점을 지우지 않게
 * 한다(WD-721 과 같은 규칙).
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantRowSearchIndex implements RowSearchIndex {

  /** 필터 1단계에서 id 필터로 넘길 최대 행 수(결정 9). 넘으면 사후 확인으로 간다. */
  static final int FILTER_ID_CAP = 10_000;

  /** 사후 확인 경로에서 limit 의 몇 배를 받는가. 필터 선택도가 낮을수록 모자랄 수 있어 넉넉히 받는다. */
  static final int POST_CHECK_FACTOR = 10;

  /** 사후 확인 경로의 최소 후보 수. */
  static final int POST_CHECK_MIN = 200;

  /** 원본 대조 스크롤 한 번에 읽는 점 수. */
  static final int SCROLL_PAGE = 1_000;

  /** 테넌트 컬렉션 목록을 기억하는 시간 — 목록은 전역 권한 호출이라 행마다 부르지 않는다. */
  static final long COLLECTIONS_CACHE_MS = 30_000;

  /** 공유 Qdrant 대상(전용 설정이 없는 테넌트). */
  private final QdrantRowTargets.RowTarget sharedTarget;

  /** 테넌트별 대상 해석(WD-742). 없으면(시험) 모든 테넌트가 공유 대상이다. */
  private final QdrantRowTargets targets;

  private final QdrantProperties props;
  private final VectorOutboxRepository outbox;
  private final SearchSourceReader reader;
  private final long exactScanMaxRows;
  private final long orphanSweepIntervalMs;
  private final LongSupplier clock;
  private final int filterIdCap;

  private record CachedCollections(List<String> names, long at) {}

  private final Map<Long, CachedCollections> collections = new ConcurrentHashMap<>();
  private final Map<String, Long> lastOrphanSweep = new ConcurrentHashMap<>();

  @Autowired
  public QdrantRowSearchIndex(
      QdrantRowClients rowClients,
      QdrantVectorStoreLifecycle lifecycle,
      QdrantRowTargets targets,
      QdrantProperties props,
      VectorOutboxRepository outbox,
      SearchSourceReader reader,
      @org.springframework.beans.factory.annotation.Value(
              "${row-search.search.exact-scan-max-rows:100000}")
          long exactScanMaxRows,
      @org.springframework.beans.factory.annotation.Value(
              "${firehub.vector-store.qdrant.row-orphan-sweep-interval-ms:1800000}")
          long orphanSweepIntervalMs) {
    this(
        rowClients,
        lifecycle,
        targets,
        props,
        outbox,
        reader,
        exactScanMaxRows,
        orphanSweepIntervalMs,
        System::currentTimeMillis,
        FILTER_ID_CAP);
  }

  /** 시험용 — 전용 대상 해석 없이 모든 테넌트를 공유 Qdrant 로 본다. */
  QdrantRowSearchIndex(
      QdrantRowClients rowClients,
      QdrantVectorStoreLifecycle lifecycle,
      QdrantProperties props,
      VectorOutboxRepository outbox,
      SearchSourceReader reader,
      long exactScanMaxRows,
      long orphanSweepIntervalMs,
      LongSupplier clock,
      int filterIdCap) {
    this(
        rowClients,
        lifecycle,
        null,
        props,
        outbox,
        reader,
        exactScanMaxRows,
        orphanSweepIntervalMs,
        clock,
        filterIdCap);
  }

  QdrantRowSearchIndex(
      QdrantRowClients rowClients,
      QdrantVectorStoreLifecycle lifecycle,
      QdrantRowTargets targets,
      QdrantProperties props,
      VectorOutboxRepository outbox,
      SearchSourceReader reader,
      long exactScanMaxRows,
      long orphanSweepIntervalMs,
      LongSupplier clock,
      int filterIdCap) {
    this.sharedTarget = QdrantRowTargets.shared(lifecycle, rowClients);
    this.targets = targets;
    this.props = props;
    this.outbox = outbox;
    this.reader = reader;
    this.exactScanMaxRows = exactScanMaxRows;
    this.orphanSweepIntervalMs = orphanSweepIntervalMs;
    this.clock = clock;
    this.filterIdCap = filterIdCap;
  }

  // --- 생명주기 ---

  /** 모든 세대의 점을 바로 지우고 새 차원 컬렉션을 준비한다(설정·모델·차원 변경 — 동기화가 부른다). */
  @Override
  public void recreate(IndexRef ref, int dim) {
    requireCurrentTenant(ref);
    deleteEverywhere(ref.tenantId(), datasetFilter(ref));
    prepareCollections(ref.tenantId(), dim);
  }

  /** 세대는 점 payload 라 컬렉션만 준비한다. */
  @Override
  public void startGeneration(IndexRef ref, int dim) {
    requireCurrentTenant(ref);
    prepareCollections(ref.tenantId(), dim);
  }

  @Override
  public boolean hasPreviousGeneration(IndexRef ref) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    String kw = QdrantCollectionSpec.rowsKeywordName(ref.tenantId());
    if (!tenantCollections(ref.tenantId()).contains(kw)) return false;
    Filter f = allOf(baseConditions(ref), generationBelow(gen));
    return await(
            client(ref.tenantId()).countAsync(kw, f, true, props.timeout()),
            props.timeout(),
            "행 이전 세대 확인")
        > 0;
  }

  @Override
  public void dropPreviousGenerations(IndexRef ref) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    deleteEverywhere(ref.tenantId(), allOf(baseConditions(ref), generationBelow(gen)));
  }

  /** 데이터셋 삭제·검색 끄기 — 아웃박스(호출 트랜잭션과 함께 커밋). 모든 세대·모든 차원이 대상이다. */
  @Override
  public void drop(IndexRef ref) {
    requireCurrentTenant(ref);
    outbox.enqueue(VectorOutboxKind.ROWS_ALL_OF_DATASET, ref.datasetId(), null);
  }

  // --- 쓰기 ---

  @Override
  public Map<Long, String> existingHashes(IndexRef ref, Collection<Long> rowIds) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    Map<Long, String> result = new HashMap<>();
    String kw = QdrantCollectionSpec.rowsKeywordName(ref.tenantId());
    if (rowIds.isEmpty() || !tenantCollections(ref.tenantId()).contains(kw)) return result;
    List<PointId> ids = rowIds.stream().map(id -> pointId(ref, id, gen)).toList();
    List<RetrievedPoint> points =
        await(
            client(ref.tenantId())
                .retrieveAsync(
                    kw,
                    ids,
                    WithPayloadSelectorFactory.include(
                        List.of(QdrantCollectionSpec.ROW_ID, QdrantCollectionSpec.SOURCE_HASH)),
                    WithVectorsSelectorFactory.enable(false),
                    null,
                    props.timeout()),
            props.timeout(),
            "행 원문 해시 조회");
    for (RetrievedPoint p : points) {
      Value rowId = p.getPayloadMap().get(QdrantCollectionSpec.ROW_ID);
      Value hash = p.getPayloadMap().get(QdrantCollectionSpec.SOURCE_HASH);
      if (rowId != null && hash != null) result.put(rowId.getIntegerValue(), hash.getStringValue());
    }
    return result;
  }

  /** 재사용 조회 한 쪽(scroll 페이지)의 점 수 — 같은 원문이 여러 행·여러 세대에 있으면 해시 하나에 점이 여럿이라 페이지로 나눠 읽는다. */
  static final int REUSE_SCROLL_PAGE = 256;

  /**
   * 이전 세대에서 같은 원문·같은 모델의 dense 벡터를 읽어 지금 세대로 다시 쓴다(서버 쪽 복사가 없다). 키워드 점은 원문으로 새로 만든다.
   *
   * <p>배치째 처리한다(WD-743): dense 컬렉션마다 배치의 해시 전부를 {@code source_hash in (…)} 한 조건으로 scroll 해 해시별 첫
   * 벡터를 모으고, 찾은 행을 {@link #upsert} 한 번(dense·키워드 쓰기 각 한 번)으로 넣는다. 해시를 다 찾으면 남은 페이지·컬렉션은 읽지 않는다.
   */
  @Override
  public Set<Long> upsertReusing(IndexRef ref, List<ReuseCandidate> rows, String model) {
    requireCurrentTenant(ref);
    return reuseVectors(ref, rows, model, generationBelow(ref.requireGeneration()));
  }

  /**
   * 지금 세대의 다른 행에서 같은 원문의 벡터를 찾는다(WD-745 — Tibero 교체 적재). 같은 세대라도 후보 행 자신의 점은 아직 없거나(새 ID) 해시가 달라
   * 걸리지 않는다.
   */
  @Override
  public Set<Long> upsertReusingSameGeneration(
      IndexRef ref, List<ReuseCandidate> rows, String model) {
    requireCurrentTenant(ref);
    return reuseVectors(ref, rows, model, generationIs(ref.requireGeneration()));
  }

  /**
   * 재사용 공통 — {@code generation} 조건(이전 세대 또는 지금 세대)에 맞는 점에서 해시별 첫 dense 벡터를 모아 지금 세대로 쓴다. 쓰기는 늘 지금
   * 세대({@link #upsert})다.
   */
  private Set<Long> reuseVectors(
      IndexRef ref, List<ReuseCandidate> rows, String model, Condition generation) {
    if (rows.isEmpty()) return Set.of();
    Set<String> wanted = new HashSet<>();
    for (ReuseCandidate r : rows) wanted.add(r.sourceHash());
    Map<String, float[]> found = new HashMap<>();
    for (String c : denseCollections(ref.tenantId())) {
      if (found.size() == wanted.size()) break;
      List<String> missing = wanted.stream().filter(h -> !found.containsKey(h)).toList();
      PointId offset = null;
      do {
        ScrollPoints.Builder req =
            ScrollPoints.newBuilder()
                .setCollectionName(c)
                .setFilter(
                    allOf(
                        baseConditions(ref),
                        generation,
                        ConditionFactory.matchKeywords(QdrantCollectionSpec.SOURCE_HASH, missing),
                        ConditionFactory.matchKeyword(QdrantCollectionSpec.MODEL, model)))
                .setLimit(REUSE_SCROLL_PAGE)
                .setWithVectors(WithVectorsSelectorFactory.enable(true))
                .setWithPayload(
                    WithPayloadSelectorFactory.include(List.of(QdrantCollectionSpec.SOURCE_HASH)));
        if (offset != null) req.setOffset(offset);
        ScrollResponse page =
            await(
                client(ref.tenantId()).scrollAsync(req.build(), props.timeout()),
                props.timeout(),
                "행 벡터 재사용 조회");
        for (RetrievedPoint p : page.getResultList()) {
          Value hash = p.getPayloadMap().get(QdrantCollectionSpec.SOURCE_HASH);
          if (hash == null || found.containsKey(hash.getStringValue())) continue;
          float[] vec = denseOf(p);
          if (vec != null) found.put(hash.getStringValue(), vec);
        }
        offset = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
      } while (offset != null && found.size() < wanted.size());
    }
    if (found.isEmpty()) return Set.of();
    List<IndexedRow> copies = new ArrayList<>();
    Set<Long> reused = new HashSet<>();
    for (ReuseCandidate r : rows) {
      float[] vec = found.get(r.sourceHash());
      if (vec == null) continue;
      copies.add(new IndexedRow(r.rowId(), r.sourceText(), r.sourceHash(), vec, model));
      reused.add(r.rowId());
    }
    upsert(ref, copies);
    return reused;
  }

  @Override
  public void upsert(IndexRef ref, List<IndexedRow> rows) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    if (rows.isEmpty()) return;
    long tenantId = ref.tenantId();
    long seq = outbox.nextSeq();
    Map<Integer, List<PointStruct>> denseByDim = new HashMap<>();
    List<PointStruct> keyword = new ArrayList<>(rows.size());
    for (IndexedRow r : rows) {
      Map<String, Value> payload = rowPayload(ref, r.rowId(), r.sourceHash(), gen, seq);
      if (r.embedding() != null) {
        Map<String, Value> densePayload = new HashMap<>(payload);
        densePayload.put(QdrantCollectionSpec.MODEL, value(r.embeddingModel()));
        denseByDim
            .computeIfAbsent(r.embedding().length, d -> new ArrayList<>())
            .add(
                PointStruct.newBuilder()
                    .setId(pointId(ref, r.rowId(), gen))
                    .setVectors(
                        VectorsFactory.namedVectors(
                            Map.of(
                                QdrantCollectionSpec.DENSE, VectorFactory.vector(r.embedding()))))
                    .putAllPayload(densePayload)
                    .build());
      }
      BigramSparseEncoder.SparseTerms terms =
          BigramSparseEncoder.document(r.sourceText(), BigramSparseEncoder.Profile.ROW);
      PointStruct.Builder kp =
          PointStruct.newBuilder().setId(pointId(ref, r.rowId(), gen)).putAllPayload(payload);
      // 낱말이 없는 원문도 해시 조회를 위해 점은 둔다 — 벡터 없는 점(sparse 는 빠져도 된다).
      kp.setVectors(
          VectorsFactory.namedVectors(
              terms.indices().length == 0
                  ? Map.of()
                  : Map.of(
                      QdrantCollectionSpec.KW_BIGRAM,
                      VectorFactory.vector(terms.valueList(), terms.indexList()))));
      keyword.add(kp.build());
    }
    for (Integer dim : denseByDim.keySet()) prepareCollections(tenantId, dim);
    prepareCollections(tenantId, 1);
    QdrantClient c = client(tenantId);
    // dense 먼저 — 클래스 설명 「점」 참고.
    for (Map.Entry<Integer, List<PointStruct>> e : denseByDim.entrySet()) {
      await(
          c.upsertAsync(
              QdrantCollectionSpec.rowsDenseName(tenantId, e.getKey()),
              e.getValue(),
              props.timeout()),
          props.timeout(),
          "행 벡터 쓰기");
    }
    await(
        c.upsertAsync(QdrantCollectionSpec.rowsKeywordName(tenantId), keyword, props.timeout()),
        props.timeout(),
        "행 키워드 쓰기");
  }

  /** 모든 세대의 dense 를 비운다(키워드는 남김) — 아웃박스. 상태 행을 키워드 전용으로 바꾸는 정책 정리와 같은 트랜잭션에 쌓인다. */
  @Override
  public void clearEmbeddings(IndexRef ref) {
    requireCurrentTenant(ref);
    outbox.enqueue(VectorOutboxKind.ROWS_DENSE_OF_DATASET, ref.datasetId(), null);
  }

  @Override
  public void deleteRows(IndexRef ref, Collection<Long> rowIds) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    if (rowIds.isEmpty()) return;
    deleteEverywhere(
        ref.tenantId(),
        allOf(
            baseConditions(ref),
            generationIs(gen),
            anyOf(QdrantCollectionSpec.ROW_ID, List.copyOf(rowIds))));
  }

  /**
   * 원본에 없는 row_id 의 점을 지운다. 데이터셋 점을 전부 훑어 데이터 저장소와 대조하므로 비싸다 — 데이터셋마다 {@code
   * row-orphan-sweep-interval-ms} 에 한 번만 하고 그 사이 호출은 0 을 돌려준다. 그동안 남은 점은 검색 결과 원본 조회에서 빠지므로(없는 행)
   * 결과가 틀리지 않고 후보 자리만 차지한다.
   */
  @Override
  public int deleteMissing(IndexRef ref) {
    return sweepOrphans(ref, false);
  }

  /** 교체 적재 뒤 옛 ID 점이 한꺼번에 남았을 때 — 주기 제한을 건너뛰고 지금 원본과 대조한다(WD-745). */
  @Override
  public int deleteMissingNow(IndexRef ref) {
    return sweepOrphans(ref, true);
  }

  /** 원본 대조 정리. {@code force} 가 거짓이면 주기 제한({@code row-orphan-sweep-interval-ms})을 지킨다. */
  private int sweepOrphans(IndexRef ref, boolean force) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    String key = ref.tenantId() + ":" + ref.datasetId();
    long now = clock.getAsLong();
    Long last = lastOrphanSweep.get(key);
    if (!force && last != null && now - last < orphanSweepIntervalMs) return 0;
    String kw = QdrantCollectionSpec.rowsKeywordName(ref.tenantId());
    if (!tenantCollections(ref.tenantId()).contains(kw)) return 0;
    QdrantClient c = client(ref.tenantId());
    Filter current = allOf(baseConditions(ref), generationIs(gen));
    List<Long> missing = new ArrayList<>();
    PointId offset = null;
    do {
      ScrollPoints.Builder q =
          ScrollPoints.newBuilder()
              .setCollectionName(kw)
              .setFilter(current)
              .setLimit(SCROLL_PAGE)
              .setWithPayload(
                  WithPayloadSelectorFactory.include(List.of(QdrantCollectionSpec.ROW_ID)))
              .setWithVectors(WithVectorsSelectorFactory.enable(false));
      if (offset != null) q.setOffset(offset);
      ScrollResponse page =
          await(c.scrollAsync(q.build(), props.timeout()), props.timeout(), "행 원본 대조");
      List<Long> ids = new ArrayList<>();
      for (RetrievedPoint p : page.getResultList()) {
        Value v = p.getPayloadMap().get(QdrantCollectionSpec.ROW_ID);
        if (v != null) ids.add(v.getIntegerValue());
      }
      Set<Long> alive = reader.existingIds(ref.sourceTable(), ids);
      for (Long id : ids) if (!alive.contains(id)) missing.add(id);
      offset = page.hasNextPageOffset() ? page.getNextPageOffset() : null;
    } while (offset != null);
    for (int from = 0; from < missing.size(); from += FILTER_ID_CAP) {
      List<Long> part = missing.subList(from, Math.min(from + FILTER_ID_CAP, missing.size()));
      deleteEverywhere(
          ref.tenantId(),
          allOf(baseConditions(ref), generationIs(gen), anyOf(QdrantCollectionSpec.ROW_ID, part)));
    }
    lastOrphanSweep.put(key, now);
    return missing.size();
  }

  /** 지금 세대의 색인 행 수(키워드 점 수). */
  @Override
  public long count(IndexRef ref) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    String kw = QdrantCollectionSpec.rowsKeywordName(ref.tenantId());
    if (!tenantCollections(ref.tenantId()).contains(kw)) return 0;
    return await(
        client(ref.tenantId())
            .countAsync(kw, allOf(baseConditions(ref), generationIs(gen)), true, props.timeout()),
        props.timeout(),
        "행 색인 수");
  }

  // --- 검색 ---

  @Override
  public List<RowHit> semantic(IndexRef ref, float[] queryVec, RowQueryFilter filter, int limit) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    String collection = QdrantCollectionSpec.rowsDenseName(ref.tenantId(), queryVec.length);
    if (limit <= 0 || !tenantCollections(ref.tenantId()).contains(collection)) return List.of();
    return search(
        ref,
        gen,
        collection,
        filter,
        limit,
        (conditions, n, exact) ->
            QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setUsing(QdrantCollectionSpec.DENSE)
                .setQuery(QueryFactory.nearest(queryVec))
                .setFilter(allOf(conditions.toArray(Condition[]::new)))
                .setParams(
                    SearchParams.newBuilder()
                        .setExact(exact)
                        .setQuantization(QuantizationSearchParams.newBuilder().setRescore(true)))
                .setLimit(n));
  }

  @Override
  public List<RowHit> keyword(IndexRef ref, String query, RowQueryFilter filter, int limit) {
    requireCurrentTenant(ref);
    long gen = ref.requireGeneration();
    BigramSparseEncoder.SparseTerms terms = BigramSparseEncoder.query(query);
    String collection = QdrantCollectionSpec.rowsKeywordName(ref.tenantId());
    if (limit <= 0
        || terms.indices().length == 0
        || !tenantCollections(ref.tenantId()).contains(collection)) {
      return List.of();
    }
    return search(
        ref,
        gen,
        collection,
        filter,
        limit,
        (conditions, n, exact) ->
            QueryPoints.newBuilder()
                .setCollectionName(collection)
                .setUsing(QdrantCollectionSpec.KW_BIGRAM)
                .setQuery(QueryFactory.nearest(terms.valueList(), terms.indexList()))
                .setFilter(allOf(conditions.toArray(Condition[]::new)))
                .setLimit(n));
  }

  /** 질의 하나를 만든다(조건, 받을 수, 정확 검색 여부). */
  @FunctionalInterface
  private interface QueryBuilder {
    QueryPoints.Builder build(List<Condition> conditions, int limit, boolean exact);
  }

  /**
   * 필터 2단계를 포함한 검색 흐름. 필터가 없으면 바로, 있으면 데이터 저장소에서 row_id 를 먼저 구해 상한 이하면 id 필터, 넘으면 넉넉히 받아 사후 확인한다.
   */
  private List<RowHit> search(
      IndexRef ref,
      long gen,
      String collection,
      RowQueryFilter filter,
      int limit,
      QueryBuilder builder) {
    List<Condition> conditions = new ArrayList<>(List.of(baseConditions(ref), generationIs(gen)));
    if (filter.isEmpty()) {
      return run(ref, builder.build(conditions, limit, useExact(ref, collection, gen)));
    }
    // 필터 평가는 데이터 저장소가 한다 — 리더가 테넌트 백엔드(PG·Tibero)의 방언으로 컴파일한다(WD-723).
    List<Long> ids = reader.matchingIds(ref.sourceTable(), filter, filterIdCap + 1);
    if (ids.isEmpty()) return List.of();
    if (ids.size() <= filterIdCap) {
      conditions.add(anyOf(QdrantCollectionSpec.ROW_ID, ids));
      // 후보가 상한 이하라 정확 검색 비용이 작고 재현율이 보장된다.
      return run(ref, builder.build(conditions, limit, true));
    }
    int pool = Math.max(limit * POST_CHECK_FACTOR, POST_CHECK_MIN);
    List<RowHit> candidates =
        run(ref, builder.build(conditions, pool, useExact(ref, collection, gen)));
    Set<Long> pass =
        reader.filterIds(
            ref.sourceTable(), candidates.stream().map(RowHit::rowId).toList(), filter);
    return candidates.stream().filter(h -> pass.contains(h.rowId())).limit(limit).toList();
  }

  private List<RowHit> run(IndexRef ref, QueryPoints.Builder q) {
    q.setWithPayload(WithPayloadSelectorFactory.include(List.of(QdrantCollectionSpec.ROW_ID)));
    List<ScoredPoint> points =
        await(
            client(ref.tenantId()).queryAsync(q.build(), props.timeout()), props.timeout(), "행 검색");
    List<RowHit> hits = new ArrayList<>(points.size());
    for (ScoredPoint p : points) {
      Long rowId = QdrantSupport.longPayload(p, QdrantCollectionSpec.ROW_ID);
      if (rowId != null) hits.add(new RowHit(rowId, p.getScore()));
    }
    return hits;
  }

  /**
   * 정확 검색 여부 — 지금 세대 점 수가 기준 이하면 정확 검색(PG 색인과 같은 기준: 비슷한 문장이 몰린 작은 데이터셋에서 HNSW 가 1위를 놓친 실측). 수는
   * 추정치(정확 계산 안 함)로 본다.
   */
  private boolean useExact(IndexRef ref, String collection, long gen) {
    long n =
        await(
            client(ref.tenantId())
                .countAsync(
                    collection,
                    allOf(baseConditions(ref), generationIs(gen)),
                    false,
                    props.timeout()),
            props.timeout(),
            "행 색인 수 추정");
    return n <= exactScanMaxRows;
  }

  // --- 도우미 ---

  /** 테넌트·데이터셋 조건(모든 질의·삭제의 바탕). */
  private static Condition baseConditions(IndexRef ref) {
    return ConditionFactory.filter(
        allOf(
            tenant(ref.tenantId()),
            ConditionFactory.match(QdrantCollectionSpec.DATASET_ID, ref.datasetId())));
  }

  private static Filter datasetFilter(IndexRef ref) {
    return allOf(baseConditions(ref));
  }

  private static Condition generationIs(long gen) {
    return ConditionFactory.match(QdrantCollectionSpec.GENERATION, gen);
  }

  private static Condition generationBelow(long gen) {
    return ConditionFactory.range(
        QdrantCollectionSpec.GENERATION, Range.newBuilder().setLt(gen).build());
  }

  private static Map<String, Value> rowPayload(
      IndexRef ref, long rowId, String sourceHash, long gen, long seq) {
    Map<String, Value> p = new HashMap<>();
    p.put(QdrantCollectionSpec.TENANT_ID, value(tenantKey(ref.tenantId())));
    p.put(QdrantCollectionSpec.DATASET_ID, value(ref.datasetId()));
    p.put(QdrantCollectionSpec.ROW_ID, value(rowId));
    p.put(QdrantCollectionSpec.SOURCE_HASH, value(sourceHash));
    p.put(QdrantCollectionSpec.GENERATION, value(gen));
    p.put(QdrantCollectionSpec.SEQ, value(seq));
    return p;
  }

  private static PointId pointId(IndexRef ref, long rowId, long gen) {
    return PointIdFactory.id(QdrantPointIds.row(ref.tenantId(), ref.datasetId(), rowId, 0, gen));
  }

  /** 읽은 점의 dense 벡터. 없으면 null. */
  private static float[] denseOf(RetrievedPoint p) {
    VectorOutput v = p.getVectors().getVectors().getVectorsMap().get(QdrantCollectionSpec.DENSE);
    if (v == null) return null;
    List<Float> data = v.hasDense() ? v.getDense().getDataList() : v.getDataList();
    if (data.isEmpty()) return null;
    float[] out = new float[data.size()];
    for (int i = 0; i < out.length; i++) out[i] = data.get(i);
    return out;
  }

  /** 테넌트 컬렉션 전부(키워드 + 차원별 dense)에서 조건에 맞는 점을 지운다. */
  private void deleteEverywhere(long tenantId, Filter filter) {
    List<String> names = tenantCollections(tenantId);
    if (names.isEmpty()) return;
    QdrantClient c = client(tenantId);
    for (String n : names) {
      await(c.deleteAsync(n, filter, props.timeout()), props.timeout(), n + " 행 점 삭제");
    }
  }

  /** 컬렉션을 준비하고(마스터 키) 목록 캐시를 버린다 — 새 컬렉션이 생기면 토큰에 넣어야 한다. */
  private void prepareCollections(long tenantId, int dim) {
    List<String> before = tenantCollections(tenantId);
    String wanted =
        dim >= 2
            ? QdrantCollectionSpec.rowsDenseName(tenantId, dim)
            : QdrantCollectionSpec.rowsKeywordName(tenantId);
    if (before.contains(wanted)
        && before.contains(QdrantCollectionSpec.rowsKeywordName(tenantId))) {
      return;
    }
    target(tenantId).ensureRowCollections(tenantId, dim);
    collections.remove(tenantId);
  }

  /** 테넌트 행 컬렉션 이름(짧게 캐시). */
  private List<String> tenantCollections(long tenantId) {
    long now = clock.getAsLong();
    CachedCollections c = collections.get(tenantId);
    if (c != null && now - c.at() < COLLECTIONS_CACHE_MS) return c.names();
    List<String> names = target(tenantId).rowCollections(tenantId);
    collections.put(tenantId, new CachedCollections(names, now));
    return names;
  }

  private List<String> denseCollections(long tenantId) {
    String kw = QdrantCollectionSpec.rowsKeywordName(tenantId);
    return tenantCollections(tenantId).stream().filter(n -> !n.equals(kw)).toList();
  }

  /** 이 테넌트 컬렉션만 허용한 클라이언트. */
  private QdrantClient client(long tenantId) {
    List<String> names = tenantCollections(tenantId);
    if (names.isEmpty()) {
      names = List.of(QdrantCollectionSpec.rowsKeywordName(tenantId));
    }
    return target(tenantId).forCollections(names);
  }

  /** 이 테넌트 행 색인이 놓인 Qdrant(WD-742) — 전용 설정이 있으면 그 인스턴스, 없으면 공유. */
  private QdrantRowTargets.RowTarget target(long tenantId) {
    return targets == null ? sharedTarget : targets.forTenant(tenantId);
  }

  /**
   * 참조의 테넌트가 현재 테넌트 문맥과 같은지 — PG 색인과 같은 원칙. 배경 스윕에서 문맥이 어긋나면 남의 테넌트 컬렉션을 건드리게 되므로 막는다(문맥이 없어도 거부).
   */
  private static void requireCurrentTenant(IndexRef ref) {
    Long current = TenantContext.get();
    if (!Objects.equals(current, ref.tenantId())) {
      throw new IllegalStateException(
          "색인 참조의 테넌트(" + ref.tenantId() + ")와 현재 테넌트 컨텍스트(" + current + ")가 다릅니다");
    }
  }
}
