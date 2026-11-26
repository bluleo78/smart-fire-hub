package com.smartfirehub.global.naming;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** databaseId → 이름 비교 기준 매핑 단위 테스트(WD-1094·WD-1154). */
class NameComparisonTest {

  @Test
  void postgresql_는_UNICODE_TRIM() {
    assertThat(NameComparison.forDatabaseId("postgresql")).isEqualTo(NameComparison.UNICODE_TRIM);
  }

  @Test
  void tibero_null_그_밖은_ASCII_TRIM() {
    assertThat(NameComparison.forDatabaseId("tibero")).isEqualTo(NameComparison.ASCII_TRIM);
    assertThat(NameComparison.forDatabaseId(null)).isEqualTo(NameComparison.ASCII_TRIM);
    assertThat(NameComparison.forDatabaseId("other")).isEqualTo(NameComparison.ASCII_TRIM);
  }
}
