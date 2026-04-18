package com.smartfirehub.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BackoffPolicy — 재시도 간격 + 소진 판정 단위 검증. */
class BackoffPolicyTest {

    private final BackoffPolicy policy = new BackoffPolicy();

    /** 각 attempt 번호에 기대되는 지연(초)을 스펙 7장 표에 맞춰 확인. */
    @ParameterizedTest
    @CsvSource({
            "1, 10",        // 첫 transient 실패 후 다음 attempt까지 10초
            "2, 60",        // 1분
            "3, 300",       // 5분
            "4, 1800",      // 30분
            "5, 7200"       // 2시간
    })
    void delaysMatchSpec(int attempt, long expectedSeconds) {
        assertThat(policy.delayFor(attempt).getSeconds()).isEqualTo(expectedSeconds);
    }

    @Test
    void exhaustedAfterFiveAttempts() {
        assertThat(policy.exhausted(5)).isFalse();
        assertThat(policy.exhausted(6)).isTrue();
    }

    @Test
    void delayForAttemptZeroThrows() {
        assertThatThrownBy(() -> policy.delayFor(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delayForAttemptSixThrows() {
        assertThatThrownBy(() -> policy.delayFor(6))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
