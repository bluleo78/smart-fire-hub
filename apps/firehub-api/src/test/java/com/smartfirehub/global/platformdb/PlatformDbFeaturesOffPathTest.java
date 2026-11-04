package com.smartfirehub.global.platformdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.smartfirehub.admin.embedding.EmbeddingStatusService;
import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.DatasetMetaReader;
import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.dataset.repository.DatasetSearchRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetEmbeddingService;
import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.dataset.search.DatasetSearchMode;
import com.smartfirehub.dataset.search.DatasetSearchRequest;
import com.smartfirehub.dataset.search.DatasetSearchService;
import com.smartfirehub.dataset.search.DatasetSourceTextBuilder;
import com.smartfirehub.document.dto.Chunk;
import com.smartfirehub.document.dto.DocumentFileResponse;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.document.dto.DocumentSearchRequest;
import com.smartfirehub.document.dto.ExtractedText;
import com.smartfirehub.document.dto.SearchMode;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentFileRepository;
import com.smartfirehub.document.service.DocumentIngestionService;
import com.smartfirehub.document.service.DocumentSearchService;
import com.smartfirehub.document.service.DocumentStorageService;
import com.smartfirehub.document.service.TextChunker;
import com.smartfirehub.document.service.TextExtractor;
import com.smartfirehub.embedding.EmbeddingDimension;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.reembed.EmbeddingBacklogService;
import com.smartfirehub.embedding.reembed.TenantReembedJob;
import com.smartfirehub.embedding.repository.EmbeddingReembedStateRepository;
import com.smartfirehub.global.platformdb.postgres.PostgresPlatformDbFeatures;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.notification.service.NotificationService;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.access.ProviderHosting;
import com.smartfirehub.securitylevel.ai.AiVectorPurgeService;
import com.smartfirehub.securitylevel.ai.AiVectorPurgeStartupRunner;
import com.smartfirehub.securitylevel.ai.EmbeddingAiGate;
import com.smartfirehub.securitylevel.ai.EmbeddingAllowance;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * WD-486 Task 5 — 플랫폼 DB 기능 플래그가 꺼졌을 때(Tibero 등) 각 서비스가 5xx 없이 해당 단계만 건너뛰는지 확인한다.
 *
 * <p>모두 목 기반 단위 테스트다. 꺼진 단계의 리포지토리 메서드가 한 번도 불리지 않았음과 돌려준 결과를 단언한다.
 */
class PlatformDbFeaturesOffPathTest {

  private static final Clearance CLEARANCE = Clearance.none(1L, 1L);
  // 가드(목)가 캡처해 돌려주는 가시성 값 — 서비스가 이 값을 그대로 리포지토리에 넘기는지 eq(VIS) 로 고정한다(WD-485 T10).
  private static final DatasetVisibility VIS = new DatasetVisibility(CLEARANCE, null);

  /** 지정한 기능만 켜진 스텁. */
  private static PlatformDbFeatures only(PlatformDbFeature... on) {
    Set<PlatformDbFeature> set = EnumSet.noneOf(PlatformDbFeature.class);
    set.addAll(List.of(on));
    return set::contains;
  }

  private static EmbeddingProvider provider() {
    return new EmbeddingProvider() {
      public List<float[]> embed(List<String> texts) {
        return texts.stream().map(t -> new float[1024]).toList();
      }

      public String modelId() {
        return "fake";
      }

      public int dimension() {
        return 1024;
      }
    };
  }

  // ---------- 예외 / PG 구현 ----------

  @Test
  void 예외는_422_와_고정_코드를_가진다() {
    var e = new PlatformDbFeatureUnsupportedException(PlatformDbFeature.VECTOR_SEARCH);
    assertThat(e.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(PlatformDbFeatureUnsupportedException.CODE)
        .isEqualTo("PLATFORM_DB_FEATURE_UNSUPPORTED");
    assertThat(e.code()).isEqualTo("PLATFORM_DB_FEATURE_UNSUPPORTED");
  }

  @Test
  void PG_구현은_모든_기능을_지원한다() {
    var pg = new PostgresPlatformDbFeatures();
    for (PlatformDbFeature f : PlatformDbFeature.values()) {
      assertThat(pg.supports(f)).isTrue();
    }
  }

  // ---------- 데이터셋 검색 ----------

  private DatasetSearchHit dsHit(long id) {
    return new DatasetSearchHit(id, "n" + id, "d", "DATASET", "SOURCE", "t" + id, "c", 0.5);
  }

  private record DsFixture(
      DatasetSearchRepository repo, EmbeddingProviderFactory factory, DatasetSearchService svc) {}

  private DsFixture ds(PlatformDbFeatures features) {
    var repo = mock(DatasetSearchRepository.class);
    var factory = mock(EmbeddingProviderFactory.class);
    var guard = mock(DatasetAccessGuard.class);
    when(guard.visibility(CLEARANCE)).thenReturn(VIS);
    when(factory.current()).thenReturn(provider());
    return new DsFixture(repo, factory, new DatasetSearchService(repo, factory, guard, features));
  }

  @Test
  void 데이터셋_HYBRID_벡터_꺼짐이면_의미검색_없이_키워드만_쓴다() {
    var f = ds(only(PlatformDbFeature.TRIGRAM_SEARCH));
    when(f.repo.searchByTrigram(eq("화재"), any(), anyInt(), eq(VIS))).thenReturn(List.of(dsHit(1L)));
    var r =
        f.svc.search(new DatasetSearchRequest("화재", 10, DatasetSearchMode.HYBRID, null), CLEARANCE);
    assertThat(r).extracting(DatasetSearchHit::datasetId).containsExactly(1L);
    verify(f.repo, never()).searchByCosine(any(), any(), any(), anyInt(), any());
    verifyNoInteractions(f.factory);
  }

  @Test
  void 데이터셋_HYBRID_트라이그램_꺼짐이면_키워드_없이_의미검색만_쓴다() {
    var f = ds(only(PlatformDbFeature.VECTOR_SEARCH));
    when(f.repo.searchByCosine(any(), any(), any(), anyInt(), eq(VIS)))
        .thenReturn(List.of(dsHit(2L)));
    var r =
        f.svc.search(new DatasetSearchRequest("화재", 10, DatasetSearchMode.HYBRID, null), CLEARANCE);
    assertThat(r).extracting(DatasetSearchHit::datasetId).containsExactly(2L);
    verify(f.repo, never()).searchByTrigram(anyString(), any(), anyInt(), any());
  }

  @Test
  void 데이터셋_둘_다_꺼지면_모드와_무관하게_빈_결과이고_예외가_없다() {
    for (DatasetSearchMode mode : DatasetSearchMode.values()) {
      var f = ds(only());
      var r = f.svc.search(new DatasetSearchRequest("화재", 10, mode, null), CLEARANCE);
      assertThat(r).isEmpty();
      verify(f.repo, never()).searchByCosine(any(), any(), any(), anyInt(), any());
      verify(f.repo, never()).searchByTrigram(anyString(), any(), anyInt(), any());
      verifyNoInteractions(f.factory);
    }
  }

  @Test
  void 데이터셋_SEMANTIC_벡터_꺼짐은_빈_목록이고_KEYWORD_트라이그램_꺼짐도_빈_목록() {
    var f = ds(only(PlatformDbFeature.TRIGRAM_SEARCH));
    assertThat(
            f.svc.search(
                new DatasetSearchRequest("화재", 10, DatasetSearchMode.SEMANTIC, null), CLEARANCE))
        .isEmpty();
    var g = ds(only(PlatformDbFeature.VECTOR_SEARCH));
    assertThat(
            g.svc.search(
                new DatasetSearchRequest("화재", 10, DatasetSearchMode.KEYWORD, null), CLEARANCE))
        .isEmpty();
    verify(g.repo, never()).searchByTrigram(anyString(), any(), anyInt(), any());
  }

  // ---------- 문서 검색 ----------

  private record DocFixture(
      DocumentChunkRepository repo, EmbeddingProviderFactory factory, DocumentSearchService svc) {}

  private DocFixture doc(PlatformDbFeatures features) {
    var repo = mock(DocumentChunkRepository.class);
    var factory = mock(EmbeddingProviderFactory.class);
    var guard = mock(DatasetAccessGuard.class);
    when(guard.visibility(CLEARANCE)).thenReturn(VIS);
    when(factory.current()).thenReturn(provider());
    return new DocFixture(repo, factory, new DocumentSearchService(factory, repo, guard, features));
  }

  private DocumentSearchHit chunkHit(long id) {
    return new DocumentSearchHit(id, 2L, 3L, "f.txt", 0, "내용", 0.9);
  }

  @Test
  void 문서_HYBRID_벡터_꺼짐이면_의미검색_없이_키워드만_쓴다() {
    var f = doc(only(PlatformDbFeature.TRIGRAM_SEARCH));
    when(f.repo.searchByTrigram(eq("질의"), any(), anyInt(), eq(VIS)))
        .thenReturn(List.of(chunkHit(1L)));
    var r = f.svc.search(new DocumentSearchRequest("질의", null, 5, SearchMode.HYBRID), CLEARANCE);
    assertThat(r).extracting(DocumentSearchHit::chunkId).containsExactly(1L);
    verify(f.repo, never()).searchByCosine(any(), any(), any(), anyInt(), any());
    verifyNoInteractions(f.factory);
  }

  @Test
  void 문서_HYBRID_트라이그램_꺼짐이면_키워드_없이_의미검색만_쓴다() {
    var f = doc(only(PlatformDbFeature.VECTOR_SEARCH));
    when(f.repo.searchByCosine(any(), any(), any(), anyInt(), eq(VIS)))
        .thenReturn(List.of(chunkHit(2L)));
    var r = f.svc.search(new DocumentSearchRequest("질의", null, 5, SearchMode.HYBRID), CLEARANCE);
    assertThat(r).extracting(DocumentSearchHit::chunkId).containsExactly(2L);
    verify(f.repo, never()).searchByTrigram(anyString(), any(), anyInt(), any());
  }

  @Test
  void 문서_둘_다_꺼지면_모드와_무관하게_빈_결과이고_예외가_없다() {
    for (SearchMode mode : SearchMode.values()) {
      var f = doc(only());
      var r = f.svc.search(new DocumentSearchRequest("질의", null, 5, mode), CLEARANCE);
      assertThat(r).isEmpty();
      verify(f.repo, never()).searchByCosine(any(), any(), any(), anyInt(), any());
      verify(f.repo, never()).searchByTrigram(anyString(), any(), anyInt(), any());
      verifyNoInteractions(f.factory);
    }
  }

  // ---------- 임베딩 쓰기 ----------

  @Test
  void 데이터셋_임베딩_벡터_꺼짐이면_벡터_쓰기만_건너뛰고_source_text_는_그대로_저장한다() {
    var repo = mock(DatasetEmbeddingRepository.class);
    var meta = mock(DatasetMetaReader.class);
    var factory = mock(EmbeddingProviderFactory.class);
    var gate = mock(EmbeddingAiGate.class);
    var input =
        new DatasetSourceTextBuilder.Input(
            "화재", "설명", "fire", List.of("col"), List.of("tag"), "안전");
    when(meta.read(7L)).thenReturn(input);
    var svc = new DatasetEmbeddingService(repo, meta, factory, gate, only());

    svc.syncSourceText(7L);
    svc.reindexEmbedding(7L, 1L);

    verify(repo).upsertSourceText(eq(7L), any(String.class));
    verify(repo, never()).upsertEmbedding(any(), anyLong(), any());
    verify(repo, never()).deleteVectors(anyLong());
    verifyNoInteractions(factory);
  }

  @Test
  void 데이터셋_백필_벡터_꺼지면_잡을_쌓지_않고_source_text_만_적재한다() {
    var meta = mock(DatasetMetaReader.class);
    var embedding = mock(DatasetEmbeddingService.class);
    var scheduler = mock(JobScheduler.class);
    when(meta.findAllIds()).thenReturn(List.of(1L, 2L));
    var svc = new DatasetEmbeddingBackfillService(meta, embedding, scheduler, only());

    assertThat(svc.backfillAll()).isEqualTo(2);

    verify(embedding).syncSourceText(1L);
    verify(embedding).syncSourceText(2L);
    verifyNoInteractions(scheduler);
  }

  @Test
  void 문서_인제스션_벡터_꺼짐이면_청크_본문만_저장하고_임베딩_안_한다() {
    var datasetRepo = mock(DatasetRepository.class);
    var storage = mock(DocumentStorageService.class);
    var files = mock(DocumentFileRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    var extractor = mock(TextExtractor.class);
    var chunker = mock(TextChunker.class);
    var factory = mock(EmbeddingProviderFactory.class);
    var notif = mock(NotificationService.class);
    var gate = mock(EmbeddingAiGate.class);
    var file =
        new DocumentFileResponse(
            9L, 3L, "a.txt", "text/plain", 1L, "PENDING", null, null, null, 5L, null, null);
    when(files.findById(9L)).thenReturn(Optional.of(file));
    when(files.findStoragePath(9L)).thenReturn("p");
    when(storage.read("p")).thenReturn(new byte[0]);
    when(extractor.extract(any(), any(), any())).thenReturn(new ExtractedText("본문", 1));
    var list = List.of(new Chunk(0, "본문", 1));
    when(chunker.chunk("본문")).thenReturn(list);
    when(gate.datasetAllowed(3L)).thenReturn(true);
    var svc =
        new DocumentIngestionService(
            datasetRepo,
            storage,
            files,
            chunks,
            extractor,
            chunker,
            factory,
            notif,
            mock(JobScheduler.class),
            gate,
            only());

    svc.processIngestion(9L, 1L);

    verify(chunks).insertChunksOnly(9L, 3L, list);
    verify(chunks, never()).insertBatch(anyLong(), anyLong(), anyList(), anyList(), any());
    verifyNoInteractions(factory);
    verify(files).markCompleted(9L, 1, 1);
  }

  // ---------- 재임베딩·정리 ----------

  @Test
  void 재임베딩_잡_벡터_꺼짐이면_아무것도_하지_않는다() {
    var factory = mock(EmbeddingProviderFactory.class);
    var state = mock(EmbeddingReembedStateRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    var datasets = mock(DatasetEmbeddingRepository.class);
    var job =
        new TenantReembedJob(
            factory,
            mock(EmbeddingConfigService.class),
            chunks,
            datasets,
            state,
            mock(EmbeddingBacklogService.class),
            mock(DatasetEmbeddingBackfillService.class),
            mock(JobScheduler.class),
            mock(EmbeddingAiGate.class),
            only());

    job.run(7L);

    verifyNoInteractions(factory, state, chunks, datasets);
  }

  @Test
  void 벡터_정리_러너_벡터_꺼짐이면_정리도_플래그도_없다() {
    var settings = mock(TenantSettingsRepository.class);
    var purge = mock(AiVectorPurgeService.class);
    var runner =
        new AiVectorPurgeStartupRunner(
            mock(TenantScopedRunner.class), settings, purge, true, only());

    runner.runForCurrentTenant();

    verifyNoInteractions(purge, settings);
  }

  @Test
  void 벡터_정리_서비스_벡터_꺼짐이면_두_진입점_모두_리포지토리를_건드리지_않는다() {
    var gate = mock(EmbeddingAiGate.class);
    var dsRepo = mock(DatasetEmbeddingRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    var service =
        new AiVectorPurgeService(
            gate,
            dsRepo,
            chunks,
            mock(com.smartfirehub.dataset.repository.SearchIndexStateRepository.class),
            mock(com.smartfirehub.dataset.rowsearch.RowSearchIndex.class),
            mock(DatasetRepository.class),
            mock(com.smartfirehub.ontology.repository.GraphOntologySourceRepository.class),
            only());

    var r =
        com.smartfirehub.global.tenant.TenantContext.runScopedGet(
            1L,
            () -> {
              var a = service.purgeDisallowed();
              var b = service.purgeDataset(5L);
              assertThat(a.datasets()).isZero();
              assertThat(b.datasets()).isZero();
              return a;
            });

    assertThat(r.failures()).isZero();
    verifyNoInteractions(gate, dsRepo, chunks);
  }

  // ---------- 벡터 테이블 읽기 가드(WD-485, WD-486 이월) ----------

  private static final EmbeddingSpace SPACE = new EmbeddingSpace(EmbeddingDimension.D1024, "fake");

  private static final EmbeddingAllowance ALLOWANCE =
      new EmbeddingAllowance(ProviderHosting.EXTERNAL);

  @Test
  void 임베딩_상태_벡터_꺼짐이면_분자만_0_이고_벡터_테이블_조회가_없다_분모는_그대로() {
    var datasets = mock(DatasetEmbeddingRepository.class);
    var chunks = mock(DocumentChunkRepository.class);
    var config = mock(EmbeddingConfigService.class);
    var state = mock(EmbeddingReembedStateRepository.class);
    var gate = mock(EmbeddingAiGate.class);
    when(gate.allowance()).thenReturn(ALLOWANCE);
    when(config.currentSpace()).thenReturn(Optional.of(SPACE));
    when(datasets.countAll(ALLOWANCE)).thenReturn(28L);
    when(chunks.countAllChunks(ALLOWANCE)).thenReturn(500L);
    var svc = new EmbeddingStatusService(datasets, chunks, config, state, gate, only());

    var r = svc.status();

    // 분모는 기반 테이블이라 그대로 읽고, 벡터 테이블을 읽는 분자만 0 이다.
    assertThat(r.datasets().total()).isEqualTo(28L);
    assertThat(r.datasets().embedded()).isZero();
    assertThat(r.documentChunks().total()).isEqualTo(500L);
    assertThat(r.documentChunks().embedded()).isZero();
    verify(datasets, never()).countEmbedded(any());
    verify(chunks, never()).countEmbedded(any());
    // 허용값은 한 번만 캡처한다.
    verify(gate).allowance();
  }

  @Test
  void 백로그_벡터_꺼짐이면_영향도_누락_수는_0_이고_hasWork_는_false_이며_벡터_조회가_없다() {
    var chunks = mock(DocumentChunkRepository.class);
    var datasets = mock(DatasetEmbeddingRepository.class);
    var indexStates = mock(com.smartfirehub.dataset.repository.SearchIndexStateRepository.class);
    var gate = mock(EmbeddingAiGate.class);
    when(gate.allowance()).thenReturn(ALLOWANCE);
    when(indexStates.countStale(any(), anyInt())).thenReturn(4L);
    var svc = new EmbeddingBacklogService(chunks, datasets, indexStates, gate, only());

    var impact = svc.impact(SPACE);

    assertThat(impact.chunks()).isZero();
    assertThat(impact.datasets()).isZero();
    // 행 검색 색인 stale 수는 벡터 테이블을 읽지 않아 그대로다.
    assertThat(impact.rowSearchIndexes()).isEqualTo(4L);
    assertThat(svc.hasWork(SPACE)).isFalse();
    verify(chunks, never()).countMissing(any(), any());
    verify(datasets, never()).countMissing(any(), any());
  }

  @Test
  void 백로그_벡터_켜짐이면_허용값을_한_번만_캡처해_두_리포지토리에_같이_넘긴다() {
    var chunks = mock(DocumentChunkRepository.class);
    var datasets = mock(DatasetEmbeddingRepository.class);
    var indexStates = mock(com.smartfirehub.dataset.repository.SearchIndexStateRepository.class);
    var gate = mock(EmbeddingAiGate.class);
    when(gate.allowance()).thenReturn(ALLOWANCE);
    when(chunks.countMissing(SPACE, ALLOWANCE)).thenReturn(2L);
    when(datasets.countMissing(SPACE, ALLOWANCE)).thenReturn(3L);
    var svc =
        new EmbeddingBacklogService(
            chunks, datasets, indexStates, gate, only(PlatformDbFeature.VECTOR_SEARCH));

    var impact = svc.impact(SPACE);

    assertThat(impact.chunks()).isEqualTo(2L);
    assertThat(impact.datasets()).isEqualTo(3L);
    verify(gate).allowance();
    assertThat(svc.hasWork(SPACE)).isTrue();
  }
}
