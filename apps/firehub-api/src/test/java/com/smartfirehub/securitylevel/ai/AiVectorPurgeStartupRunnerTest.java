package com.smartfirehub.securitylevel.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import com.smartfirehub.settings.repository.TenantSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 배포 시점 1회 정리의 플래그 규칙(보충 §2.1): 실패 없이 끝난 테넌트에만 플래그를 남긴다. 예외·부분 실패면 플래그가 없어 다음 기동에 다시 돈다. 스프링 컨텍스트
 * 없이 목으로 확인한다.
 */
class AiVectorPurgeStartupRunnerTest {

  private TenantSettingsRepository settings;
  private AiVectorPurgeService purge;
  private TenantScopedRunner tenantRunner;
  private AiVectorPurgeStartupRunner runner;

  @BeforeEach
  void setUp() {
    settings = mock(TenantSettingsRepository.class);
    purge = mock(AiVectorPurgeService.class);
    tenantRunner = mock(TenantScopedRunner.class);
    runner = new AiVectorPurgeStartupRunner(tenantRunner, settings, purge, true);
    when(settings.findValue(AiVectorPurgeStartupRunner.FLAG_KEY)).thenReturn(Optional.empty());
  }

  @Test
  void success_setsFlag() {
    when(purge.purgeDisallowed()).thenReturn(new AiVectorPurgeService.PurgeResult(1, 1, 0, 0, 0));
    runner.runForCurrentTenant();
    verify(settings).upsert(AiVectorPurgeStartupRunner.FLAG_KEY, "done", null);
  }

  @Test
  void purgeThrows_doesNotSetFlag() {
    when(purge.purgeDisallowed()).thenThrow(new IllegalStateException("db down"));
    assertThatThrownBy(() -> runner.runForCurrentTenant())
        .isInstanceOf(IllegalStateException.class);
    verify(settings, never()).upsert(anyString(), anyString(), any());
  }

  @Test
  void partialFailure_doesNotSetFlag() {
    when(purge.purgeDisallowed()).thenReturn(new AiVectorPurgeService.PurgeResult(2, 1, 0, 0, 1));
    runner.runForCurrentTenant();
    verify(settings, never()).upsert(anyString(), anyString(), any());
  }

  @Test
  void flagPresent_skipsPurge() {
    when(settings.findValue(AiVectorPurgeStartupRunner.FLAG_KEY)).thenReturn(Optional.of("done"));
    runner.runForCurrentTenant();
    verify(purge, never()).purgeDisallowed();
  }

  @Test
  void disabled_doesNotIterateTenants() {
    new AiVectorPurgeStartupRunner(tenantRunner, settings, purge, false).onReady();
    verify(tenantRunner, never()).forEachActiveTenant(any());
  }
}
