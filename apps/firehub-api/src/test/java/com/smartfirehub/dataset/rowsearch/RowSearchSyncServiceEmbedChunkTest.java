package com.smartfirehub.dataset.rowsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.IncrementalCursorService;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
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
 * 임베딩 호출 크기 상한 회귀 + S3 키워드 전용 색인(등급이 임베딩 공급자를 불허하면 공급자 호출 없이 텍스트만 색인, 다시 허용되면 실제 모델로 재생성).
 *
 * <p>임베딩 호출 크기 상한 회귀: 배치(최대 200행 × 8000자)를 한 번에 임베딩하면 임베딩 서버 타임아웃(Ollama 120초)에 걸려 같은 구간을 영원히
 * 재시도한다. 배치 크기를 32 보다 크게 잡고 호출마다 텍스트 수를 기록해 상한을 단언한다.
 *
 * <p>통합 테스트(RowSearchSyncServiceTest)는 batch-size=2 라 32 를 넘을 수 없어 이 경계를 증명하지 못한다 — 그래서 협력자를 mock
 * 으로 둔 단위 테스트로 따로 둔다.
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
  @Mock private EmbeddingAiGate aiGate;

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

  /** 같은 설정 해시·OID 의 상태 행. model·dim 만 바꿔 키워드 전용/일반 색인 상태를 만든다. */
  private static SearchIndexState state(SearchConfig config, String model, int dim) {
    return new SearchIndexState(
        ID,
        "IDLE",
        config.configHash(),
        model,
        dim,
        1L,
        null,
        OffsetDateTime.now(),
        null,
        0,
        0,
        0,
        null,
        null);
  }

  /** 공통 협력자 준비: 상태·데이터셋·설정·원본 70행. 공급자 스텁은 테스트마다 다르므로 여기서 두지 않는다. */
  private SearchConfig prepare(String stateModel, int stateDim) {
    SearchConfig config = new SearchConfig(List.of(new SearchConfig.Field("content", "내용")));
    SearchIndexState state = state(config, stateModel, stateDim);
    when(states.tryAcquireLease(eq(ID), any(Duration.class))).thenReturn(true);
    // 재색인(resetForFullPass) 뒤 다시 읽을 때도 같은 상태를 돌려준다(패스 커서는 null 이라 startPassIfNeeded 를 부른다).
    when(states.find(ID)).thenReturn(Optional.of(state));
    when(datasetRepository.findById(ID))
        .thenReturn(
            Optional.of(
                new DatasetResponse(
                    ID, "d", "src", null, null, "TABLE", "SOURCE", null, false, List.of(), null,
                    null, null, null, null)));
    when(searchColumns.findConfig(ID)).thenReturn(config);
    when(reader.currentOid("src")).thenReturn(1L);
    List<SearchSourceReader.SourceRow> rows =
        LongStream.rangeClosed(1, ROWS)
            .mapToObj(
                i ->
                    new SearchSourceReader.SourceRow(
                        i, Map.<String, Object>of("content", "행 " + i)))
            .toList();
    // 배치 분할 테스트가 키셋을 지키는 스텁으로 덮어쓰므로 lenient.
    lenient().when(reader.fetchChanged(eq("src"), any(), any(), eq(0L), anyInt())).thenReturn(rows);
    // 첫 배치 전에 멈추는 시나리오(코드리뷰 A-3)는 배치를 읽지 않으므로 lenient.
    lenient().when(index.existingHashes(any(), any())).thenReturn(Map.of());
    // 비교 후 갱신(코드리뷰 A-3)은 기본적으로 성공 — 정리 개입 시나리오만 false 로 덮는다.
    lenient()
        .when(
            states.resetForFullPass(
                anyLong(), anyString(), anyString(), anyInt(), anyLong(), any()))
        .thenReturn(true);
    lenient()
        .doAnswer(
            inv -> {
              upserted.addAll(inv.getArgument(1));
              return null;
            })
        .when(index)
        .upsert(any(), any());
    lenient()
        .when(index.upsertReusing(any(), anyLong(), anyString(), anyString(), anyString()))
        .thenReturn(false);
    return config;
  }

  /** 호출마다 텍스트 수를 기록하는 1024차원 가짜 공급자(모델 "m"). */
  private EmbeddingProvider recordingProvider() {
    return new EmbeddingProvider() {
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
    };
  }

  private RowSearchSyncService service() {
    return service(100);
  }

  private RowSearchSyncService service(int batchSize) {
    return new RowSearchSyncService(
        index,
        states,
        searchColumns,
        reader,
        datasetRepository,
        embeddingFactory,
        cursorService,
        aiGate,
        5000,
        batchSize);
  }

  @Test
  void largeBatch_isEmbeddedInChunksOfAtMost32() {
    // 설정·모델·차원·OID 가 모두 같아 재색인 없이 증분 패스만 돈다(패스 커서도 이미 잡혀 있음).
    prepare("m", 1024);
    when(aiGate.datasetAllowed(ID)).thenReturn(true);
    when(embeddingFactory.current()).thenReturn(recordingProvider());

    assertThat(service().sync(ID)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    assertThat(callSizes).isNotEmpty().allMatch(n -> n <= 32);
    assertThat(callSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(ROWS);
    // 청크로 나눠도 행 id 와 벡터 짝이 어긋나지 않아야 한다.
    assertThat(upserted)
        .extracting(IndexedRow::rowId)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, ROWS).boxed().toList());
  }

  @Test
  void disallowed_indexesTextOnly_withoutCallingProvider() {
    // S3 §4.3: 민감 등급 + 외부 임베딩 — 상태는 실제 모델로 색인돼 있었지만 이제 불허. 공급자를 아예 만들지 않는다.
    prepare("m", 1024);
    when(aiGate.datasetAllowed(ID)).thenReturn(false);

    assertThat(service().sync(ID)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    verify(embeddingFactory, never()).current();
    // 모델 표식이 KEYWORD_ONLY 로 바뀌어 전체 재색인(벡터 차원 1 — 기존 외부 유래 벡터는 테이블과 함께 사라진다).
    verify(index).recreate(any(), eq(RowSearchSyncService.KEYWORD_ONLY_DIM));
    verify(states)
        .resetForFullPass(
            eq(ID),
            anyString(),
            eq(RowSearchSyncService.KEYWORD_ONLY_MODEL),
            eq(RowSearchSyncService.KEYWORD_ONLY_DIM),
            anyLong(),
            eq("m"));
    assertThat(upserted)
        .hasSize(ROWS)
        .allSatisfy(
            r -> {
              assertThat(r.embedding()).isNull();
              assertThat(r.embeddingModel()).isEqualTo(RowSearchSyncService.KEYWORD_ONLY_MODEL);
              assertThat(r.sourceText()).isNotBlank();
            });
  }

  @Test
  void keywordOnly_thenAllowed_recreatesWithModel() {
    // Review Focus 1·2: 키워드 전용 색인(호스팅 미선언·민감)이 자체 호스팅 선언·등급 하향으로 허용되면 실제 모델로 재생성·임베딩된다.
    prepare(RowSearchSyncService.KEYWORD_ONLY_MODEL, RowSearchSyncService.KEYWORD_ONLY_DIM);
    when(aiGate.datasetAllowed(ID)).thenReturn(true);
    when(embeddingFactory.current()).thenReturn(recordingProvider());

    assertThat(service().sync(ID)).isEqualTo(RowSearchSyncService.Outcome.COMPLETED);

    verify(index).recreate(any(), eq(1024));
    verify(states)
        .resetForFullPass(
            eq(ID),
            anyString(),
            eq("m"),
            eq(1024),
            anyLong(),
            eq(RowSearchSyncService.KEYWORD_ONLY_MODEL));
    assertThat(callSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(ROWS);
    assertThat(upserted)
        .hasSize(ROWS)
        .allSatisfy(
            r -> {
              assertThat(r.embedding()).isNotNull();
              assertThat(r.embeddingModel()).isEqualTo("m");
            });
  }

  @Test
  void becomesDisallowedMidCycle_stopsBeforeNextBatch() {
    // 한 주기 도중 등급 상향·호스팅 외부 전환 — 첫 배치(32행) 뒤 불허로 바뀌면 남은 배치를 외부 공급자로 보내지 않고 PARTIAL 로 멈춘다.
    prepare("m", 1024);
    List<SearchSourceReader.SourceRow> all =
        LongStream.rangeClosed(1, ROWS)
            .mapToObj(
                i ->
                    new SearchSourceReader.SourceRow(
                        i, Map.<String, Object>of("content", "행 " + i)))
            .toList();
    // 키셋(afterId)·limit 을 지키는 원본 — 배치가 실제로 여러 번 나뉜다.
    when(reader.fetchChanged(eq("src"), any(), any(), anyLong(), anyInt()))
        .thenAnswer(
            inv -> {
              long after = inv.getArgument(3);
              int limit = inv.getArgument(4);
              return all.stream().filter(r -> r.id() > after).limit(limit).toList();
            });
    // 주기 시작 판정 + 첫 배치 직전 재확인은 허용, 두 번째 배치 직전 재확인에서 불허.
    when(aiGate.datasetAllowed(ID)).thenReturn(true, true, false);
    when(embeddingFactory.current()).thenReturn(recordingProvider());

    assertThat(service(32).sync(ID)).isEqualTo(RowSearchSyncService.Outcome.PARTIAL);

    assertThat(callSizes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(32);
    assertThat(upserted).hasSize(32);
    verify(states, never()).markCompleted(anyLong(), anyLong(), anyLong());
  }

  @Test
  void becomesDisallowedBeforeFirstBatch_sendsNothingToProvider() {
    // 코드리뷰 A-3: 주기 시작 판정과 첫 배치 사이(재색인·패스 시작 캡처)에 등급 상향·호스팅 외부 전환이 일어나면 첫 배치도 외부로 보내지 않는다.
    prepare("m", 1024);
    when(aiGate.datasetAllowed(ID)).thenReturn(true, false);
    when(embeddingFactory.current()).thenReturn(recordingProvider());

    assertThat(service(32).sync(ID)).isEqualTo(RowSearchSyncService.Outcome.PARTIAL);

    assertThat(callSizes).isEmpty();
    assertThat(upserted).isEmpty();
    verify(reader, never()).fetchChanged(any(), any(), any(), anyLong(), anyInt());
    verify(states, never()).markCompleted(anyLong(), anyLong(), anyLong());
  }

  @Test
  void purgeIntervenesBeforeFullPassReset_doesNotOverwriteKeywordOnly_norEmbed() {
    // 코드리뷰 A-3: 키워드 전용→허용 재색인을 시작하려는데 그 사이 정리(임대 없음)가 모델을 다시 키워드 전용으로 바꿨다 — 비교 후 갱신이 실패하면
    // 덮어쓰지 않고(정리 표식 보존) 임베딩 없이 멈춘다. 결정적 재현: 저장소가 "읽은 뒤 바뀌었음"(false)을 돌려준다.
    prepare(RowSearchSyncService.KEYWORD_ONLY_MODEL, RowSearchSyncService.KEYWORD_ONLY_DIM);
    when(aiGate.datasetAllowed(ID)).thenReturn(true);
    when(embeddingFactory.current()).thenReturn(recordingProvider());
    when(states.resetForFullPass(
            eq(ID),
            anyString(),
            eq("m"),
            eq(1024),
            anyLong(),
            eq(RowSearchSyncService.KEYWORD_ONLY_MODEL)))
        .thenReturn(false);

    assertThat(service().sync(ID)).isEqualTo(RowSearchSyncService.Outcome.PARTIAL);

    assertThat(callSizes).isEmpty();
    assertThat(upserted).isEmpty();
    verify(states, never()).startPassIfNeeded(anyLong(), any(), anyLong());
    verify(states, never()).markCompleted(anyLong(), anyLong(), anyLong());
  }
}
