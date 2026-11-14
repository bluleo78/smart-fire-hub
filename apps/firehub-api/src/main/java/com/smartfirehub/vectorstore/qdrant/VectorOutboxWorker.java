package com.smartfirehub.vectorstore.qdrant;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.vectorstore.ConditionalOnQdrantVectorStore;
import com.smartfirehub.vectorstore.VectorOutboxRepository;
import com.smartfirehub.vectorstore.VectorOutboxRepository.Entry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 벡터 아웃박스 워커(WD-721, ADR 0002 결정 6). 주기마다 활성 테넌트의 아웃박스 행을 순번 순서로 Qdrant 에 반영하고, 반영한 행만 지운다.
 *
 * <p>왜 폴링만 하나: 기존 {@code OutboxWakeupChannel} 은 알림 전용 고정 채널이라 쓰면 알림 워커가 깨어난다. 삭제가 몇 초 늦어도 사후 확인(결정
 * 5)이 지워졌거나 불허된 데이터셋을 결과에서 거르므로 폴링으로 충분하다.
 *
 * <p>잠금 없이 돈다 — 삭제는 멱등이라 파드 둘이 같은 행을 반영해도 결과가 같다. 반영에 실패하면 시도 수와 오류를 남기고 그 테넌트의 이번 회차를 멈춘다 (저장소가
 * 내려가 있으면 나머지 행도 실패하므로 매번 전부 두드리지 않는다). 비활성 테넌트의 행은 남는다(결정 6, 감수).
 */
@Slf4j
@Component
@ConditionalOnQdrantVectorStore
public class VectorOutboxWorker {

  /** 한 번에 읽는 행 수. 다 차면 같은 회차에 다음 묶음을 읽는다. */
  static final int BATCH = 100;

  private final TenantScopedRunner tenantRunner;
  private final VectorOutboxRepository outbox;
  private final QdrantOutboxApplier applier;
  private final boolean enabled;

  /** 활성 여부는 설정값으로 받는다(테스트는 끄고 {@link #drainCurrentTenant()} 를 직접 부른다). */
  public VectorOutboxWorker(
      TenantScopedRunner tenantRunner,
      VectorOutboxRepository outbox,
      QdrantOutboxApplier applier,
      @Value("${firehub.vector-store.outbox-worker-enabled:true}") boolean enabled) {
    this.tenantRunner = tenantRunner;
    this.outbox = outbox;
    this.applier = applier;
    this.enabled = enabled;
  }

  /** 기본 5초 주기. 한 테넌트의 예외는 {@link TenantScopedRunner} 가 격리한다. */
  @Scheduled(
      initialDelayString = "${firehub.vector-store.outbox-poll-interval-ms:5000}",
      fixedDelayString = "${firehub.vector-store.outbox-poll-interval-ms:5000}")
  public void poll() {
    if (!enabled) return;
    tenantRunner.forEachActiveTenant(tenantId -> drainCurrentTenant());
  }

  /**
   * 현재 테넌트(TenantContext)의 행을 반영한다.
   *
   * @return 반영해 지운 행 수
   */
  public int drainCurrentTenant() {
    int done = 0;
    while (true) {
      List<Entry> batch = outbox.findPending(BATCH);
      for (Entry e : batch) {
        try {
          applier.apply(e);
        } catch (RuntimeException ex) {
          outbox.markFailed(e.seq(), ex.getMessage());
          log.warn(
              "벡터 아웃박스 반영 실패 — 다음 회차에 다시 한다: tenant={}, seq={}, kind={}, attempts={}",
              e.tenantId(),
              e.seq(),
              e.kind(),
              e.attempts() + 1,
              ex);
          return done;
        }
        outbox.delete(e.seq());
        done++;
      }
      if (batch.size() < BATCH) return done;
    }
  }
}
