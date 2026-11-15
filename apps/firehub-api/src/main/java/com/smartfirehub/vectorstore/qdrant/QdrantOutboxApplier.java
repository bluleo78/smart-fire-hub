package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.allOf;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.seqBefore;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenant;

import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorOutboxRepository.Entry;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Condition;
import io.qdrant.client.grpc.Common.Filter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 행 하나를 Qdrant 삭제로 바꿔 보낸다(WD-721, ADR 0002 결정 6). 워커가 부른다.
 *
 * <p>모든 삭제에 테넌트 조건과 순번 가드({@link QdrantSupport#seqBefore})가 붙는다 — 행을 쌓은 뒤 다시 쓴 점(더 큰 순번)은 남는다. 삭제는
 * 멱등이라 같은 행을 두 번 반영해도(워커가 반영 뒤 행을 지우기 전에 죽음) 결과가 같다. 한 행이 여러 컬렉션을 건드리면 하나라도 실패할 때 예외를 던진다 — 행이 남아
 * 다음 폴링이 처음부터 다시 한다.
 */
@Component
@ConditionalOnQdrantVectorStore
public class QdrantOutboxApplier {

  private final QdrantClient client;
  private final QdrantProperties props;
  private final QdrantRowClients rowClients;

  /** 테넌트 전용 Qdrant 해석(WD-742). 없으면(시험) 모든 테넌트의 행이 공유 Qdrant 에 있다고 본다. */
  private final QdrantRowTargets targets;

  /** 카탈로그·청크 종류만 반영하는 생성자 — 시험용. 행 종류가 오면 거부한다. */
  public QdrantOutboxApplier(QdrantClient client, QdrantProperties props) {
    this(client, props, null, null);
  }

  /** 공유 Qdrant 만 쓰는 생성자 — 시험용. */
  public QdrantOutboxApplier(
      QdrantClient client, QdrantProperties props, QdrantRowClients rowClients) {
    this(client, props, rowClients, null);
  }

  @Autowired
  public QdrantOutboxApplier(
      QdrantClient client,
      QdrantProperties props,
      QdrantRowClients rowClients,
      QdrantRowTargets targets) {
    this.client = client;
    this.props = props;
    this.rowClients = rowClients;
    this.targets = targets;
  }

  /**
   * 행을 반영한다.
   *
   * @throws QdrantAccessException 저장소 호출이 실패하면
   */
  public void apply(Entry e) {
    List<Condition> must = new ArrayList<>();
    must.add(tenant(e.tenantId()));
    must.add(seqBefore(e.seq()));
    switch (e.kind()) {
      case DATASET_DENSE -> {
        target(must, QdrantCollectionSpec.DATASET_ID, e.targetId());
        delete(denseCollections(true, e.keepDim()), must);
      }
      case DATASET_ALL -> {
        target(must, QdrantCollectionSpec.DATASET_ID, required(e));
        List<String> all = denseCollections(true, null);
        all.add(QdrantCollectionSpec.DATASETS_KEYWORD_COLLECTION);
        delete(all, must);
      }
      case CHUNK_DENSE -> {
        target(must, QdrantCollectionSpec.DATASET_ID, e.targetId());
        delete(denseCollections(false, e.keepDim()), must);
      }
      case CHUNK_ALL_OF_DATASET -> {
        target(must, QdrantCollectionSpec.DATASET_ID, required(e));
        delete(allChunkCollections(), must);
      }
      case CHUNK_ALL_OF_DOCUMENT -> {
        target(must, QdrantCollectionSpec.DOCUMENT_ID, required(e));
        delete(allChunkCollections(), must);
      }
      case ROWS_ALL_OF_DATASET -> {
        target(must, QdrantCollectionSpec.DATASET_ID, required(e));
        deleteRows(e.tenantId(), true, must);
      }
      case ROWS_DENSE_OF_DATASET -> {
        target(must, QdrantCollectionSpec.DATASET_ID, required(e));
        deleteRows(e.tenantId(), false, must);
      }
    }
  }

  /**
   * 테넌트 행 컬렉션에서 지운다(WD-722). 차원을 바꾼 테넌트는 옛 차원 컬렉션도 남아 있으므로 목록(마스터 키)으로 그 테넌트 컬렉션을 모두 찾고, 삭제는 그
   * 컬렉션들만 허용한 토큰으로 한다. 세대 조건은 붙이지 않는다 — 모든 세대가 대상이다.
   *
   * @param withKeyword 키워드 컬렉션도 지울지(전체 삭제). 거짓이면 dense 만
   */
  private void deleteRows(long tenantId, boolean withKeyword, List<Condition> must) {
    // 테넌트 전용 Qdrant(WD-742)면 그 인스턴스에서 나열·삭제한다. 그 인스턴스가 내려가 있으면 여기서 던져 이 테넌트의 이번 회차만 멈춘다(워커 설명).
    QdrantRowTargets.RowTarget dedicated = targets == null ? null : targets.forTenant(tenantId);
    if (dedicated != null && dedicated.dedicated()) {
      String kw = QdrantCollectionSpec.rowsKeywordName(tenantId);
      List<String> names =
          dedicated.rowCollections(tenantId).stream()
              .filter(n -> withKeyword || !n.equals(kw))
              .toList();
      if (names.isEmpty()) return;
      QdrantClient c = dedicated.forCollections(names);
      Filter f = allOf(must.toArray(Condition[]::new));
      for (String name : names) {
        await(c.deleteAsync(name, f, props.timeout()), props.timeout(), name + " 행 아웃박스 삭제(전용)");
      }
      return;
    }
    if (rowClients == null || !rowClients.enabled()) {
      // API 키가 없으면 행 검색이 꺼져 있어 행 점이 생긴 적이 없다 — 지울 것이 없으므로 반영한 것으로 친다. 여기서 던지면 데이터셋 삭제마다 쌓이는
      // 행 아웃박스가 그 테넌트의 다른 삭제(카탈로그·청크)까지 영영 막는다.
      return;
    }
    String prefix = QdrantCollectionSpec.rowsTenantPrefix(tenantId);
    String keyword = QdrantCollectionSpec.rowsKeywordName(tenantId);
    List<String> targets =
        await(client.listCollectionsAsync(), props.timeout(), "행 컬렉션 목록").stream()
            .filter(n -> n.startsWith(prefix))
            .filter(n -> withKeyword || !n.equals(keyword))
            .sorted()
            .toList();
    if (targets.isEmpty()) return; // 아직 색인을 만든 적 없는 테넌트
    QdrantClient tenantClient = rowClients.forCollections(targets);
    Filter filter = allOf(must.toArray(Condition[]::new));
    for (String c : targets) {
      await(
          tenantClient.deleteAsync(c, filter, props.timeout()), props.timeout(), c + " 행 아웃박스 삭제");
    }
  }

  /** 대상 id 조건(null 이면 테넌트 전체라 붙이지 않는다). */
  private static void target(List<Condition> must, String field, Long id) {
    if (id != null) must.add(ConditionFactory.match(field, id));
  }

  /** 대상이 꼭 있어야 하는 종류 — 없으면 테넌트 전체를 지우게 되므로 거부한다. */
  private static Long required(Entry e) {
    if (e.targetId() == null) {
      throw new IllegalStateException("대상 없는 아웃박스 행: seq=" + e.seq() + ", kind=" + e.kind());
    }
    return e.targetId();
  }

  /** dense 컬렉션 — {@code keepDim} 차원은 뺀다. */
  private static List<String> denseCollections(boolean datasets, Integer keepDim) {
    List<String> out = new ArrayList<>();
    for (EmbeddingDimension d : EmbeddingDimension.values()) {
      if (keepDim != null && d.size() == keepDim) continue;
      out.add(datasets ? d.datasetCollection() : d.chunkCollection());
    }
    return out;
  }

  private static List<String> allChunkCollections() {
    List<String> all = denseCollections(false, null);
    all.add(QdrantCollectionSpec.CHUNKS_KEYWORD_COLLECTION);
    return all;
  }

  private void delete(List<String> collections, List<Condition> must) {
    Filter filter = allOf(must.toArray(Condition[]::new));
    for (String c : collections) {
      await(client.deleteAsync(c, filter, props.timeout()), props.timeout(), c + " 아웃박스 삭제");
    }
  }
}
