package com.smartfirehub.dataset.search;

import com.smartfirehub.global.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 데이터셋 변경 이벤트를 받아 커밋 완료 후 비동기로 임베딩만 재생성한다.
 *
 * <p>source_text 는 쓰기 트랜잭션 내에서 이미 동기 저장됐으므로(키워드 검색 즉시 노출) 여기서는 외부 호출을 동반하는 embedding 만 처리한다.
 * AFTER_COMMIT 단계 + 전용 스레드 풀({@code indexExecutor})로 실행해 메인 쓰기 트랜잭션/요청 스레드를 막지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DatasetReindexListener {

  private final DatasetEmbeddingService embeddingService;

  /** 커밋 완료 후 비동기로 임베딩 재생성. 실패해도 best-effort 로 쓰기 경로에 영향을 주지 않는다. */
  @Async("indexExecutor")
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onDatasetChanged(DatasetChangedEvent event) {
    // @Async 경로는 TenantContextTaskDecorator 가 이미 컨텍스트를 세워 준다. 그 값을 그대로
    // 넘긴다(같은 값을 다시 set 하는 것은 무해하다) — JobRunr 경로와 시그니처를 통일하기 위함.
    embeddingService.reindexEmbedding(event.datasetId(), TenantContext.require());
  }
}
