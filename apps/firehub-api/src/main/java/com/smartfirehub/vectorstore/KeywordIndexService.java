package com.smartfirehub.vectorstore;

import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository;
import com.smartfirehub.dataset.repository.DatasetEmbeddingRepository.SourceTextRow;
import com.smartfirehub.document.repository.DocumentChunkRepository;
import com.smartfirehub.document.repository.DocumentChunkRepository.ChunkSource;
import com.smartfirehub.embedding.SourceHash;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 키워드 색인 쓰기 경로(ADR 0002 결정 4·8, WD-720). 키워드 색인이 원문 열과 따로 있는 저장소(Qdrant)에서만 일한다 — pgvector 는 원문 열
 * 자체가 색인이라 모든 메서드가 바로 끝난다({@code separateKeywordIndex()} 가 거짓).
 *
 * <ul>
 *   <li><b>데이터셋 원문:</b> 데이터셋·태그 편집({@code syncSourceText})은 쓰기 트랜잭션 안에서 원문을 저장한다. 별도 저장소 쓰기는 트랜잭션에
 *       묶이지 않으므로 커밋 뒤에 쓴다({@link #indexDatasetAfterCommit}). 그래서 편집 직후 키워드 검색 반영이 조금 늦을 수 있다.
 *   <li><b>문서 청크:</b> 청크를 커밋한 뒤 적재 잡이 바로 쓴다({@link #indexChunks}).
 *   <li><b>실패분:</b> 쓰기가 성공하면 키워드 표시(「규칙:원문 해시」)를 채운다. 실패했거나 그새 원문이 바뀌었으면 표시가 비거나 어긋나고, 백로그
 *       스윕({@link #sweepCurrentTenant})이 다시 쓴다. 키워드 sparse 는 임베딩 API 없이 만들어 싸다.
 * </ul>
 *
 * <p>모든 쓰기 실패는 로그만 남기고 삼킨다 — 커밋을 마친 편집 요청이나 완료 직전의 적재 잡을 키워드 때문에 실패시키지 않는다.
 */
@Slf4j
@Service
public class KeywordIndexService {

  /** 스윕 한 번에 읽고 쓰는 행 수. */
  static final int BATCH = 256;

  private final DatasetVectorIndex datasetIndex;
  private final ChunkVectorIndex chunkIndex;
  private final DatasetEmbeddingRepository datasetRepository;
  private final DocumentChunkRepository chunkRepository;
  private final VectorStoreFeatures features;

  /**
   * 플랫폼 DB 읽기·표시 쓰기용 새 트랜잭션(REQUIRES_NEW). 커밋 뒤 콜백에서 그냥 리포지토리를 부르면 이미 커밋된 트랜잭션에 합류해 표시 쓰기가 커밋되지 않는다
   * (Spring {@code afterCommit} 규약). 그래서 늘 새 트랜잭션으로 연다.
   */
  private final TransactionTemplate newTx;

  public KeywordIndexService(
      DatasetVectorIndex datasetIndex,
      ChunkVectorIndex chunkIndex,
      DatasetEmbeddingRepository datasetRepository,
      DocumentChunkRepository chunkRepository,
      VectorStoreFeatures features,
      PlatformTransactionManager transactionManager) {
    this.datasetIndex = datasetIndex;
    this.chunkIndex = chunkIndex;
    this.datasetRepository = datasetRepository;
    this.chunkRepository = chunkRepository;
    this.features = features;
    this.newTx = new TransactionTemplate(transactionManager);
    this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /**
   * 이 데이터셋의 키워드를 커밋 뒤에 쓴다. 트랜잭션이 없으면 바로 쓴다. 커밋 뒤 원문을 새로 읽어 쓰므로 같은 트랜잭션에서 여러 번 불려도 마지막 원문이 들어간다.
   * 테넌트는 지금 문맥에서 잡아 둔다.
   */
  public void indexDatasetAfterCommit(long datasetId) {
    if (!datasetIndex.separateKeywordIndex()) return;
    // 저장소가 내려가 있으면 쓰지 않는다(WD-724). 커밋 뒤 요청 스레드에서 도므로 제한 시간까지 응답을 붙들게 된다. 표시가 비어 백로그 스윕이 복구 뒤 쓴다.
    if (!features.available()) return;
    long tenantId = TenantContext.require("데이터셋 키워드 쓰기");
    Runnable write = () -> safely("데이터셋 " + datasetId, () -> writeDataset(tenantId, datasetId));
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              write.run();
            }
          });
    } else {
      write.run();
    }
  }

  /** 커밋된 청크들의 키워드를 쓰고 표시를 채운다. 적재 잡이 청크 저장 직후 부른다(트랜잭션 밖). */
  public void indexChunks(List<ChunkSource> sources) {
    if (!chunkIndex.separateKeywordIndex() || sources.isEmpty()) return;
    if (!features.available()) return; // 저장소 일시 중단 — 백로그 스윕이 복구 뒤 쓴다(WD-724)
    long tenantId = TenantContext.require("청크 키워드 쓰기");
    safely("청크 " + sources.size() + "건", () -> writeChunks(tenantId, sources));
  }

  /**
   * 지금 테넌트의 키워드 백로그를 모두 다시 쓴다(키셋으로 {@value #BATCH} 건씩). 배치 하나가 실패하면 그 배치는 건너뛰고(다음 스윕이 다시 집는다) 계속한다.
   *
   * @return 다시 쓴 행 수(데이터셋 + 청크)
   */
  public int sweepCurrentTenant() {
    long tenantId = TenantContext.require("키워드 백로그 스윕");
    int written = 0;
    if (datasetIndex.separateKeywordIndex()) {
      long after = 0;
      while (true) {
        List<SourceTextRow> batch =
            datasetRepository.findKeywordMissing(datasetIndex.keywordRule(), after, BATCH);
        if (batch.isEmpty()) break;
        List<KeywordSource> sources =
            batch.stream().map(r -> KeywordSource.dataset(r.datasetId(), r.sourceText())).toList();
        if (safely("데이터셋 백로그", () -> writeAndMarkDatasets(tenantId, sources))) {
          written += batch.size();
        }
        after = batch.get(batch.size() - 1).datasetId();
      }
    }
    if (chunkIndex.separateKeywordIndex()) {
      long after = 0;
      while (true) {
        List<ChunkSource> batch =
            chunkRepository.findKeywordMissing(chunkIndex.keywordRule(), after, BATCH);
        if (batch.isEmpty()) break;
        if (safely("청크 백로그", () -> writeChunks(tenantId, batch))) {
          written += batch.size();
        }
        after = batch.get(batch.size() - 1).chunkId();
      }
    }
    return written;
  }

  private void writeDataset(long tenantId, long datasetId) {
    TenantContext.runScoped(
        tenantId,
        () -> {
          String text =
              newTx.execute(s -> datasetRepository.findSourceText(datasetId).orElse(null));
          if (text == null) return; // 그새 데이터셋이 지워졌다
          writeAndMarkDatasets(tenantId, List.of(KeywordSource.dataset(datasetId, text)));
        });
  }

  private void writeAndMarkDatasets(long tenantId, List<KeywordSource> sources) {
    datasetIndex.upsertKeyword(tenantId, sources);
    newTx.executeWithoutResult(
        s ->
            datasetRepository.markKeywordIndexed(
                datasetIndex.keywordRule(),
                sources.stream().map(KeywordSource::id).toList(),
                sources.stream().map(src -> SourceHash.of(src.text())).toList()));
  }

  private void writeChunks(long tenantId, List<ChunkSource> chunks) {
    List<KeywordSource> sources = new ArrayList<>(chunks.size());
    for (ChunkSource c : chunks) {
      sources.add(
          KeywordSource.chunk(
              new ChunkRef(c.chunkId(), c.datasetId(), c.documentFileId()), c.content()));
    }
    chunkIndex.upsertKeyword(tenantId, sources);
    newTx.executeWithoutResult(
        s ->
            chunkRepository.markKeywordIndexed(
                chunkIndex.keywordRule(),
                chunks.stream().map(ChunkSource::chunkId).toList(),
                chunks.stream().map(c -> SourceHash.of(c.content())).toList()));
  }

  /** 실패를 로그로만 남긴다 — 백로그 스윕이 다시 쓴다. 성공하면 참. */
  private static boolean safely(String what, Runnable work) {
    try {
      work.run();
      return true;
    } catch (RuntimeException e) {
      log.warn("키워드 쓰기 실패 — 백로그 스윕이 다시 쓴다: {}", what, e);
      return false;
    }
  }
}
