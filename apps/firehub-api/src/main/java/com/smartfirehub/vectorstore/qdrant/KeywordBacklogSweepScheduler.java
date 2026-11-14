package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.KeywordIndexService;
import com.smartfirehub.vectorstore.VectorStoreFeatures;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 키워드 백로그 스윕(ADR 0002 결정 4, WD-720) — 주기마다 활성 테넌트의 키워드 표시가 비었거나 어긋난 행을 다시 쓴다. Qdrant 설치본에서만 뜬다
 * (pgvector 는 원문 열이 곧 키워드 색인이라 할 일이 없다).
 *
 * <p>임베딩 백로그 스윕과 따로 둔다 — 키워드는 임베딩 설정이 없는 테넌트에서도 필요하고, 임베딩 API 를 부르지 않아 싸다.
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class KeywordBacklogSweepScheduler {

  private final TenantScopedRunner tenantRunner;
  private final KeywordIndexService keywordIndex;
  private final VectorStoreFeatures features;
  private final boolean enabled;

  /** 활성 여부는 설정값으로 받는다(테스트는 끄고 직접 {@link #sweep()} 을 부른다 — 임베딩 백로그 스윕과 같은 규약). */
  public KeywordBacklogSweepScheduler(
      TenantScopedRunner tenantRunner,
      KeywordIndexService keywordIndex,
      VectorStoreFeatures features,
      @Value("${firehub.vector-store.keyword-sweep-enabled:true}") boolean enabled) {
    this.tenantRunner = tenantRunner;
    this.keywordIndex = keywordIndex;
    this.features = features;
    this.enabled = enabled;
  }

  /** 기본 5분 주기. 한 테넌트 실패는 {@link TenantScopedRunner} 가 격리한다. */
  @Scheduled(
      initialDelayString = "${firehub.vector-store.keyword-sweep-interval-ms:300000}",
      fixedDelayString = "${firehub.vector-store.keyword-sweep-interval-ms:300000}")
  public void sweep() {
    if (!enabled) return;
    // 저장소가 내려가 있으면 건너뛴다(WD-724) — 배치마다 제한 시간까지 기다리지 않게. 표시가 비어 있으니 복구 뒤 회차가 다시 쓴다.
    if (!features.available()) return;
    tenantRunner.forEachActiveTenant(
        tenantId -> {
          int written = keywordIndex.sweepCurrentTenant();
          if (written > 0) {
            log.info("키워드 백로그 다시 씀: tenant={}, rows={}", tenantId, written);
          }
        });
  }
}
