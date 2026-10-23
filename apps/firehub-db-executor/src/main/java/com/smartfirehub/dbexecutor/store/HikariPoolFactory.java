package com.smartfirehub.dbexecutor.store;

import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * 실제 Hikari 풀 생성기(api {@code ExternalStorePoolFactory} 와 같은 설정). 생성 시점에는 접속하지 않는다({@code
 * initializationFailTimeout=-1}) — 외부 DB 가 잠시 내려가 있어도 풀 생성이 실패하지 않고 첫 사용 때 드러난다.
 */
public class HikariPoolFactory implements Function<StoreDescriptor, DataSource> {

  private final int maxSize;
  private final Function<StoreDescriptor, StoreDescriptor.Account> account;

  /**
   * @param maxSize 풀당 최대 커넥션
   * @param account 접속 계정 선택 함수(OWNER 풀은 {@code StoreDescriptor::owner}, 읽기 전용 풀은 {@code
   *     StoreDescriptor::ro})
   */
  public HikariPoolFactory(
      int maxSize, Function<StoreDescriptor, StoreDescriptor.Account> account) {
    this.maxSize = maxSize;
    this.account = account;
  }

  @Override
  public DataSource apply(StoreDescriptor d) {
    HikariDataSource ds = new HikariDataSource();
    ds.setDriverClassName(TiberoSession.DRIVER_CLASS);
    ds.setJdbcUrl(TiberoSession.jdbcUrl(d.host(), d.port(), d.database()));
    // 계정이 없는 기술자는 TenantPools.fingerprint 가 풀 생성 전에 이미 거부한다(여기서는 null 이 아님).
    StoreDescriptor.Account a = account.apply(d);
    ds.setUsername(a.username());
    ds.setPassword(a.password());
    ds.setMaximumPoolSize(maxSize);
    ds.setConnectionTimeout(10_000);
    ds.setIdleTimeout(600_000);
    ds.setConnectionTestQuery(TiberoSession.VALIDATION_QUERY);
    // 커넥션마다 세션 NLS 를 고정한다.
    ds.setConnectionInitSql(TiberoSession.SESSION_INIT_SQL);
    // 한 요청 = 한 트랜잭션: 자동 커밋을 끈다(커밋·롤백은 실행 코드가 명시).
    ds.setAutoCommit(false);
    ds.setInitializationFailTimeout(-1);
    ds.setPoolName("tibero-tenant-" + d.tenantId() + "-" + a.username());
    return ds;
  }
}
