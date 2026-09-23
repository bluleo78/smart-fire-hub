package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 임베딩 호출 크기 상한 회귀: 배치(최대 200행 × 8000자)를 한 번에 임베딩하면 임베딩 서버 타임아웃(Ollama 120초)에 걸려
 * 같은 구간을 영원히 재시도한다. 배치 크기를 32 보다 크게 잡고 호출마다 텍스트 수를 기록해 상한을 단언한다.
 *
 * <p>통합 테스트(RowSearchSyncServiceTest)는 batch-size=2 라 32 를 넘을 수 없어 이 경계를 증명하지 못한다 — 그래서
 * 협력자를 mock 으로 둔 단위 테스트로 따로 둔다.
 */
@ExtendWith(MockitoExtension.class)
class RowSearchSyncServiceEmbedChunkTest {

  private static final long ID = 7L;
  private static final int ROWS = 70;

  @Mock private RowSearchIndex index;
  @Mock private SearchIndexStateRepository states;
  @Mock private SearchColumnRepository searchColumns;
  @Mock private SearchSourceReader reader;
  @Mock private DatasetRepository datasetRepository;
  @Mock private EmbeddingProviderFactory embeddingFactory;
  @Mock private IncrementalCursorService cursorService;

  private final List<Integer> callSizes = new ArrayList<>();
  private final List<IndexedRow> upserted = new ArrayList<>();

  @BeforeEach
  void setUp() {
    TenantContext.set(1L);
  }

  @AfterEach
  void tearDown() {
    TenantContext.clear();
  }

  @Test
  void largeBatch_isEmbeddedInChunksOfAtMost32() {
    SearchConfig config = new SearchConfig(List.of(new SearchConfig.Field("content", "내용")));
    // 설정·모델·차원·OID 가 모두 같아 재색인 없이 증분 패스만 돈다(패스 커서도 이미 잡혀 있음).
    SearchIndexState state =
        new SearchIndexState(
            ID, "IDLE", config.configHash(), "m", 1024, 1L, null, OffsetDateTime.now(), null, 0, 0, 0, null,
            null);
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(true);
    when(states.find(ID)).thenReturn(Optional.of(state));
    when(datasetRepository.findById(ID))
        .thenReturn(
            Optional.of(
                new DatasetResponse(
                    ID, "d", "src", null, null, "TABLE", "SOURCE", null, false, List.of(), null, null,
                    null, null, null)));
    when(searchColumns.findConfig(ID)).thenReturn(config);
    when(embeddingFactory.current())
        .thenReturn(
            new EmbeddingProvider() {
              public List<float[]> embed(List<String> texts) {
                callSizes.add(texts.size());
                return texts.stream().map(t -> new float[1024]).toList();
              }

              public String modelId() {
                return "m";
              }

              public int dimension() {
                return 1024;
              }
            });
    when(reader.currentOid("src")).thenReturn(1L);
    List<SearchSourceReader.SourceRow> rows =
        LongStream.rangeClosed(1, ROWS)
            .mapToObj(i -> new SearchSourceReader.SourceRow(i, Map.<String, Object>of("content", "행 " + i)))
            .toList();
    when(reader.fetchChanged(eq("src"), any(), any(), eq(0L), anyInt())).thenReturn(rows);
    when(index.existingHashes(any(), any())).thenReturn(Map.of());
    lenient()
        .doAnswer(
            inv -> {
              upserted.addAll(inv.getArgument(1));
              return null;
            })
        .when(index)
        .upsert(any(), any());
    lenient().when(index.upsertReusing(any(), anyLong(), anyString(), anyString(), anyString())).thenReturn(false);

    RowSearchSyncService sync =
        new RowSearchSyncService(
            index, states, searchColumns, reader, datasetRepository, embeddingFactory, cursorService, 5000, 100);

    assertThat(sync.sync(ID)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    assertThat(callSizes).isNotEmpty().allMatch(n -> n <= 32);
    assertThat(callSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(ROWS);
    // 청크로 나눠도 행 id 와 벡터 짝이 어긋나지 않아야 한다.
    assertThat(upserted).extracting(IndexedRow::rowId).containsExactlyElementsOf(
        LongStream.rangeClosed(1, ROWS).boxed().toList());
  }
}
