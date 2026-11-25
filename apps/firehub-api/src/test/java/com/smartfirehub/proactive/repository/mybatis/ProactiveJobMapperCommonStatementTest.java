package com.smartfirehub.proactive.repository.mybatis;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * WD-1154 — 스마트 작업 이름 중복 검사 {@code countByOwnerAndName} 에 공통 문(databaseId 없음)이 있어, Tibero 와 매핑되지 않은
 * DB(databaseId null)에서도 문이 풀리는지. DB 연결 없이 매퍼 XML 을 databaseId 별 Configuration 으로 읽는다.
 */
class ProactiveJobMapperCommonStatementTest {

  private static final String STATEMENT_ID =
      "com.smartfirehub.proactive.repository.mybatis.ProactiveJobMapper.countByOwnerAndName";

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

  @Test
  void nullDatabaseId_resolvesCommonStatementWithoutTranslate() {
    Configuration c = load(null);
    assertThat(c.hasStatement(STATEMENT_ID, false)).isTrue();
    assertThat(sql(c)).doesNotContain("translate");
  }

  @Test
  void postgresql_resolvesTranslateStatement() {
    Configuration c = load("postgresql");
    assertThat(c.hasStatement(STATEMENT_ID, false)).isTrue();
    assertThat(sql(c)).contains("translate");
  }

  // tibero 판 선택(공통 문)은 PlatformMapperLoadTest 가 실제 조립으로 본다.
}
