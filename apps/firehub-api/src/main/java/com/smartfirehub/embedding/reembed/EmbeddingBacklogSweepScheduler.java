package com.smartfirehub.embedding.reembed;

import com.smartfirehub.embedding.config.EmbeddingConfigService;
import com.smartfirehub.global.tenant.TenantScopedRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재임베딩 백로그 스윕(level-trigger). 주기마다 모든 활성 테넌트에서 "할 일이 남았는데 도는 잡이 없으면" 재임베딩 잡을
 * 투입한다.
 *
 * <p><b>왜 필요한가.</b> 문서 적재·데이터셋 재색인은 시작할 때 provider 를 잡고 긴 임베딩 호출을 한다. 그 사이 설정이
 * 바뀌면 옛 공간 벡터가 늦게 써진다. 이때 재임베딩 잡은 이미 DONE 이라 아무도 다시 투입하지 않아 판정식은 참인 채
 * 영구 방치된다(검색 누락, 진행률 100% 미도달). 저장 시 투입·SUPERSEDED 재투입(edge-trigger)은 지연 단축용으로 두고,
 * 이 스윕이 최종 수렴을 보장한다.
 *
 * <p>미설정 테넌트, 그리고 마지막 잡이 FAILED 인데 그 뒤로 설정이 다시 저장되지 않은 테넌트는 건너뛴다(옮길 대상
 * 공간이 없거나, 다시 돌려도 같은 이유로 실패한다). 판정은 {@link EmbeddingBacklogService#hasWork} 한 규칙만 쓴다.
 */
@Slf4j
@Component
public class EmbeddingBacklogSweepScheduler {

  private final TenantScopedRunner tenantRunner;
  private final EmbeddingConfigService configService;
  private final EmbeddingBacklogService backlogService;
  private final EmbeddingReembedStateRepository stateRepository;
  private final TenantReembedJob reembedJob;
  private final boolean enabled;

  /** 활성 여부는 설정값으로 받는다(테스트는 끄고 직접 {@link #sweep()} 을 부른다 — 행 검색 스윕과 같은 규약). */
  public EmbeddingBacklogSweepScheduler(
      TenantScopedRunner tenantRunner,
      EmbeddingConfigService configService,
      EmbeddingBacklogService backlogService,
      EmbeddingReembedStateRepository stateRepository,
      TenantReembedJob reembedJob,
      @Value("${app.embedding.backlog-sweep-enabled:true}") boolean enabled) {
    this.tenantRunner = tenantRunner;
    this.configService = configService;
    this.backlogService = backlogService;
    this.stateRepository = stateRepository;
    this.reembedJob = reembedJob;
    this.enabled = enabled;
  }

  /** 기본 5분 주기. 한 테넌트 실패는 {@link TenantScopedRunner} 가 격리한다. */
  @Scheduled(
      initialDelayString = "${app.embedding.backlog-sweep-interval-ms:300000}",
      fixedDelayString = "${app.embedding.backlog-sweep-interval-ms:300000}")
  public void sweep() {
    if (!enabled) return;
    tenantRunner.forEachActiveTenant(this::sweepTenant);
  }

  /**
   * 한 테넌트: 설정됨 && 유효 임대 없음 && 할 일 있음 → 잡 투입. 임대 확인(1행 조회)을 COUNT 두 번인 판정식보다
   * 먼저 해 도는 중인 테넌트의 비용을 줄인다(조건은 AND 라 순서가 결과를 바꾸지 않는다).
   */
  private void sweepTenant(long tenantId) {
    configService
        .currentSpace()
        .ifPresent(
            space -> {
              if (stateRepository.hasActiveLease()) return; // 도는 잡이 끝에서 스스로 수렴·재투입한다
              // 결정적 실패(잘못된 키·가드 거부)는 설정이 바뀌기 전까지 다시 돌려도 같다 — 재투입하면 시간당 12잡 ×
              // JobRunr 재시도가 쌓인다. 설정을 다시 저장하면 이 조건이 풀린다(저장 시 투입도 따로 있다).
              if (stateRepository.isFailedSinceLastConfigSave()) return;
              if (!backlogService.hasWork(space)) return;
              log.info("재임베딩 백로그 발견 — 잡 재투입: tenant={}, space={}", tenantId, space);
              reembedJob.enqueue(tenantId);
            });
  }
}
