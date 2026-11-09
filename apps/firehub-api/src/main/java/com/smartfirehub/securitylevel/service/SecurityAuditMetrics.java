package com.smartfirehub.securitylevel.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 보안 감사 비동기 기록의 관측 메트릭(WD-687). {@link SecurityAuditRecorder} 의 큐 포화 정책이 버리거나 넘친 건수를 보이게 한다 — 예전에는
 * WARN 로그의 누적 건수로만 알 수 있었다. 이름은 다른 메트릭(NotificationMetrics 등)과 같은 snake_case {@code _total} 규칙을
 * 따른다.
 *
 * <ul>
 *   <li>{@code security_audit_dropped_total{kind=access|denial}} — 버린 기록(소프트·하드 상한, 종료 중 거절, 종료 시
 *       비우지 못함)
 *   <li>{@code security_audit_overflow_total} — 소프트 상한을 넘어 받아들인 거부 기록(버리지 않음)
 * </ul>
 */
@Component
public class SecurityAuditMetrics {

  /** 버린 기록 카운터 이름(태그 kind). */
  static final String DROPPED = "security_audit_dropped_total";

  /** 소프트 상한을 넘어 받아들인 거부 기록 카운터 이름. */
  static final String OVERFLOW = "security_audit_overflow_total";

  private final Map<SecurityAuditRecorder.Kind, Counter> dropped =
      new EnumMap<>(SecurityAuditRecorder.Kind.class);
  private final Counter overflow;

  public SecurityAuditMetrics(MeterRegistry registry) {
    for (SecurityAuditRecorder.Kind k : SecurityAuditRecorder.Kind.values()) {
      dropped.put(
          k,
          Counter.builder(DROPPED)
              .description("큐 포화·종료로 버린 보안 감사 기록 수")
              .tag("kind", k.tag)
              .register(registry));
    }
    this.overflow =
        Counter.builder(OVERFLOW)
            .description("대기 소프트 상한을 넘어 받아들인 거부 감사 기록 수(버리지 않음)")
            .register(registry);
  }

  /** 기록 하나를 버렸다. */
  void dropped(SecurityAuditRecorder.Kind kind) {
    dropped.get(kind).increment();
  }

  /** 거부 기록 하나를 소프트 상한을 넘어 받아들였다. */
  void overflowed() {
    overflow.increment();
  }

  /** 버린 기록 누적 수(종류 합) — WARN 로그용. */
  long totalDropped() {
    return (long) dropped.values().stream().mapToDouble(Counter::count).sum();
  }

  /** 넘침 누적 수 — WARN 로그용. */
  long overflowTotal() {
    return (long) overflow.count();
  }
}
