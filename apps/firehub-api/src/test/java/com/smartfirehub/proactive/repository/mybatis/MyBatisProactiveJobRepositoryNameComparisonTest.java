package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.naming.NameComparison;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * WD-1108·WD-1154 — MyBatis 구현이 매퍼가 문을 고르는 SqlSessionFactory 의 databaseId 로 개명 판정 기준을 고르는지
 * (postgresql 은 유니코드, tibero·null(미매핑) 은 공통 문과 같은 ASCII).
 */
class MyBatisProactiveJobRepositoryNameComparisonTest {

  @ParameterizedTest
  @CsvSource(
      value = {"postgresql, UNICODE_TRIM", "tibero, ASCII_TRIM", "null, ASCII_TRIM"},
      nullValues = "null")
  void nameComparison_followsFactoryDatabaseId(String databaseId, NameComparison expected) {
    SqlSessionFactory factory = mock(SqlSessionFactory.class);
    Configuration configuration = new Configuration();
    configuration.setDatabaseId(databaseId);
    when(factory.getConfiguration()).thenReturn(configuration);

    MyBatisProactiveJobRepository repo =
        new MyBatisProactiveJobRepository(
            mock(ProactiveJobMapper.class),
            JsonMapper.builderWithJackson2Defaults().build(),
            factory);

    assertThat(repo.nameComparison()).isEqualTo(expected);
  }
}
