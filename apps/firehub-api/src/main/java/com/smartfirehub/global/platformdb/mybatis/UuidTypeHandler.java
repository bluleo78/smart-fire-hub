package com.smartfirehub.global.platformdb.mybatis;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

/**
 * UUID 읽기/쓰기 핸들러(WD-487 PoC). MyBatis 는 UUID 기본 핸들러가 없어 resultMap 의 {@code <arg>} 가 검증에서 실패하므로
 * resultMap 에 명시한다. 매퍼가 여럿이 같이 쓰므로 {@link UtcInstantTypeHandler} 옆(플랫폼 DB 공용 패키지)에 둔다.
 *
 * <p>읽기: PG {@code uuid} 는 {@code getObject} 가 UUID 를, Tibero(VARCHAR2(36))는 문자열을 주므로 둘 다 받는다.
 *
 * <p>쓰기: {@code setObject(UUID)} — PG 경로다(전역 등록하지 않아 PG 파라미터는 MyBatis 기본 경로 그대로다). Tibero 팩토리는 문자열로
 * 쓰는 하위 클래스 {@link UuidStringTypeHandler} 를 전역 등록한다.
 */
public class UuidTypeHandler extends BaseTypeHandler<UUID> {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setObject(i, parameter);
  }

  @Override
  public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
    return convert(rs.getObject(columnName));
  }

  @Override
  public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
    return convert(rs.getObject(columnIndex));
  }

  @Override
  public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
    return convert(cs.getObject(columnIndex));
  }

  /** DB 값(UUID 또는 문자열)을 UUID 로 바꾼다. */
  private static UUID convert(Object value) {
    if (value == null) return null;
    if (value instanceof UUID uuid) return uuid;
    return UUID.fromString(value.toString());
  }
}
