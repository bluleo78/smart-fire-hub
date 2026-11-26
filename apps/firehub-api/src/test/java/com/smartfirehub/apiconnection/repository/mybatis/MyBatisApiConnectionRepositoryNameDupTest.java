package com.smartfirehub.apiconnection.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.validation.TextRules;
import java.util.List;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 비 PG(Tibero) MyBatis API 연결 저장소의 이름 중복 판정 단위 시험(WD-1172). 매퍼는 mock — 이름 목록을 읽어 Java 에서 PG 와 같은
 * UNICODE_TRIM 기준으로 비교하는지 본다.
 */
class MyBatisApiConnectionRepositoryNameDupTest {

  private final ApiConnectionMapper mapper = mock(ApiConnectionMapper.class);

  private MyBatisApiConnectionRepository repo(String databaseId) {
    SqlSessionFactory factory = mock(SqlSessionFactory.class);
    Configuration configuration = new Configuration();
    configuration.setDatabaseId(databaseId);
    when(factory.getConfiguration()).thenReturn(configuration);
    return new MyBatisApiConnectionRepository(mapper, factory);
  }

  private boolean existsWithStored(String stored, String query) {
    when(mapper.selectNamesExcludingId(null)).thenReturn(List.of(stored));
    return repo(MyBatisPlatformConfig.TIBERO).existsByName(query);
  }

  @Test
  @DisplayName("Tibero: 앞뒤 ASCII·NBSP·전각 공백만 다른 이름은 같은 이름이다")
  void edgeSpaces_areIgnored() {
    assertThat(existsWithStored("\u0020결제 API", "결제 API")).isTrue();
    assertThat(existsWithStored("\u00a0결제 API", "결제 API")).isTrue();
    assertThat(existsWithStored("\u3000결제 API\u3000", "결제 API")).isTrue();
  }

  @Test
  @DisplayName("Tibero: 안쪽 공백 종류만 다른 이름은 같은 이름이다")
  void innerSpaceKind_isIgnored() {
    assertThat(existsWithStored("결제\u00a0API", "결제 API")).isTrue();
  }

  @Test
  @DisplayName("Tibero: '?' 는 공백으로 보지 않는다")
  void questionMark_isNotSpace() {
    assertThat(existsWithStored("결제 API?", "결제 API")).isFalse();
    assertThat(existsWithStored("결제?API", "결제 API")).isFalse();
  }

  @Test
  @DisplayName("Tibero: U+001F 제어 문자는 걷지 않는다")
  void controlChar_isNotTrimmed() {
    assertThat(existsWithStored("Sales\u001f", "Sales")).isFalse();
  }

  @Test
  @DisplayName("Tibero: 대소문자는 무시하고, null 저장값·null 질의는 NPE 없이 처리한다")
  void caseAndNulls() {
    assertThat(existsWithStored("SALES", "sales")).isTrue();
    when(mapper.selectNamesExcludingId(null)).thenReturn(java.util.Arrays.asList(null, "x"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByName("sales")).isFalse();
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByName(null)).isFalse();
  }

  @Test
  @DisplayName("Tibero: existsByNameExcludingId 는 excludeId 로 목록을 읽어 판정한다")
  void excludingId_readsNamesWithExcludeId() {
    when(mapper.selectNamesExcludingId(7L)).thenReturn(List.of(" 결제 API"));
    assertThat(repo(MyBatisPlatformConfig.TIBERO).existsByNameExcludingId("결제 API", 7L)).isTrue();
    verify(mapper).selectNamesExcludingId(7L);
  }

  @Test
  @DisplayName("PostgreSQL: 기존 SQL(countByName)을 부르고 이름 목록은 읽지 않는다")
  void postgres_usesSqlCount() {
    when(mapper.countByName("a", TextRules.EDGE_SPACE_CHARS, TextRules.EDGE_SPACE_AS_ASCII))
        .thenReturn(1L);
    assertThat(repo(MyBatisPlatformConfig.POSTGRESQL).existsByName("a")).isTrue();
    verify(mapper, never()).selectNamesExcludingId(any());
    verify(mapper, never()).countByNameExcludingId(anyString(), any(), anyString(), anyString());
  }
}
