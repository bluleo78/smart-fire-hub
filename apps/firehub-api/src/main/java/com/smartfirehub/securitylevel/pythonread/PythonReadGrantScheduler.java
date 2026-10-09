package com.smartfirehub.securitylevel.pythonread;

import com.smartfirehub.global.tenant.TenantScopedRunner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 드리프트 회복 — 기동 시 1회 + 하루 1회 ACTIVE 전 테넌트 syncTenant(스펙 §4.3, WD-29).
 *
 * <p>이벤트 유실·발행 누락·손으로 건 GRANT 같은 드리프트를 주기적으로 계산값에 되돌린다. 예외를 밖으로 던지지 않는다 — 기동을 막으면 안 되고, 한 테넌트의 실패가
 * 나머지를 멈추면 안 된다({@link TenantScopedRunner#forEachActiveTenant} 가 테넌트별 예외를 격리한다). 실행 직전 동기화(JIT)가
 * 있으므로 이 주기 작업이 실패해도 과권한 창은 열리지 않는다.
 */
@Slf4j
@Component
public class PythonReadGrantScheduler {

  private final PythonReadGrantSync sync;
  private final TenantScopedRunner tenantScopedRunner;
  private final boolean enabled;

  /**
   * @param enabled 테스트 프로필은 끈다 — 공유 테스트 DB 의 수백 테넌트를 컨텍스트 기동마다 훑지 않게. 기계장치(PythonReadGrantSync)는
   *     꺼지지 않는다.
   */
  public PythonReadGrantScheduler(
      PythonReadGrantSync sync,
      TenantScopedRunner tenantScopedRunner,
      @Value("${app.pipeline.python-read-sync.enabled:true}") boolean enabled) {
    this.sync = sync;
    this.tenantScopedRunner = tenantScopedRunner;
    this.enabled = enabled;
  }

  /** 기동 시 1회 — 배포 사이에 쌓인 드리프트(예: 이전 버전이 만든 테이블)를 맞춘다. */
  @EventListener(ApplicationReadyEvent.class)
  public void onStartup() {
    runAll("기동");
  }

  /** 하루 1회 — 이벤트 유실·수동 GRANT 드리프트 회복. */
  @Scheduled(cron = "${app.pipeline.python-read-sync.cron:0 20 3 * * *}")
  public void daily() {
    runAll("일 1회");
  }

  /** 전 ACTIVE 테넌트 동기화. 꺼져 있으면 아무것도 하지 않는다. */
  void runAll(String reason) {
    if (!enabled) {
      return;
    }
    try {
      // forEachActiveTenant 는 테넌트마다 TenantContext 를 세우고 테넌트별 예외를 삼키고 계속한다.
      tenantScopedRunner.forEachActiveTenant(tenantId -> sync.syncTenant());
      log.info("PYTHON 읽기 권한 동기화 완료({})", reason);
    } catch (RuntimeException e) {
      log.error("PYTHON 읽기 권한 전체 동기화 실패({}) — 다음 주기에 재시도", reason, e);
    }
  }
}
