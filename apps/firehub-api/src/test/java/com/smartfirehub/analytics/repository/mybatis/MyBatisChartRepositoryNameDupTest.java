package com.smartfirehub.analytics.repository.mybatis;

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
import java.util.Arrays;
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
 * 비 PG(Tibero) MyBatis 차트 저장소의 이름 중복 판정 단위 시험(WD-1181). 매퍼는 mock — 소유자의 이름 목록을 읽어 Java 에서 PG 와 같은
 * UNICODE_TRIM 기준으로 비교하는지 본다.
 */
class MyBatisChartRepositoryNameDupTest {

  private final ChartMapper mapper = mock(ChartMapper.class);

  private MyBatisChartRepository repo(String databaseId) {
    SqlSessionFactory factory = mock(SqlSessionFactory.class);
    Configuration configuration = new Configuration();
    configuration.setDatabaseId(databaseId);
    when(factory.getConfiguration()).thenReturn(configuration);
    return new MyBatisChartRepository(
        mapper, JsonMapper.builderWithJackson2Defaults().build(), factory);
  }

  private boolean existsWithStored(String stored, String query) {
    when(mapper.selectNamesByOwner(5L, null)).thenReturn(List.of(stored));
    return repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, query, null);
  }

  @Test
  @DisplayName("Tibero: 앞뒤 ASCII·NBSP·전각 공백만 다른 이름은 같은 이름이다")
  void edgeSpaces_areIgnored() {
    assertThat(existsWithStored(" 결제 현황", "결제 현황")).isTrue();
    assertThat(existsWithStored("\u00a0결제 현황", "결제 현황")).isTrue();
    assertThat(existsWithStored("\u3000결제 현황\u3000", "결제 현황")).isTrue();
    assertThat(existsWithStored("결제 현황", "\u3000결제 현황\u00a0")).isTrue();
  }

  @Test
  @DisplayName("Tibero: 안쪽 공백 종류만 다른 이름은 같은 이름이다")
  void innerSpaceKind_isIgnored() {
    assertThat(existsWithStored("결제\u00a0현황", "결제 현황")).isTrue();
  }

  @Test
  @DisplayName("Tibero: 대소문자는 무시한다")
  void case_isIgnored() {
    assertThat(existsWithStored("Sales Board", "sales BOARD")).isTrue();
  }

  @Test
  @DisplayName("Tibero: '?' 와 U+001F 는 공백으로 보지 않는다")
  void questionMarkAndControlChar_areNotSpace() {
    assertThat(existsWithStored("결제 현황?", "결제 현황")).isFalse();
    assertThat(existsWithStored("결제?현황", "결제 현황")).isFalse();
    assertThat(existsWithStored("Sales\u001f", "Sales")).isFalse();
  }

  @Test
  @DisplayName("Tibero: excludeId 를 목록 조회에 넘기고, null 이름·null 저장값은 NPE 없이 처리한다")
  void excludeIdAndNulls() {
    when(mapper.selectNamesByOwner(5L, 9L)).thenReturn(Arrays.asList(null, " 결제 현황"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, "결제 현황", 9L)).isTrue();
    verify(mapper).selectNamesByOwner(5L, 9L);
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByOwnerAndName(5L, null, null)).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {MyBatisPlatformConfig.POSTGRESQL, MyBatisPlatformConfig.TIBERO})
  @DisplayName("개명 판정 기준은 databaseId 와 무관하게 UNICODE_TRIM 이다")
  void nameComparison_isAlwaysUnicodeTrim(String databaseId) {
    assertThat(repo(databaseId).nameComparison()).isEqualTo(NameComparison.UNICODE_TRIM);
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
