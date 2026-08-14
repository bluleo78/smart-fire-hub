package com.smartfirehub.dataset.search;

import com.smartfirehub.embedding.EmbeddingProvider;
import com.smartfirehub.embedding.EmbeddingProviderFactory;
import com.smartfirehub.global.tenant.TenantContext;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 데이터셋 검색 인덱스 적재 서비스.
 *
 * <ul>
 *   <li>syncSourceText: 외부호출 없는 문자열 합본만 동기 저장(쓰기 트랜잭션 내 호출 → 키워드 검색 즉시 가시성 보장).
 *   <li>reindexEmbedding: bge-m3 임베딩 생성(외부 호출 동반, 비동기 경로 전용).
 * </ul>
 *
 * <p>주의: 생성자 인자 순서는 {@code @RequiredArgsConstructor} 가 필드 선언 순서대로 생성한다
 * (embeddingRepo, metaReader, embeddingFactory). 단위 테스트가 이 3-arg 시그니처에 의존한다.
 */
@Service
@RequiredArgsConstructor
public class DatasetEmbeddingService {

  private final DatasetEmbeddingRepository embeddingRepo;
  private final DatasetMetaReader metaReader;
  private final EmbeddingProviderFactory embeddingFactory;

  /** 동기: source_text 만 갱신. 메타 없으면(삭제됨) 인덱스 제거. 쓰기 트랜잭션 내에서 호출. */
  public void syncSourceText(long datasetId) {
    DatasetSourceTextBuilder.Input meta = metaReader.read(datasetId);
    if (meta == null) {
      embeddingRepo.delete(datasetId);
      return;
    }
    embeddingRepo.upsertSourceText(datasetId, DatasetSourceTextBuilder.build(meta));
  }

  /**
   * 비동기: 현재 메타 기준 임베딩 생성·갱신. 메타 없으면 no-op(동기 경로에서 이미 제거됨).
   *
   * <p>이 메서드는 두 경로에서 호출된다 — JobRunr 배경 잡({@link DatasetEmbeddingBackfillService})과
   * {@code @Async} 리스너({@link DatasetReindexListener}). 전자는 이 메서드가 잡 스레드의 진입점이라
   * tenantId 를 여기서 직접 세워야 하지만, 후자는 진입점이 아니다 — TenantContextTaskDecorator 가 이미
   * 컨텍스트를 세워둔 스레드 한복판에서 호출된다. 그래서 종료 시 무조건 clear() 하면 안 되고, 호출 전
   * 컨텍스트를 저장했다가 그대로 복원해야 한다 — clear()로 밀어버리면 이 메서드가 리스너 호출의 마지막
   * 문장이 아니게 되는 순간(P2-b/P4에서 뒤에 로직이 추가되는 경우) 그 이후 코드가 컨텍스트 없이 실행되어
   * RLS 가 조용히 0행을 반환하게 된다.
   */
  public void reindexEmbedding(long datasetId, long tenantId) {
    TenantContext.runScoped(
        tenantId,
        () -> {
          DatasetSourceTextBuilder.Input meta = metaReader.read(datasetId);
          if (meta == null) {
            return;
          }
          String sourceText = DatasetSourceTextBuilder.build(meta);
          EmbeddingProvider provider = embeddingFactory.current();
          float[] embedding = provider.embed(List.of(sourceText)).get(0);
          embeddingRepo.updateEmbedding(datasetId, embedding, provider.modelId());
        });
  }
}
