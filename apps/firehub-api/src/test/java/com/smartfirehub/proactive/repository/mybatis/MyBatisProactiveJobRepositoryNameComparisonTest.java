package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.naming.NameComparison;
import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * WD-1108·WD-1154 — MyBatis 구현이 매퍼가 문을 고르는 SqlSessionFactory 의 databaseId 로 개명 판정 기준을 고르는지
 * (postgresql 은 유니코드, tibero 는 공통 문과 같은 ASCII).
 *
 * <p>WD-1165: databaseId null 이면 다른 매퍼의 PG/Tibero 전용 조각(sqlFalse 등)을 풀 수 없어 첫 문장 조회부터 모든 매퍼 호출이 실패하므로
 * (조립은 지나가도 쓸 수 없는 설치본) 이 경우는 보지 않는다.
 */
class MyBatisProactiveJobRepositoryNameComparisonTest {

  @ParameterizedTest
  @CsvSource({
    MyBatisPlatformConfig.POSTGRESQL + ", UNICODE_TRIM",
    MyBatisPlatformConfig.TIBERO + ", ASCII_TRIM"
  })
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
