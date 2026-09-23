package com.smartfirehub.dataset.rowsearch;

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
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 하나의 행 검색 색인을 원본과 맞춘다(설계 4장).
 *
 * <p>트랜잭션을 걸지 않는다 — 임베딩 호출(외부 서버)을 DB 트랜잭션 밖에 두고, DB 쓰기는 각 저장소의 짧은 트랜잭션으로
 * 나눈다. 실패하면 책갈피를 전진시키지 않아 다음 주기가 같은 구간을 다시 처리한다(source_hash 로 중복 임베딩 흡수).
 *
 * <p><b>크로스 도메인 의존:</b> 책갈피 후보 캡처는 {@code pipeline.service.IncrementalCursorService} 를 그대로
 * 재사용한다 — "처리 직전에, 커밋 안 된 가장 오래된 트랜잭션 시작 시각 이하로 잡는다"는 불변식이 파이프라인 증분 처리와
 * 같기 때문이다. 이 서비스가 다른 패키지로 옮겨지면 여기 import 도 함께 고친다.
 */
@Slf4j
@Service
public class RowSearchSyncService {

  /** 한 번의 sync 결과. PARTIAL 은 주기 상한에 걸려 다음 주기에 이어서 처리한다는 뜻이다. */
  public enum Outcome {
    SKIPPED,
    PARTIAL,
    COMPLETED,
    FAILED
  }

  private static final Duration LEASE = Duration.ofMinutes(10);
  private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);

  /**
   * 임베딩 서버 한 번 호출에 보내는 최대 텍스트 수. 배치(기본 200행 × 최대 8000자)를 통째로 보내면 느린 서버(Ollama 는
   * 120초 타임아웃)에서 매번 시간 초과로 실패해 같은 구간을 영영 재시도하게 된다 — 그래서 작게 나눠 보낸다.
   */
  static final int EMBED_CHUNK_SIZE = 32;

  private final RowSearchIndex index;
  private final SearchIndexStateRepository states;
  private final SearchColumnRepository searchColumns;
  private final SearchSourceReader reader;
  private final DatasetRepository datasetRepository;
  private final EmbeddingProviderFactory embeddingFactory;
  private final IncrementalCursorService cursorService;
  private final int maxRowsPerCycle;
  private final int batchSize;

  /** 주기 상한·배치 크기는 설정값으로 받는다(테스트가 작은 값으로 경계를 재현한다). */
  public RowSearchSyncService(
      RowSearchIndex index,
      SearchIndexStateRepository states,
      SearchColumnRepository searchColumns,
      SearchSourceReader reader,
      DatasetRepository datasetRepository,
      EmbeddingProviderFactory embeddingFactory,
      IncrementalCursorService cursorService,
      @Value("${row-search.sync.max-rows-per-cycle:5000}") int maxRowsPerCycle,
      @Value("${row-search.sync.batch-size:200}") int batchSize) {
    this.index = index;
    this.states = states;
    this.searchColumns = searchColumns;
    this.reader = reader;
    this.datasetRepository = datasetRepository;
    this.embeddingFactory = embeddingFactory;
    this.cursorService = cursorService;
    this.maxRowsPerCycle = maxRowsPerCycle;
    this.batchSize = batchSize;
  }

  /**
   * 데이터셋 하나를 한 주기만큼 동기화한다. 임대를 못 잡으면(다른 인스턴스가 처리 중) SKIPPED.
   *
   * <p>호출자는 테넌트 스코프(TenantContext) 안에서 부른다. 실패 시 지수 백오프(1,2,4,8,16,30분)를 건다.
   */
  public Outcome sync(long datasetId) {
    // 임대를 먼저 잡고 상태를 읽는다. 임대 전에 읽으면 다른 워커가 그 사이 패스를 끝낸 뒤의 낡은 스냅샷
    // (resume_after_id·sync_cursor 등)으로 이어 처리해, 새 책갈피로 확정하면서 id≤resume 구간의 변경을 영영 놓친다.
    // 상태 행이 없으면 UPDATE 가 0행이라 tryAcquireLease 도 false 다.
    if (!states.tryAcquireLease(datasetId, LEASE)) return Outcome.SKIPPED;
    SearchIndexState state = null;
    try {
      state = states.find(datasetId).orElse(null);
      if (state == null) return Outcome.SKIPPED; // 임대 직후 끄기(상태 행 삭제)와 경합
      return doSync(datasetId, state);
    } catch (RuntimeException e) {
      log.warn("행 검색 색인 동기화 실패: datasetId={}", datasetId, e);
      int failures = (state == null ? 0 : state.consecutiveFailures()) + 1;
      long backoffMin = Math.min(MAX_BACKOFF.toMinutes(), 1L << Math.min(failures - 1, 5)); // 1,2,4,8,16,30분
      // 메시지 없는 예외(NPE 등)도 원인을 남기도록 클래스 이름으로 대체한다.
      states.markFailed(
          datasetId,
          Objects.toString(e.getMessage(), e.getClass().getName()),
          OffsetDateTime.now().plusMinutes(backoffMin));
      return Outcome.FAILED;
    } finally {
      states.releaseLease(datasetId);
    }
  }

  /** 재색인 판단 → 책갈피 후보 캡처 → 변경분 배치 처리 → (완주 시) 삭제분 정리·책갈피 확정. */
  private Outcome doSync(long datasetId, SearchIndexState state) {
    // DatasetRepository 는 클래스 레벨 @Transactional 이라 트랜잭션 없이 불려도 RLS GUC 가 선다.
    DatasetResponse dataset = datasetRepository.findById(datasetId).orElse(null);
    if (dataset == null) return Outcome.SKIPPED; // 삭제 중 — CASCADE 로 상태 행도 곧 사라진다
    IndexRef ref = new IndexRef(TenantContext.require(), datasetId, dataset.tableName());
    SearchConfig config = searchColumns.findConfig(datasetId);
    if (config.isEmpty()) {
      // 검색 탭에서 끄면 설정 서비스가 상태 행·색인을 지우지만, 검색 대상 컬럼을 컬럼 삭제로 모두 없애면 상태 행과
      // 색인 테이블이 남는다. 그대로 두면 영영 낡은 색인이 "사용 가능"으로 검색되므로 여기서 끄기와 같게 정리한다.
      index.drop(ref);
      states.delete(datasetId);
      return Outcome.SKIPPED;
    }
    EmbeddingProvider provider = embeddingFactory.current();
    String model = provider.modelId();
    int dim = provider.dimension();
    long oid = reader.currentOid(ref.sourceTable());

    // 1) 전체 재색인 판단
    boolean configChanged =
        !config.configHash().equals(state.configHash())
            || !model.equals(state.embeddingModel())
            || !Objects.equals(state.embeddingDim(), dim);
    if (configChanged) {
      index.recreate(ref, dim); // 텍스트나 모델이 달라 재사용 불가
      states.resetForFullPass(datasetId, config.configHash(), model, dim, oid);
      state = states.find(datasetId).orElseThrow();
    } else if (state.sourceTableOid() == null || state.sourceTableOid() != oid) {
      index.rebuildReusing(ref, dim); // swap: id 가 다시 매겨졌다 — 이전 색인은 내용 주소 재사용용으로만
      states.markSourceOid(datasetId, oid);
      state = states.find(datasetId).orElseThrow();
    }

    // 2) 책갈피 후보 — 패스 시작 시에만 캡처(IncrementalCursorService 불변식: 실행 직전).
    //    패스 시작이면 원본 전체 행 수도 함께 기록해 여러 주기에 걸친 색인 중에도 진행률(분모)이 보이게 한다.
    //    (resetForFullPass·markSourceOid 는 pass_cursor 를 비우고 state 를 다시 읽으므로 이 판정이 맞다.)
    if (state.passCursor() == null) {
      states.startPassIfNeeded(
          datasetId, cursorService.captureCandidate(), reader.countRows(ref.sourceTable()));
    }

    // 3) 변경분 처리
    boolean reusing = index.hasPrev(ref);
    long afterId = state.resumeAfterId() == null ? 0L : state.resumeAfterId();
    int processed = 0;
    boolean completed = false;
    while (processed < maxRowsPerCycle) {
      List<SearchSourceReader.SourceRow> rows =
          reader.fetchChanged(
              ref.sourceTable(), config.columnNames(), state.syncCursor(), afterId, batchSize);
      if (rows.isEmpty()) {
        completed = true;
        break;
      }
      long added = processBatch(ref, config, rows, provider, model, reusing);
      afterId = rows.get(rows.size() - 1).id();
      processed += rows.size();
      states.saveProgress(datasetId, afterId, added);
      if (rows.size() < batchSize) {
        completed = true;
        break;
      }
    }
    if (!completed) {
      // 상한에 딱 맞게 끝난 경우 한 주기를 헛돌지 않도록 남은 행이 있는지 1건만 들여다본다.
      completed =
          reader
              .fetchChanged(ref.sourceTable(), config.columnNames(), state.syncCursor(), afterId, 1)
              .isEmpty();
    }
    if (!completed) return Outcome.PARTIAL;

    // 4) 삭제분 정리 + 5) 마무리
    index.deleteMissing(ref);
    if (reusing) index.dropPrev(ref);
    states.markCompleted(datasetId, index.count(ref), reader.countRows(ref.sourceTable()));
    return Outcome.COMPLETED;
  }

  /**
   * 한 배치: 텍스트 조립 → 해시 같으면 생략 → (재구축 중이면) 재사용 → 나머지만 임베딩.
   *
   * @return 색인 행 수 증감(새로 들어간 행 − 지운 행). 진행률(indexed_rows)을 count(*) 없이 갱신하는 데 쓴다 — 이미
   *     색인에 있던 행의 갱신은 0 이다.
   */
  private long processBatch(
      IndexRef ref,
      SearchConfig config,
      List<SearchSourceReader.SourceRow> rows,
      EmbeddingProvider provider,
      String model,
      boolean reusing) {
    Map<Long, String> existing =
        index.existingHashes(ref, rows.stream().map(SearchSourceReader.SourceRow::id).toList());
    List<Long> toDelete = new ArrayList<>();
    long added = 0;
    List<Pending> pending = new ArrayList<>();
    for (SearchSourceReader.SourceRow row : rows) {
      String text = config.buildSourceText(row.values());
      if (text.isEmpty()) {
        // 검색 대상 필드가 모두 비었다 — 색인에 있던 행이면 지운다.
        if (existing.containsKey(row.id())) toDelete.add(row.id());
        continue;
      }
      String hash = SearchConfig.sha256(text);
      if (hash.equals(existing.get(row.id()))) continue; // 검색 대상 아닌 컬럼만 바뀜
      if (!existing.containsKey(row.id())) added++; // 이 배치에서 색인에 새로 들어가는 행(재사용·임베딩 모두)
      if (reusing && index.upsertReusing(ref, row.id(), text, hash, model)) continue;
      pending.add(new Pending(row.id(), text, hash));
    }
    index.deleteRows(ref, toDelete);
    added -= toDelete.size();
    if (pending.isEmpty()) return added;
    // 트랜잭션 밖에서, 한 호출이 너무 커지지 않도록 EMBED_CHUNK_SIZE 씩 나눠 임베딩한다(순서 유지).
    List<String> texts = pending.stream().map(Pending::text).toList();
    List<float[]> vectors = new ArrayList<>(texts.size());
    for (int from = 0; from < texts.size(); from += EMBED_CHUNK_SIZE) {
      vectors.addAll(provider.embed(texts.subList(from, Math.min(from + EMBED_CHUNK_SIZE, texts.size()))));
    }
    List<IndexedRow> upserts = new ArrayList<>(pending.size());
    for (int i = 0; i < pending.size(); i++) {
      Pending p = pending.get(i);
      upserts.add(new IndexedRow(p.rowId(), p.text(), p.hash(), vectors.get(i), model));
    }
    index.upsert(ref, upserts);
    return added;
  }

  /** 임베딩이 필요한 행 하나(재사용되지 않고 새로 임베딩할 대상). */
  private record Pending(long rowId, String text, String hash) {}
}
