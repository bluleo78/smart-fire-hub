package com.smartfirehub.global.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.SQLExceptionOverride;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/**
 * JDBC 문장 시간 초과({@link SQLTimeoutException})를 받은 커넥션을 풀에서 빼는 HikariCP 예외 판정(WD-791).
 *
 * <p>왜: HikariCP 5.1.0(Boot 3.4.1)은 {@code ProxyConnection.checkException} 에서 SQLState 08xxx 등과 함께
 * {@code SQLTimeoutException} 도 「고장난 커넥션」으로 보고 퇴출했다. Boot 3.5 가 가져오는 HikariCP 6.x 는 이 조건을 뺐다(6.3.3
 * 바이트코드 확인 — 퇴출 조건은 08xxx·고정 SQLState/오류 코드·{@code MUST_EVICT} 판정뿐). Tibero 드라이버는 queryTimeout 취소를
 * {@code SQLTimeoutException}(-12040)으로 보내는데, 취소된 세션을 그대로 풀에 돌려보내지 않는다는 예전 동작과 그 위에 선 WD-727 롤백
 * 처리({@code TenantAwareTransactionManager#doRollback})·{@code TiberoQueryTimeoutLiveTest} 가 이 퇴출을
 * 전제로 한다. 업그레이드는 동작을 바꾸지 않아야 하므로 이 판정으로 5.x 동작을 되살린다.
 *
 * <p>그 밖의 예외는 {@link Override#CONTINUE_EVICT} — HikariCP 기본 규칙(08xxx 등)을 그대로 따른다. PG 의 문장
 * 취소(SQLState 57014)는 {@code SQLTimeoutException} 이 아닌 일반 예외라 5.x 때처럼 퇴출되지 않는다.
 *
 * <p>Spring 이 만드는 풀은 {@code spring.datasource.hikari.exception-override-class-name} 으로, 코드로 만드는 풀은
 * {@link #applyTo(HikariConfig)} 로 건다. Hikari 가 이름으로 인스턴스를 만들므로 공개 기본 생성자가 있어야 한다.
 */
public class HikariTimeoutEvictionOverride implements SQLExceptionOverride {

  /** 시간 초과는 반드시 퇴출, 나머지는 Hikari 기본 판정에 맡긴다. */
  @java.lang.Override
  public Override adjudicate(SQLException sqlException) {
    return sqlException instanceof SQLTimeoutException
        ? Override.MUST_EVICT
        : Override.CONTINUE_EVICT;
  }

  /**
   * 코드로 만드는 풀(HikariConfig·HikariDataSource)에 이 판정을 건다. {@code spring.datasource.hikari} 바인딩을 거치지
   * 않는 풀은 설정 파일의 값이 닿지 않으므로 생성 지점마다 이 메서드를 부른다.
   */
  public static void applyTo(HikariConfig config) {
    config.setExceptionOverrideClassName(HikariTimeoutEvictionOverride.class.getName());
  }
}
