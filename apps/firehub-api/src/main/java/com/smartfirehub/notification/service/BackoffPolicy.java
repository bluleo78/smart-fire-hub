package com.smartfirehub.notification.service;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 스펙 7장 backoff 정책.
 * attempt=1..5 재시도, 6번째부터는 exhausted → PERMANENT_FAILURE(UNRECOVERABLE).
 *
 * <p>지연 간격: 10s, 1m, 5m, 30m, 2h (지수 증가).
 */
@Component
public class BackoffPolicy {

    /** 재시도 attempt 번호(1..5) → 다음 시도까지 지연 시간. */
    private static final Duration[] DELAYS = {
            Duration.ofSeconds(10),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(30),
            Duration.ofHours(2)
    };

    /** attempt번째 재시도에 적용할 지연. attempt 범위 밖이면 IAE. */
    public Duration delayFor(int attempt) {
        if (attempt < 1 || attempt > DELAYS.length) {
            throw new IllegalArgumentException("attempt out of range: " + attempt);
        }
        return DELAYS[attempt - 1];
    }

    /** 재시도 소진 여부 판정. DELAYS.length(5)를 초과하면 영구 실패 처리. */
    public boolean exhausted(int attempt) {
        return attempt > DELAYS.length;
    }
}
