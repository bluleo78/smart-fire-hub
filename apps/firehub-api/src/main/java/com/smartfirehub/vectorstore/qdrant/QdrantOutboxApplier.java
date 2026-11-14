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

  public QdrantOutboxApplier(QdrantClient client, QdrantProperties props) {
    this.client = client;
    this.props = props;
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
