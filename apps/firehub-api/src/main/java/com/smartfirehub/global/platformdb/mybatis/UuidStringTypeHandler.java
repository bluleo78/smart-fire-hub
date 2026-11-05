package com.smartfirehub.global.platformdb.mybatis;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.apache.ibatis.type.JdbcType;

/**
 * UUID 를 문자열로 쓰는 핸들러(WD-487 PoC). Tibero 팩토리에만 {@code UUID} 기본 핸들러로 전역 등록한다({@code
 * MyBatisPlatformConfig#applyTiberoSettings}).
 *
 * <p>왜: Tibero 플랫폼 테이블의 UUID 컬럼은 VARCHAR2(36) 이고 드라이버가 {@code setObject(UUID)} 를 지원하지 않는다
 * (JDBC-590703, 실측). 전역 등록이라 문장마다 문자열 변환 {@code <bind>} 를 둘 필요가 없다. 읽기는 부모({@link
 * UuidTypeHandler})의 문자열·UUID 겸용 변환을 그대로 쓴다.
 */
public class UuidStringTypeHandler extends UuidTypeHandler {

  @Override
  public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
      throws SQLException {
    ps.setString(i, parameter.toString());
  }
}
