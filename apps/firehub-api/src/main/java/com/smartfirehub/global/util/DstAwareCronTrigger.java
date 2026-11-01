package com.smartfirehub.global.util;

import java.time.Instant;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;

/**
 * {@link DstAwareCron} 규칙으로 다음 발화 시각을 정하는 Spring 스케줄러 {@link Trigger} (WD-391).
 *
 * <p>Spring {@code CronTrigger} 대신 파이프라인 트리거·스마트 작업 스케줄러가 쓴다. 미리보기 API 와 같은 계산기를 쓰므로 편집 창 「다음 5회」와
 * 실제 실행 시각이 서머타임 전환일에도 같다.
 *
 * <p>기준 시각 선택은 Spring {@code CronTrigger} 와 같다 — 직전 실행이 끝난 시각을 쓰되, 그것이 예정 시각보다 이르면(시계가 일찍 깨운 경우) 예정
 * 시각을 쓰고, 첫 실행이면 지금 시각을 쓴다.
 */
public final class DstAwareCronTrigger implements Trigger {

  private final DstAwareCron cron;

  DstAwareCronTrigger(DstAwareCron cron) {
    this.cron = cron;
  }

  /** 이 트리거가 쓰는 계산기. */
  public DstAwareCron cron() {
    return cron;
  }

  @Override
  public Instant nextExecution(TriggerContext triggerContext) {
    Instant timestamp = triggerContext.lastCompletion();
    if (timestamp != null) {
      Instant scheduled = triggerContext.lastScheduledExecution();
      if (scheduled != null && timestamp.isBefore(scheduled)) {
        // 직전 작업이 예정보다 일찍 끝난 것처럼 보이면(시계 오차) 예정 시각을 기준으로 해 같은 시각에 두 번 돌지 않게 한다
        timestamp = scheduled;
      }
    } else {
      timestamp = triggerContext.getClock().instant();
    }
    return cron.next(timestamp);
  }

  @Override
  public String toString() {
    return "DstAwareCronTrigger: " + cron;
  }
}
