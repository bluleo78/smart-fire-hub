package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartfirehub.global.naming.NameComparison;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** WD-1108 — MyBatis 구현이 플랫폼 DB 설정에 따라 개명 판정 기준을 고르는지(PG 유니코드 / Tibero ASCII). */
class MyBatisProactiveJobRepositoryNameComparisonTest {

  private static MyBatisProactiveJobRepository repo(String platformDbType) {
    return new MyBatisProactiveJobRepository(
        mock(ProactiveJobMapper.class),
        JsonMapper.builderWithJackson2Defaults().build(),
        platformDbType);
  }

  @Test
  void postgres_usesUnicodeTrim() {
    assertThat(repo("postgres").nameComparison()).isEqualTo(NameComparison.UNICODE_TRIM);
  }

  @Test
  void tibero_usesAsciiTrim() {
    assertThat(repo("tibero").nameComparison()).isEqualTo(NameComparison.ASCII_TRIM);
  }
}
