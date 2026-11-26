package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/**
 * WD-1154·WD-1172 — 스마트 작업 이름 목록 조회 {@code selectNamesByOwner} 에 공통 문(databaseId 없음)이 있어, Tibero 와 매핑되지 않은
 * DB(databaseId null)에서도 문이 풀리는지(tibero 판 선택은 WD-1165 부터 여기서 직접 본다). DB 연결 없이 매퍼 XML 을 databaseId 별 Configuration 으로 읽는다.
 */
class ProactiveJobMapperCommonStatementTest {

  private static final String NS =
      "com.smartfirehub.proactive.repository.mybatis.ProactiveJobMapper.";

  /** 공통 문(WD-1172) — 비 PG 이름 목록 조회. */
  private static final String STATEMENT_ID = NS + "selectNamesByOwner";

  /** PG 전용 문 — 다른 databaseId 에서는 풀리지 않아야 한다. */
  private static final String COUNT_ID = NS + "countByOwnerAndName";

  /**
   * 운영 {@code ProactiveJobMapper.xml} 을 지정한 databaseId 로 읽은 Configuration 을 만든다.
   *
   * <p>MyBatisPlatformConfig 의 전체 조립을 거치지 않는 이유: databaseId 가 null 이면 다른 매퍼의 PG 전용 조각(sqlFalse 등)을
   * 풀 수 없어 전체 SqlSessionFactory 를 만들 수 없다. 그래서 XMLMapperBuilder 로 이 XML 만 읽어 문 선택(공통 문 vs
   * postgresql 문)만 확인한다.
   */
  private static Configuration load(String databaseId) {
    try {
      Configuration c = new Configuration();
      c.setDatabaseId(databaseId);
      String path = "mybatis/proactive/ProactiveJobMapper.xml";
      try (InputStream in = new ClassPathResource(path).getInputStream()) {
        new XMLMapperBuilder(in, c, path, c.getSqlFragments()).parse();
      }
      return c;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** 문의 실제 SQL 을 뽑는다 — 동적 요소(excludeId)는 null 로 두어 기본 분기만 본다. */
  private static String sql(Configuration c) {
    Map<String, Object> params = new HashMap<>();
    params.put("ownerId", 1L);
    params.put("name", "n");
    params.put("excludeId", null);
    params.put("ws", "w");
    params.put("sp", "s");
    return c.getMappedStatement(STATEMENT_ID, false).getBoundSql(params).getSql().toLowerCase();
  }

  /**
   * 공통 문(databaseId 없음)을 쓰는 경우 — 매핑되지 않은 DB(null, WD-1154)와 tibero(WD-1165) 모두 translate 없는 문으로 풀린다.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = MyBatisPlatformConfig.TIBERO)
  void commonStatementWithoutTranslate(String databaseId) {
    Configuration c = load(databaseId);
    assertThat(c.hasStatement(STATEMENT_ID, false)).isTrue();
    assertThat(sql(c)).doesNotContain("translate");
  }

  /** WD-1172: 이름 비교 SQL(countByOwnerAndName)은 PG 에만 있고 tibero·null 에서는 풀리지 않는다. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = MyBatisPlatformConfig.TIBERO)
  void countStatement_isPostgresqlOnly(String databaseId) {
    assertThat(load(databaseId).hasStatement(COUNT_ID, false)).isFalse();
  }

  @Test
  void postgresql_resolvesTranslateStatement() {
    Configuration c = load(MyBatisPlatformConfig.POSTGRESQL);
    assertThat(c.hasStatement(COUNT_ID, false)).isTrue();
    assertThat(
            c.getMappedStatement(COUNT_ID, false)
                .getBoundSql(Map.of("ownerId", 1L, "name", "n", "ws", "w", "sp", "s"))
                .getSql()
                .toLowerCase())
        .contains("translate");
  }
}
