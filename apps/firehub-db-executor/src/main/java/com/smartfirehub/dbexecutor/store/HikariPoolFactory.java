package com.smartfirehub.dbexecutor.store;

import com.zaxxer.hikari.HikariDataSource;
import java.util.function.BiFunction;
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
    // 계정이 없는 기술자는 TenantPools.fingerprint 가 풀 생성 전에 이미 거부한다(여기서는 null 이 아님).
    StoreDescriptor.Account a = account.apply(d);
    HikariDataSource ds = base(d, a, maxSize, 10_000, TiberoSession.SESSION_INIT_SQL);
    ds.setIdleTimeout(600_000);
    return ds;
  }

  /** 슬롯 풀 유휴 커넥션 수명(ms). Hikari 의 최솟값(10초)보다 크고, 스크립트가 읽기를 마친 뒤 슬롯 세션이 오래 남지 않을 만큼 짧게 둔다. */
  static final long SLOT_IDLE_TIMEOUT_MS = 30_000;

  /** 슬롯 풀 대여 대기(ms). 요청 스레드를 오래 쥐지 않게 짧게 둔다(OWNER·RO 풀은 10초 그대로). */
  static final long SLOT_CONNECTION_TIMEOUT_MS = 3_000;

  /**
   * 슬롯 풀 생성기(WD-78). 슬롯 계정 {@code GENIA_T{id}_S{k}} 로 접속하고, 커넥션마다 기존 NLS 고정에 더해 {@code
   * CURRENT_SCHEMA} 를 테넌트 소유 스키마로 바꾼다 — 스크립트가 스키마 없이 쓴 테이블 이름이 슬롯 계정 자신의 (빈) 스키마가 아니라 테넌트 테이블을
   * 가리키게.
   *
   * <p>풀 크기는 설정값({@code app.pool.slot-max-size}, 기본 3 — Ruling 17: 슬롯은 실행이 아니라 등급마다 하나라 같은 등급
   * Python 단계가 동시에 돌면 같은 (테넌트, 슬롯) 풀을 나눠 쓰므로 1 이면 둘째가 반드시 기다리다 실패한다), 대여 대기 {@link
   * #SLOT_CONNECTION_TIMEOUT_MS}, 최소 유휴 0, 유휴 {@link #SLOT_IDLE_TIMEOUT_MS} — 스크립트 읽기는 짧고 드물어 세션을
   * 쥐고 있을 이유가 없다. 최소 유휴가 크기보다 작아야 Hikari 가 유휴 수명을 적용하므로 0 으로 둔다.
   *
   * @param maxSize (테넌트, 슬롯) 풀의 커넥션 상한 — 설정값을 그대로 Hikari 에 전달한다
   */
  public static BiFunction<StoreDescriptor, Integer, DataSource> forSlots(int maxSize) {
    return (d, slot) -> {
      StoreDescriptor.Account a = d.slot(slot);
      HikariDataSource ds =
          base(
              d,
              a,
              maxSize,
              SLOT_CONNECTION_TIMEOUT_MS,
              TiberoSession.slotSessionInitSql(d.tenantId(), d.schema()));
      ds.setMinimumIdle(0);
      ds.setIdleTimeout(SLOT_IDLE_TIMEOUT_MS);
      return ds;
    };
  }

  /** 두 풀 종류가 함께 쓰는 Hikari 설정. 생성 시점에는 접속하지 않는다. */
  private static HikariDataSource base(
      StoreDescriptor d,
      StoreDescriptor.Account a,
      int maxSize,
      long connectionTimeoutMs,
      String initSql) {
    HikariDataSource ds = new HikariDataSource();
    ds.setDriverClassName(TiberoSession.DRIVER_CLASS);
    ds.setJdbcUrl(TiberoSession.jdbcUrl(d.host(), d.port(), d.database()));
    ds.setUsername(a.username());
    ds.setPassword(a.password());
    ds.setMaximumPoolSize(maxSize);
    ds.setConnectionTimeout(connectionTimeoutMs);
    ds.setConnectionTestQuery(TiberoSession.VALIDATION_QUERY);
    // 커넥션마다 세션을 고정한다(NLS, 슬롯 풀은 CURRENT_SCHEMA 까지).
    ds.setConnectionInitSql(initSql);
    // 한 요청 = 한 트랜잭션: 자동 커밋을 끈다(커밋·롤백은 실행 코드가 명시).
    ds.setAutoCommit(false);
    ds.setInitializationFailTimeout(-1);
    ds.setPoolName("tibero-tenant-" + d.tenantId() + "-" + a.username());
    return ds;
  }
}
