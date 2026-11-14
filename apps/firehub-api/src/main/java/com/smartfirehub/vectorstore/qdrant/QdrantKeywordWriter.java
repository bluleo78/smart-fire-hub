package com.smartfirehub.vectorstore.qdrant;

import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.await;
import static com.smartfirehub.vectorstore.qdrant.QdrantSupport.tenantKey;
import static io.qdrant.client.ValueFactory.value;

import com.smartfirehub.vectorstore.KeywordSource;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.Common.PointId;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 키워드 컬렉션 쓰기(WD-720) — 데이터셋·청크 색인 구현이 함께 쓴다. 원문을 음절 바이그램 sparse 로 바꿔 덮어쓴다(ADR 0002 결정 8).
 *
 * <p>원문에 낱말이 하나도 없으면(빈 문자열·기호만) sparse 가 비어 Qdrant 가 쓰기를 거절할 수 있고, 옛 키워드가 남으면 안 되므로 그 점을 지운다. 쓰기는
 * 커밋 뒤에 불리므로(키워드 쓰기 경로) 삭제도 바로 한다.
 */
final class QdrantKeywordWriter {

  private QdrantKeywordWriter() {}

  static void write(
      QdrantClient client,
      QdrantProperties props,
      String collection,
      long tenantId,
      List<KeywordSource> sources,
      BigramSparseEncoder.Profile profile,
      Function<KeywordSource, PointId> pointId,
      Function<KeywordSource, Map<String, Value>> extraPayload) {
    if (sources.isEmpty()) return;
    List<PointStruct> points = new ArrayList<>();
    List<PointId> empty = new ArrayList<>();
    for (KeywordSource s : sources) {
      BigramSparseEncoder.SparseTerms terms = BigramSparseEncoder.document(s.text(), profile);
      if (terms.indices().length == 0) {
        empty.add(pointId.apply(s));
        continue;
      }
      Map<String, Value> payload = new HashMap<>(extraPayload.apply(s));
      payload.put(QdrantCollectionSpec.TENANT_ID, value(tenantKey(tenantId)));
      points.add(
          PointStruct.newBuilder()
              .setId(pointId.apply(s))
              .setVectors(
                  VectorsFactory.namedVectors(
                      Map.of(
                          QdrantCollectionSpec.KW_BIGRAM,
                          VectorFactory.vector(terms.valueList(), terms.indexList()))))
              .putAllPayload(payload)
              .build());
    }
    if (!points.isEmpty()) {
      await(client.upsertAsync(collection, points, props.timeout()), props.timeout(), "키워드 쓰기");
    }
    if (!empty.isEmpty()) {
      await(client.deleteAsync(collection, empty, props.timeout()), props.timeout(), "빈 키워드 지우기");
    }
  }
}
