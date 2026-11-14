package com.smartfirehub.vectorstore;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.user.repository.TenantPairContractBase;
import com.smartfirehub.vectorstore.VectorMarkerRepository.Marker;
import com.smartfirehub.vectorstore.VectorOutboxRepository.Entry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 벡터 정합성 리포지토리 계약 테스트(WD-721, ADR 0002 결정 6) — {@link VectorOutboxRepository} 와 {@link
 * VectorMarkerRepository} 를 구현마다(jOOQ·MyBatis PG·MyBatis Tibero) 하위 클래스로 실행한다. 두 테넌트를 만들어 RLS 가 다른
 * 테넌트의 아웃박스 행·표시 열을 가리는지, 쓰는 문장이 남의 행을 건드리지 않는지 고정한다.
 */
public abstract class VectorConsistencyRepositoryContractTest extends TenantPairContractBase {

  protected abstract VectorOutboxRepository outbox();

  protected abstract VectorMarkerRepository markers();

  @Test
  @DisplayName("아웃박스 — 쌓은 테넌트에서만 보이고, 순번 순서로 읽고, 실패 기록과 지우기가 그 행만 바꾼다")
  void outboxIsTenantScopedAndOrdered() {
    long first = inA(() -> outbox().enqueue(VectorOutboxKind.DATASET_ALL, 11L, null));
    long second = inA(() -> outbox().enqueue(VectorOutboxKind.CHUNK_DENSE, null, 1024));
    long other = inB(() -> outbox().enqueue(VectorOutboxKind.DATASET_DENSE, 11L, null));
    assertThat(second).isGreaterThan(first);
    assertThat(inA(() -> outbox().nextSeq())).isGreaterThan(other);

    List<Entry> a = inA(() -> outbox().findPending(10));
    assertThat(a)
        .containsExactly(
            new Entry(first, tenantA, VectorOutboxKind.DATASET_ALL, 11L, null, 0),
            new Entry(second, tenantA, VectorOutboxKind.CHUNK_DENSE, null, 1024, 0));
    assertThat(inA(() -> outbox().findPending(1))).hasSize(1);
    assertThat(inB(() -> outbox().findPending(10)))
        .containsExactly(new Entry(other, tenantB, VectorOutboxKind.DATASET_DENSE, 11L, null, 0));

    // 다른 테넌트에서는 A 의 행을 지우거나 고치지 못한다.
    inB(
        () -> {
          outbox().markFailed(first, "x");
          outbox().delete(first);
          return null;
        });
    assertThat(inA(() -> outbox().findPending(10))).hasSize(2);

    runA(() -> outbox().markFailed(first, "e".repeat(800)));
    assertThat(inA(() -> outbox().findPending(10)).get(0).attempts()).isEqualTo(1);
    runA(() -> outbox().delete(first));
    assertThat(inA(() -> outbox().findPending(10))).extracting(Entry::seq).containsExactly(second);
  }

  /** 테넌트 A: 데이터셋 둘(하나는 1024 표시+키워드, 하나는 1536 표시), 청크 둘. 테넌트 B: 1024 표시 데이터셋 하나. */
  private record Seed(long d1, long d2, long c1, long c2, long other) {}

  private Seed seed() {
    long d1 = fx.createDataset(tenantA, prefix + " 하나");
    long d2 = fx.createDataset(tenantA, prefix + " 둘");
    long other = fx.createDataset(tenantB, prefix + " 남의 것");
    fx.seedEmbeddingRow(tenantA, d1, "하나");
    fx.seedEmbeddingRow(tenantA, d2, "둘");
    fx.seedEmbeddingRow(tenantB, other, "남");
    fx.setEmbeddingMarker(d1, 1024, "m");
    fx.setEmbeddingKeywordMarker(d1, "bigram-v1:a");
    fx.setEmbeddingMarker(d2, 1536, "m");
    fx.setEmbeddingMarker(other, 1024, "m");
    long file = fx.createDocumentFile(tenantA, d1, prefix + ".txt", "COMPLETED");
    long c1 = fx.insertChunk(tenantA, file, d1, 0, "청크 하나");
    long c2 = fx.insertChunk(tenantA, file, d1, 1, "청크 둘");
    fx.setChunkMarker(c1, 1024, "m");
    fx.setChunkKeywordMarker(c2, "bigram-v1:b");
    return new Seed(d1, d2, c1, c2, other);
  }

  @Test
  @DisplayName("표시 읽기 — 수·다른 차원·id 목록·키셋 페이지, 다른 테넌트 행은 RLS 가 가린다")
  void markersAreReadPerTenant() {
    Seed s = seed();

    assertThat(inA(() -> markers().countDatasetDense(null, null))).isEqualTo(2);
    assertThat(inA(() -> markers().countDatasetDense(null, 1536))).isEqualTo(1);
    assertThat(inA(() -> markers().countDatasetDense(List.of(s.d1(), s.other()), null)))
        .isEqualTo(1);
    assertThat(inA(() -> markers().countDatasetDense(List.of(), null))).isZero();
    assertThat(inA(() -> markers().countChunkDense(List.of(s.d1()), null))).isEqualTo(1);
    assertThat(inA(() -> markers().countChunkDense(null, 1024))).isZero();

    assertThat(
            inA(
                () ->
                    markers()
                        .findDatasetsMarkedOtherThan(List.of(s.d1(), s.d2(), s.other()), 1024)))
        .containsExactly(s.d2());
    assertThat(inA(() -> markers().findChunkDatasetsMarkedOtherThan(List.of(s.c1(), s.c2()), 1536)))
        .containsExactly(s.d1());
    assertThat(inA(() -> markers().findChunkDatasetsMarkedOtherThan(List.of(s.c1()), 1024)))
        .isEmpty();

    assertThat(inA(() -> markers().findDatasetMarkers(List.of(s.d1(), s.other()))))
        .containsExactly(new Marker(s.d1(), s.d1(), 1024, "m", true));
    assertThat(inA(() -> markers().findChunkMarkers(List.of(s.c1(), s.c2()))))
        .containsExactlyInAnyOrder(
            new Marker(s.c1(), s.d1(), 1024, "m", false),
            new Marker(s.c2(), s.d1(), null, null, true));

    assertThat(inA(() -> markers().pageMarkedDatasets(0, 1)))
        .extracting(Marker::id)
        .containsExactly(Math.min(s.d1(), s.d2()));
    assertThat(inA(() -> markers().pageMarkedDatasets(0, 10)))
        .extracting(Marker::id)
        .containsExactly(Math.min(s.d1(), s.d2()), Math.max(s.d1(), s.d2()));
    assertThat(inA(() -> markers().pageMarkedChunks(s.c1(), 10)))
        .extracting(Marker::id)
        .containsExactly(s.c2());
    assertThat(inB(() -> markers().pageMarkedDatasets(0, 10)))
        .extracting(Marker::id)
        .containsExactly(s.other());
  }

  @Test
  @DisplayName("키워드 대조용 원문 — 현재 테넌트 행만 읽고 없으면 null")
  void keywordTextLookup() {
    Seed s = seed();
    assertThat(inA(() -> markers().findDatasetSourceText(s.d1()))).isEqualTo("하나");
    assertThat(inA(() -> markers().findChunkContent(s.c2()))).isEqualTo("청크 둘");
    assertThat(inA(() -> markers().findDatasetSourceText(s.other()))).isNull();
    assertThat(inB(() -> markers().findChunkContent(s.c1()))).isNull();
  }

  @Test
  @DisplayName("표시 비우기 — 표시가 그대로일 때만 비우고, 다른 테넌트 행은 건드리지 않는다")
  void clearOnlyWhenUnchanged() {
    Seed s = seed();

    // 모델이 바뀐 표시는 그대로 둔다(그새 다시 쓴 표시).
    assertThat(inA(() -> markers().clearDatasetDense(s.d1(), 1024, "다른 모델"))).isZero();
    assertThat(inA(() -> markers().clearDatasetDense(s.d1(), 1024, "m"))).isEqualTo(1);
    assertThat(inA(() -> markers().clearDatasetKeyword(s.d1()))).isEqualTo(1);
    assertThat(inA(() -> markers().findDatasetMarkers(List.of(s.d1()))))
        .containsExactly(new Marker(s.d1(), s.d1(), null, null, false));

    assertThat(inA(() -> markers().clearChunkDense(s.c1(), 1536, "m"))).isZero();
    assertThat(inA(() -> markers().clearChunkDense(s.c1(), 1024, "m"))).isEqualTo(1);
    assertThat(inA(() -> markers().clearChunkKeyword(s.c2()))).isEqualTo(1);
    assertThat(inA(() -> markers().pageMarkedChunks(0, 10))).isEmpty();

    // A 문맥에서 B 의 행을 비우려 해도 0행이다.
    assertThat(inA(() -> markers().clearDatasetDense(s.other(), 1024, "m"))).isZero();
    assertThat(inB(() -> markers().findDatasetMarkers(List.of(s.other()))))
        .containsExactly(new Marker(s.other(), s.other(), 1024, "m", false));
  }
}
