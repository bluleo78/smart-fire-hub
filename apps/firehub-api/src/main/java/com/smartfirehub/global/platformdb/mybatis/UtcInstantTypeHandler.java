package com.smartfirehub.global.platformdb.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * {@link Instant} ↔ 시간대 없는 TIMESTAMP(UTC 벽시계) 핸들러(WD-487 PoC). Tibero 팩토리에만 {@code Instant} 기본 핸들러로
 * 전역 등록한다({@code MyBatisPlatformConfig#applyTiberoSettings}).
 *
 * <p>왜: Tibero 플랫폼 테이블은 WD-260 결함 때문에 TIMESTAMP WITH TIME ZONE 을 쓰지 않고 TIMESTAMP 에 UTC 벽시계를 담는다.
 * MyBatis 기본 {@code InstantTypeHandler} 는 {@code getTimestamp().toInstant()} 라 JVM 기본 시간대로 해석해, KST
 * JVM 에서는 9시간 이른 시각이 된다(실측). 여기서는 벽시계를 그대로 꺼내 UTC 로 붙인다. PG 팩토리에는 등록하지 않는다 — PG 의 timestamptz 경로는
 * 기본 핸들러 그대로다.
 */
public class UtcInstantTypeHandler extends BaseTypeHandler<Instant> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, Instant parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, LocalDateTime.ofInstant(parameter, ZoneOffset.UTC));
  }

  @Override
  public Instant getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return toInstant(rs.getObject(columnName, LocalDateTime.class));
  }

  @Override
  public Instant getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return toInstant(rs.getObject(columnIndex, LocalDateTime.class));
  }

  @Override
  public Instant getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return toInstant(cs.getObject(columnIndex, LocalDateTime.class));
  }

  private static Instant toInstant(LocalDateTime utcWall) {
    return utcWall == null ? null : utcWall.toInstant(ZoneOffset.UTC);
  }
}
