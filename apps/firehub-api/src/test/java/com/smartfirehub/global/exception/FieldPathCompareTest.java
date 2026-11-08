package com.smartfirehub.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 필드 경로 자연 정렬 비교(ValidationErrorCollector.compareFieldPath, WD-654) — BigInteger 없이 비교해도
 * 예전(BigInteger)과 같은 순서인지 확인한다.
 *
 * <p>왜: 숫자 맵 키는 클라이언트가 길이 제한 없이 보낼 수 있는데, 예전에는 비교할 때마다 숫자 묶음을 BigInteger 로 새로 읽어 오류 응답 경로에서 CPU 를
 * 썼다. 이제 앞자리 0 을 떼고 → 자릿수 → 자리별 순으로 비교한다. 순서 자체는 바뀌면 안 되므로 예전 구현을 기준값(oracle)으로 둔다.
 */
class FieldPathCompareTest {

  /** 예전(WD-640) 구현 그대로 — 숫자 묶음을 BigInteger 로 읽어 비교. 순서 기준값. */
  private static int oracle(String a, String b) {
    int i = 0;
    int j = 0;
    while (i < a.length() && j < b.length()) {
      if (Character.isDigit(a.charAt(i)) && Character.isDigit(b.charAt(j))) {
        int ei = i;
        while (ei < a.length() && Character.isDigit(a.charAt(ei))) {
          ei++;
        }
        int ej = j;
        while (ej < b.length() && Character.isDigit(b.charAt(ej))) {
          ej++;
        }
        int cmp = new BigInteger(a.substring(i, ei)).compareTo(new BigInteger(b.substring(j, ej)));
        if (cmp != 0) {
          return cmp;
        }
        i = ei;
        j = ej;
      } else {
        if (a.charAt(i) != b.charAt(j)) {
          return Character.compare(a.charAt(i), b.charAt(j));
        }
        i++;
        j++;
      }
    }
    return Integer.compare(a.length() - i, b.length() - j);
  }

  private static int compare(String a, String b) {
    return ValidationErrorCollector.compareFieldPath(a, b);
  }

  /** 아주 긴 숫자 키(1만 자리 이상)와 앞자리 0 — 수 크기로 비교한다. */
  @Test
  void veryLongNumericKeysAndLeadingZeros() {
    String big = "9".repeat(10_000);
    String bigger = "1" + "0".repeat(10_000);
    assertThat(compare("m[" + big + "]", "m[" + bigger + "]")).isNegative();
    assertThat(compare("m[" + bigger + "]", "m[" + big + "]")).isPositive();
    // 앞자리 0 이 아무리 많아도 수는 같다 → 숫자 묶음은 같고, 남은 길이로 정한다(둘 다 끝이면 0).
    assertThat(compare("m[" + "0".repeat(20_000) + "7]", "m[7]")).isZero();
    assertThat(compare("m[" + "0".repeat(20_000) + "8]", "m[7]")).isPositive();
    assertThat(compare("m[" + "0".repeat(20_000) + "]", "m[0]")).isZero();
    assertThat(compare("rows[2]", "rows[10]")).isNegative();
    assertThat(compare("a01", "a1")).isZero();
    assertThat(compare("a01b", "a1c")).isNegative();
  }

  /** 무작위 경로 쌍에서 부호가 예전 구현과 같다 — 앞자리 0, 0 만 있는 묶음, 긴 숫자, 비 ASCII 숫자(아라비아-인도 숫자)를 섞는다. */
  @Test
  void matchesBigIntegerOracle() {
    String[] parts = {
      "a", "b", ".", "[", "]", "0", "1", "9", "00", "007", "٣", "٠", "12345678901234567890"
    };
    Random random = new Random(654);
    for (int n = 0; n < 20_000; n++) {
      String a = randomPath(random, parts);
      String b = randomPath(random, parts);
      assertThat(Integer.signum(compare(a, b)))
          .as("%s vs %s", a, b)
          .isEqualTo(Integer.signum(oracle(a, b)));
    }
  }

  private static String randomPath(Random random, String[] parts) {
    StringBuilder sb = new StringBuilder();
    int len = random.nextInt(8);
    for (int k = 0; k < len; k++) {
      sb.append(parts[random.nextInt(parts.length)]);
    }
    return sb.toString();
  }
}
