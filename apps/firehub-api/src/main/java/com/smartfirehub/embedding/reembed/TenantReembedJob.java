package com.smartfirehub.embedding.reembed;

import com.smartfirehub.dataset.search.DatasetEmbeddingBackfillService;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.search.DatasetEmbeddingRepository.SourceTextRow;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkContent;
import com.smartfirehub.embedding.EmbeddingNotConfiguredException;
import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.embedding.EmbeddingSpace;
import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.embedding.config.dto.EmbeddingImpact;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.global.transaction.AfterCommitRunner;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.stereotype.Service;

/**
 * 테넌트 전량 재임베딩(스펙 §4). 현재 차원 테이블에 현재 모델 벡터가 없는 청크·데이터셋을 64건씩 임베딩해 옮기고, 끝나면 다른 차원 테이블의 잔여 행을 정리한다.
 *
 * <p><b>동시 실행 방지는 임대 행으로 한다.</b> JobRunr 고정 잡 ID 는 쓰지 않는다 — JobRunr 7 은 같은 id 재투입을 무시해(성공 잡 보관
 * 36시간) A→B→A 전환의 두 번째 A 가 조용히 버려진다. 임대를 못 잡으면 {@link ReembedBusyException} 으로 실패해 JobRunr 재시도가
 * 이어받는다.
 *
 * <p><b>멱등.</b> "현재 모델 벡터 없음" 기준이라 중단 뒤 다시 돌면 남은 것만 처리한다. 배치마다 설정을 다시 읽어 공간이 바뀌었으면 SUPERSEDED 로 멈추고
 * 임대를 푼 뒤 자기 자신을 다시 투입한다(새 설정 공간으로 이어받는다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantReembedJob {

  static final int BATCH = 64;
  static final Duration LEASE = Duration.ofMinutes(10);

  private final EmbeddingProviderFactory providerFactory;
  private final EmbeddingConfigService configService;
  private final DocumentChunkRepository chunkRepository;
  private final DatasetEmbeddingRepository datasetRepository;
  private final EmbeddingReembedStateRepository stateRepository;
  private final EmbeddingBacklogService backlogService;
  private final DatasetEmbeddingBackfillService datasetBackfillService;
  private final JobScheduler jobScheduler;

  /** 한 단계(청크·데이터셋)의 결말 — 끝까지 갔는지, 설정 변경으로 멈췄는지. */
  private enum Outcome {
    COMPLETED,
    SUPERSEDED
  }

  /**
   * 잡을 투입한다. 호출자 트랜잭션이 있으면 커밋 뒤로 미룬다 — JobRunr 는 자기 커넥션을 쓰므로 커밋 전에 투입하면 워커가 아직 보이지 않는 설정을
   * 읽는다(DocumentIngestionService 와 같은 가드).
   */
  public void enqueue(long tenantId) {
    AfterCommitRunner.run(() -> jobScheduler.enqueue(() -> run(tenantId)));
  }

  /** 관리자 "전체 재임베딩": source_text 를 먼저 채워 모집단을 맞춘 뒤 대상 수를 돌려주고 잡을 투입한다. */
  public EmbeddingImpact requestReindexAll() {
    long tenantId = TenantContext.require("전체 재임베딩");
    EmbeddingSpace space =
        configService.currentSpace().orElseThrow(EmbeddingNotConfiguredException::new);
    datasetBackfillService.syncAllSourceText();
    EmbeddingImpact impact = backlogService.impact(space);
    enqueue(tenantId);
    return impact;
  }

  /**
   * 잡 진입점. {@code @Transactional} 을 붙이지 않는다 — 본문 전에 트랜잭션이 열리면 GUC 가 이미 늦고, 외부 임베딩 호출 동안 커넥션을 쥔다.
   * 테넌트는 페이로드로 받아 여기서 세운다 (DatasetEmbeddingService.reindexEmbedding(datasetId, tenantId) 선례).
   */
  @Job(name = "Tenant embedding reembed: tenant %0")
  public void run(long tenantId) {
    TenantContext.runScoped(tenantId, () -> runInTenant(tenantId));
  }

  private void runInTenant(long tenantId) {
    if (!stateRepository.tryAcquire(LEASE)) throw new ReembedBusyException(tenantId);
    boolean superseded = false;
    // 재시도로 넘길 원래 예외. 실패 기록·임대 해제가 또 던져도 이것을 가리지 않게 붙들어 둔다(아래 finally).
    RuntimeException primary = null;
    try {
      EmbeddingProvider provider = providerFactory.current();
      EmbeddingSpace space = EmbeddingSpace.of(provider);
      stateRepository.markRunning(space);
      // 완료 정리 직전에도 설정을 다시 본다 — 마지막 배치 도중 바뀌었다면 옛 차원(=새 설정 공간일 수 있다)을
      // 지우면 안 된다.
      if (reembedChunks(provider, space) == Outcome.SUPERSEDED
          || reembedDatasets(provider, space) == Outcome.SUPERSEDED
          || superseded(space)) {
        // return 하지 않는다 — 아래 재투입이 try 뒤에 있으므로 흐름을 끝까지 흘려보낸다.
        stateRepository.markSuperseded();
        superseded = true;
        log.info("재임베딩 중단(설정 변경): tenant={}, space={}", tenantId, space);
      } else {
        // 완료 — 다른 차원 테이블 잔여 행을 한 번 더 정리한다(쓰기 경로가 이미 옮겼지만 늦게 쓴 행 대비).
        chunkRepository.deleteOtherDimensions(space.dimension());
        datasetRepository.deleteOtherDimensions(space.dimension());
        stateRepository.markDone();
        log.info("재임베딩 완료: tenant={}, space={}", tenantId, space);
      }
    } catch (EmbeddingNotConfiguredException e) {
      // 재시도해도 결과가 같다 — 사유만 남기고 삼킨다(재시도 소진까지 JobRunr 대시보드를 어지럽히지 않게).
      try {
        stateRepository.markFailed(e.getMessage());
      } catch (RuntimeException m) {
        m.addSuppressed(e); // 기록 실패가 올라가더라도 원인(미설정)은 남긴다
        primary = m;
        throw m;
      }
    } catch (RuntimeException e) {
      primary = e;
      try {
        stateRepository.markFailed(Objects.toString(e.getMessage(), e.getClass().getName()));
      } catch (RuntimeException m) {
        e.addSuppressed(m); // 기록 실패가 원래 실패(재시도 대상)를 가리지 않게
      }
      throw e; // JobRunr 백오프 재시도
    } finally {
      try {
        stateRepository.release();
      } catch (RuntimeException r) {
        // finally 에서 던지면 진행 중인 원래 예외가 사라진다 — 있으면 거기에 붙이고, 없으면 그대로 던진다.
        if (primary == null) throw r;
        primary.addSuppressed(r);
      }
    }
    if (superseded) {
      // 새 설정 저장이 잡을 투입했다고 믿을 수 없다: A→B→A 로 되돌리면 저장 시점엔 hasWork(A)=false 라 투입이
      // 없는데, 이 잡이 도중 배치를 B 로 옮겨 A 벡터가 빠진 채 남는다. 임대를 푼 뒤 무조건 다시 투입한다 —
      // 잡은 멱등이고 할 일이 없으면 외부 호출 없이 끝난다.
      enqueue(tenantId);
    }
  }

  /**
   * 판정식 대상 청크를 키셋(id) 순으로 BATCH 건씩 임베딩해 현재 차원 테이블로 옮긴다. 배치마다 설정을 다시 보고 임대를 늘린다. 한 배치의 청크 id 수는
   * BATCH 이하라 저장소의 IN 바인드 한도에 닿지 않는다.
   */
  private Outcome reembedChunks(EmbeddingProvider provider, EmbeddingSpace space) {
    long afterId = 0L;
    while (true) {
      if (superseded(space)) return Outcome.SUPERSEDED;
      List<ChunkContent> batch = chunkRepository.findMissing(space, afterId, BATCH);
      if (batch.isEmpty()) return Outcome.COMPLETED;
      List<float[]> vectors = provider.embed(batch.stream().map(ChunkContent::content).toList());
      chunkRepository.upsertEmbeddings(
          space, batch.stream().map(ChunkContent::chunkId).toList(), vectors);
      afterId = batch.get(batch.size() - 1).chunkId();
      stateRepository.renewLease(LEASE);
    }
  }

  /** 판정식 대상 카탈로그 행을 같은 방식으로 옮긴다(source_text 를 임베딩). */
  private Outcome reembedDatasets(EmbeddingProvider provider, EmbeddingSpace space) {
    long afterId = 0L;
    while (true) {
      if (superseded(space)) return Outcome.SUPERSEDED;
      List<SourceTextRow> batch = datasetRepository.findMissing(space, afterId, BATCH);
      if (batch.isEmpty()) return Outcome.COMPLETED;
      List<float[]> vectors =
          provider.embed(batch.stream().map(SourceTextRow::sourceText).toList());
      // 배치당 한 번(한 트랜잭션) — 행마다 부르면 트랜잭션이 BATCH 회 열린다.
      datasetRepository.upsertEmbeddings(
          space, batch.stream().map(SourceTextRow::datasetId).toList(), vectors);
      afterId = batch.get(batch.size() - 1).datasetId();
      stateRepository.renewLease(LEASE);
    }
  }

  /** 배치마다 설정을 다시 읽는다 — 모델/차원이 바뀌었으면 이 잡은 멈추고 새 잡이 이어받는다. */
  private boolean superseded(EmbeddingSpace running) {
    Optional<EmbeddingSpace> now = configService.currentSpace();
    return now.isEmpty() || !now.get().equals(running);
  }
}
