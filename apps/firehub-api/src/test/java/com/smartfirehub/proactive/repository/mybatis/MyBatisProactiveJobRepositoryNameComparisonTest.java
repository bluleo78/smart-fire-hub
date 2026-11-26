package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.naming.NameComparison;
import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.validation.TextRules;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * WD-1108·WD-1172 — MyBatis 스마트 작업 저장소의 이름 비교 기준과 중복 판정 경로. 개명 판정 기준은 databaseId 와 무관하게
 * UNICODE_TRIM 이고(비 PG 는 이름 목록을 읽어 Java 에서 같은 기준으로 비교한다), postgresql 만 기존 SQL
 * 문(countByOwnerAndName)을 쓴다.
 *
 * <p>WD-1165: databaseId null 이면 다른 매퍼의 PG/Tibero 전용 조각을 풀 수 없어 모든 매퍼 호출이 실패하지만, 저장소 생성자 자체는
 * databaseId 를 읽기만 하므로 여기서는 비교 기준 확인에 한해 null 도 넣는다.
 */
class MyBatisProactiveJobRepositoryNameComparisonTest {

  private final ProactiveJobMapper mapper = mock(ProactiveJobMapper.class);

  private MyBatisProactiveJobRepository repo(String databaseId) {
    SqlSessionFactory factory = mock(SqlSessionFactory.class);
    Configuration configuration = new Configuration();
    configuration.setDatabaseId(databaseId);
    when(factory.getConfiguration()).thenReturn(configuration);
    return new MyBatisProactiveJobRepository(
        mapper, JsonMapper.builderWithJackson2Defaults().build(), factory);
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {MyBatisPlatformConfig.POSTGRESQL, MyBatisPlatformConfig.TIBERO})
  @DisplayName("개명 판정 기준은 databaseId 와 무관하게 UNICODE_TRIM 이다")
  void nameComparison_isAlwaysUnicodeTrim(String databaseId) {
    assertThat(repo(databaseId).nameComparison()).isEqualTo(NameComparison.UNICODE_TRIM);
  }

  @Test
  @DisplayName("Tibero: 저장된 이름이 앞뒤 전각 공백만 다르면 중복이다")
  void tibero_matchesUnicodeEdgeSpace() {
    when(mapper.selectNamesByOwner(5L, null)).thenReturn(List.of("\u3000결제 API"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "결제 API", null))
        .isTrue();
  }

  @Test
  @DisplayName("Tibero: 안쪽 공백 종류만 다른 이름(NBSP)도 PG 처럼 같은 이름이라 중복이다 (WD-1172)")
  void tibero_matchesInnerNbsp() {
    when(mapper.selectNamesByOwner(5L, null)).thenReturn(List.of("결제\u00a0API"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "결제 API", null))
        .isTrue();
  }

  @Test
  @DisplayName("Tibero: 저장된 이름 끝의 U+001F 는 공백이 아니라 다른 이름이다")
  void tibero_controlCharIsNotSpace() {
    when(mapper.selectNamesByOwner(5L, null)).thenReturn(List.of("Sales\u001f"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "Sales", null))
        .isFalse();
  }

  @Test
  @DisplayName("Tibero: '?' 는 공백으로 보지 않는다")
  void tibero_questionMarkIsNotSpace() {
    when(mapper.selectNamesByOwner(5L, null)).thenReturn(List.of("결제 API?"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "결제 API", null))
        .isFalse();
  }

  @Test
  @DisplayName("Tibero: excludeId 를 목록 조회에 넘기고, null 이름·null 저장값은 NPE 없이 처리한다")
  void tibero_excludeIdAndNulls() {
    when(mapper.selectNamesByOwner(5L, 9L)).thenReturn(java.util.Arrays.asList(null, " 결제 API"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "결제 API", 9L)).isTrue();
    verify(mapper).selectNamesByOwner(5L, 9L);
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, null, null)).isFalse();
  }

  @Test
  @DisplayName("PostgreSQL: 기존 SQL(countByOwnerAndName)을 부르고 이름 목록은 읽지 않는다")
  void postgres_usesSqlCount() {
    when(mapper.countByOwnerAndName(
            5L, "a", null, TextRules.EDGE_SPACE_CHARS, TextRules.EDGE_SPACE_AS_ASCII))
        .thenReturn(1L);
    assertThat(repo(MyBatisPlatformConfig.POSTGRESQL).existsByOwnerAndName(5L, "a", null)).isTrue();
    verify(mapper, never()).selectNamesByOwner(anyLong(), any());
  }
}
