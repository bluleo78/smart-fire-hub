package com.smartfirehub.securitylevel.ai;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 배포 시점 1회 정리(보충 §2.1). 배포 직후 모든 호스팅이 기본 EXTERNAL 이라 민감·기밀 데이터셋에 이미 정책 위반 벡터가 있다 — 이벤트 기반 트리거로는 잡히지
 * 않으므로 기동 시 테넌트별로 한 번 돈다.
 *
 * <p>정리가 <b>실패 없이</b> 끝난 테넌트에만 tenant_settings 플래그를 남긴다. 예외가 나거나 행 검색 색인 정리 실패가 하나라도 있으면 플래그가 없어 다음
 * 기동에 다시 돈다(정리는 멱등).
 *
 * <p>플래그 키는 "security." 네임스페이스라 범용 설정 PUT 으로 쓸 수 없다(SettingsOverridePolicy 의 테넌트 쓰기 허용 목록 밖).
 */
@Slf4j
@Component
public class AiVectorPurgeStartupRunner {

  /** 테넌트별 완료 플래그 키. 정리 규칙이 바뀌어 다시 돌려야 하면 버전 접미사를 올린다. */
  public static final String FLAG_KEY = "security.ai_vector_purge_v1";

  private final TenantScopedRunner tenantRunner;
  private final TenantSettingsRepository tenantSettings;
  private final AiVectorPurgeService purgeService;
  private final boolean enabled;

  public AiVectorPurgeStartupRunner(
      TenantScopedRunner tenantRunner,
      TenantSettingsRepository tenantSettings,
      AiVectorPurgeService purgeService,
      @Value("${security.ai-vector-purge.startup-enabled:true}") boolean enabled) {
    this.tenantRunner = tenantRunner;
    this.tenantSettings = tenantSettings;
    this.purgeService = purgeService;
    this.enabled = enabled;
  }

  /** 기동 완료 후 백그라운드에서 — 기동 시간을 늘리지 않는다. 테넌트 하나의 실패는 나머지를 막지 않는다(TenantScopedRunner). */
  @Async("indexExecutor")
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!enabled) {
      return;
    }
    tenantRunner.forEachActiveTenant(tenantId -> runForCurrentTenant());
  }

  /**
   * 현재 테넌트에서 플래그가 없을 때만 정리하고, 실패 없이 끝났을 때만 플래그를 남긴다. 예외는 그대로 던진다(TenantScopedRunner 가 로그 후 다음
   * 테넌트로). 테스트가 직접 부른다.
   */
  public void runForCurrentTenant() {
    if (tenantSettings.findValue(FLAG_KEY).isPresent()) {
      return;
    }
    AiVectorPurgeService.PurgeResult result = purgeService.purgeDisallowed();
    if (result.failures() > 0) {
      log.warn("배포 시점 외부 벡터 정리 일부 실패 — 플래그를 남기지 않아 다음 기동에 다시 돈다: {}", result);
      return;
    }
    tenantSettings.upsert(FLAG_KEY, "done", null);
    log.info("배포 시점 외부 벡터 정리 완료: {}", result);
  }
}
