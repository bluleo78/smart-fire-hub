package com.smartfirehub.global.platformdb.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** PlatformRetries 의 「중복 키면 1회 재시도」 규칙을 DB 없이 고정한다(WD-711). */
class PlatformRetriesTest {

  @Test
  @DisplayName("첫 시도가 성공하면 다시 돌지 않고 결과를 돌려준다")
  void noRetryOnSuccess() {
    AtomicInteger calls = new AtomicInteger();

    assertThat(PlatformRetries.onceOnDuplicateKey(() -> calls.incrementAndGet() * 10))
        .isEqualTo(10);
    assertThat(calls).hasValue(1);
  }

  @Test
  @DisplayName("첫 시도가 중복 키면 한 번만 다시 돌아 두 번째 결과를 돌려준다")
  void retriesOnceOnDuplicateKey() {
    AtomicInteger calls = new AtomicInteger();

    int out =
        PlatformRetries.onceOnDuplicateKey(
            () -> {
              if (calls.incrementAndGet() == 1) {
                throw new DuplicateKeyException("dup");
              }
              return 0;
            });

    assertThat(out).isZero();
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("두 번 다 중복 키면 세 번째는 없고 예외가 그대로 올라간다")
  void propagatesWhenRetryAlsoFails() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                PlatformRetries.runOnceOnDuplicateKey(
                    () -> {
                      calls.incrementAndGet();
                      throw new DuplicateKeyException("dup");
                    }))
        .isInstanceOf(DuplicateKeyException.class);
    assertThat(calls).hasValue(2);
  }

  @Test
  @DisplayName("중복 키가 아닌 무결성 위반은 다시 시도하지 않는다")
  void doesNotRetryOtherIntegrityViolations() {
    AtomicInteger calls = new AtomicInteger();

    assertThatThrownBy(
            () ->
                PlatformRetries.runOnceOnDuplicateKey(
                    () -> {
                      calls.incrementAndGet();
                      throw new DataIntegrityViolationException("check");
                    }))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(calls).hasValue(1);
  }
}
