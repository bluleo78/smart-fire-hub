package com.smartfirehub.dbexecutor.sql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

/**
 * INTEGER 변환이 값을 조용히 바꾸지 않는지 고정한다(WD-78 리뷰 후속).
 *
 * <p>Jackson 은 64비트를 넘는 JSON 정수를 BigInteger, 소수를 Double/BigDecimal 로 읽는다. {@code longValue()} 로 바꾸면
 * 다른 숫자가 저장되므로(fail-open) 정확히 long 으로 표현되지 않는 값은 InvalidValue 로 요청 전체를 실패시킨다.
 */
class LoadValuesIntegerTest {

  @Test
  void integer_withinLongRange_keepsValue() {
    assertThat(LoadValues.coerce(42, "INTEGER")).isEqualTo(42L);
    assertThat(LoadValues.coerce(Long.MAX_VALUE, "INTEGER")).isEqualTo(Long.MAX_VALUE);
    assertThat(LoadValues.coerce("  -7 ", "INTEGER")).isEqualTo(-7L);
  }

  @Test
  void integer_wholeDecimal_isAccepted() {
    // 3.0 처럼 소수부가 0 인 값은 같은 정수라 받는다(Python float 출력).
    assertThat(LoadValues.coerce(3.0d, "INTEGER")).isEqualTo(3L);
  }

  @Test
  void integer_beyond64Bits_failsClosed() {
    BigInteger big = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
    assertThatThrownBy(() -> LoadValues.coerce(big, "INTEGER"))
        .isInstanceOf(LoadValues.InvalidValue.class);
  }

  @Test
  void integer_fraction_failsClosed() {
    assertThatThrownBy(() -> LoadValues.coerce(3.7d, "INTEGER"))
        .isInstanceOf(LoadValues.InvalidValue.class);
    assertThatThrownBy(() -> LoadValues.coerce(new BigDecimal("1.5"), "INTEGER"))
        .isInstanceOf(LoadValues.InvalidValue.class);
  }
}
