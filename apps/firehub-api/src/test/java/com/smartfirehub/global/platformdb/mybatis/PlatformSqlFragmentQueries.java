package com.smartfirehub.global.platformdb.mybatis;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import org.mybatis.spring.SqlSessionTemplate;

/**
 * PlatformSql 공용 조각 시험 질의(src/test/resources/mybatis/test/PlatformSqlFragmentsTestMapper.xml)를 부르는
 * 시험 도우미(WD-707). PG 시험({@code PlatformSqlFragmentsTest})과 Tibero 탐침({@code
 * TiberoBundle2SyntaxProbeLiveTest})에 복제돼 있던 호출 코드를 한 곳에 모았다. 테넌트 범위·트랜잭션은 DB 마다 여는 방법이 달라 호출자가
 * 감싼다.
 */
public final class PlatformSqlFragmentQueries {

  /** 시험 질의 namespace. */
  public static final String NS =
      "com.smartfirehub.global.platformdb.mybatis.PlatformSqlFragmentsTest.";

  private final SqlSessionTemplate sql;

  public PlatformSqlFragmentQueries(SqlSessionTemplate sql) {
    this.sql = sql;
  }

  /** 같은 문장의 DB 현재 시각과 seconds 초 뒤 시각의 차이(ms). 결과 맵 키는 DB 마다 대소문자가 달라 소문자로 맞춘다. */
  public long laterMinusNowMillis(long seconds) {
    Map<String, Object> raw = sql.selectOne(NS + "nowAndLater", Map.of("seconds", seconds));
    Map<String, Object> row = new HashMap<>();
    raw.forEach((k, v) -> row.put(k.toLowerCase(), v));
    return ((Timestamp) row.get("later_at")).getTime() - ((Timestamp) row.get("base_at")).getTime();
  }

  /** 값 하나가 likeIgnoreCase 패턴과 맞으면 1, 아니면 0. */
  public int like(String value, String pattern) {
    Map<String, Object> p = new HashMap<>();
    p.put("value", value);
    p.put("pattern", pattern);
    return sql.selectOne(NS + "likeCount", p);
  }

  /** 문자열 a·b 가 nullSafeEquals 로 같으면 1, 아니면 0. */
  public int nullSafe(String a, String b) {
    return pair("nullSafeCount", a, b);
  }

  /** 문자열 a·b 가 nullSafeDistinct 로 다르면 1, 아니면 0. */
  public int nullSafeDistinct(String a, String b) {
    return pair("nullSafeDistinctCount", a, b);
  }

  /**
   * 숫자 열 a 와 Integer 파라미터 b 가 nullSafeDistinct 로 다르면 1 — countStale 의 embedding_dim 비교처럼 null 파라미터를
   * jdbcType 없이 바인드한다.
   */
  public int nullSafeDistinctNumber(Integer a, Integer b) {
    return pair("nullSafeDistinctNumberCount", a, b);
  }

  /** 데이터셋 한 건의 description 이 likeIgnoreCase 패턴과 맞으면 1(호출자가 테넌트 범위·트랜잭션을 연다). */
  public int datasetDescriptionLike(long datasetId, String pattern) {
    return sql.<Integer>selectOne(
        NS + "datasetDescriptionLikeCount", Map.of("id", datasetId, "pattern", pattern));
  }

  private int pair(String id, Object a, Object b) {
    Map<String, Object> p = new HashMap<>();
    p.put("a", a);
    p.put("b", b);
    return sql.selectOne(NS + id, p);
  }
}
