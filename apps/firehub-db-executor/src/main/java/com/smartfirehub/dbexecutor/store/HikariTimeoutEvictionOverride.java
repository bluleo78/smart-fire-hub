package com.smartfirehub.dbexecutor.store;

import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * JDBC 문장 시간 초과({@link SQLTimeoutException})를 받은 커넥션을 풀에서 빼는 HikariCP 예외 판정(WD-791, api 의 같은 이름
 * 클래스와 같은 규칙).
 *
 * <p>왜: HikariCP 5.1.0(Boot 3.4.1)은 {@code SQLTimeoutException} 을 받은 커넥션을 고장으로 보고 퇴출했는데, Boot 3.5 가
 * 가져오는 HikariCP 6.x 는 이 조건을 뺐다. Tibero 드라이버는 queryTimeout 취소를 {@code SQLTimeoutException}(-12040)으로
 * 보내므로, 취소된 세션을 풀에 돌려보내지 않던 예전 동작을 이 판정으로 지킨다(업그레이드는 동작을 바꾸지 않는다). 그 밖의 예외는 {@link
 * Override#CONTINUE_EVICT} 로 HikariCP 기본 규칙(08xxx 등)에 맡긴다.
 *
 * <p>Hikari 가 클래스 이름으로 인스턴스를 만들므로 공개 기본 생성자가 있어야 한다.
 */
public class HikariTimeoutEvictionOverride implements SQLExceptionOverride {

  /** 시간 초과는 반드시 퇴출, 나머지는 Hikari 기본 판정에 맡긴다. */
  @java.lang.Override
  public Override adjudicate(SQLException sqlException) {
    return sqlException instanceof SQLTimeoutException
        ? Override.MUST_EVICT
        : Override.CONTINUE_EVICT;
  }
}
