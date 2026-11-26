package com.smartfirehub.global.naming;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;

/**
 * WD-1181 — 리포트 양식·저장 쿼리·대시보드·차트의 비 PG 이름 목록 조회 {@code selectNamesByOwner} 가 공통 문(databaseId 없음)으로
 * tibero·null 모두에서 풀리고 유니코드 translate 를 쓰지 않으며, 이름 비교 SQL({@code countByOwnerAndName})은 PG 에만 남았는지
 * 본다. DB 연결 없이 매퍼 XML 을 databaseId 별 Configuration 으로 읽는다.
 */
class NameListStatementTest {

  private static Configuration load(String path, String databaseId) {
    try {
      Configuration c = new Configuration();
      c.setDatabaseId(databaseId);
      try (InputStream in = new ClassPathResource(path).getInputStream()) {
        new XMLMapperBuilder(in, c, path, c.getSqlFragments()).parse();
      }
      return c;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String sql(Configuration c, String id) {
    Map<String, Object> params = new HashMap<>();
    params.put("ownerId", 1L);
    params.put("excludeId", null);
    return c.getMappedStatement(id, false).getBoundSql(params).getSql().toLowerCase();
  }

  @ParameterizedTest
  @CsvSource({
    "mybatis/proactive/ReportTemplateMapper.xml,com.smartfirehub.proactive.repository.mybatis.ReportTemplateMapper",
    "mybatis/analytics/SavedQueryMapper.xml,com.smartfirehub.analytics.repository.mybatis.SavedQueryMapper",
    "mybatis/analytics/AnalyticsDashboardMapper.xml,com.smartfirehub.analytics.repository.mybatis.AnalyticsDashboardMapper",
    "mybatis/analytics/ChartMapper.xml,com.smartfirehub.analytics.repository.mybatis.ChartMapper"
  })
  void nameListIsCommon_andCountIsPostgresOnly(String path, String ns) {
    for (String databaseId : new String[] {null, MyBatisPlatformConfig.TIBERO}) {
      Configuration c = load(path, databaseId);
      assertThat(c.hasStatement(ns + ".selectNamesByOwner", false)).isTrue();
      assertThat(sql(c, ns + ".selectNamesByOwner")).doesNotContain("translate");
      assertThat(c.hasStatement(ns + ".countByOwnerAndName", false)).isFalse();
    }
    Configuration pg = load(path, MyBatisPlatformConfig.POSTGRESQL);
    assertThat(pg.hasStatement(ns + ".countByOwnerAndName", false)).isTrue();
    assertThat(pg.hasStatement(ns + ".selectNamesByOwner", false)).isTrue();
  }
}
