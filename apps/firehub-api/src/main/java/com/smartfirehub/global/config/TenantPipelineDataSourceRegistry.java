package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jooq.ExceptionTranslatorExecuteListener;
import org.springframework.boot.autoconfigure.jooq.SpringTransactionProvider;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.stereotype.Component;

/**
 * 테넌트별 파이프라인 실행 커넥션 풀({@code pipeline_executor_t{tenantId}} 자격증명)의 레지스트리.
 *
 * <p><b>왜 {@code SET ROLE} 이 아니라 테넌트별로 별도 자격증명(별도 커넥션 풀)을 쓰는가(R2).</b>
 * {@code SET ROLE} 은 세션 안에서 유효한 상태 전환일 뿐이라, 같은 세션이 스스로 {@code RESET ROLE}
 * 로 되돌리면 이후 문장은 원래 권한(대개 소유자·상위 권한)으로 실행된다. SQL 실행 경로는 {@code
 * SqlValidator} 로 위험 문장을 걸러내지만, Python 실행 경로({@code python_executor.py})는 임의
 * 스크립트를 그대로 돌리는 구조라 문장을 검증할 수단 자체가 없다 — 그 경로에서 사용자 스크립트가
 * {@code RESET ROLE} 을 실행하는 것을 막을 방법이 없다는 뜻이다. 반면 별도 DB 자격증명으로 접속하면,
 * 접속 자체가 그 롤로 고정되므로 세션 안에서 되돌릴 수 있는 상태가 아니다 — 신뢰 경계가 SQL 문장이
 * 아니라 TCP 커넥션 수준에 있다.
 *
 * <p><b>왜 풀 개수에 상한을 두는가(R3).</b> 테넌트마다 별도 풀을 쓰는 대가로 커넥션 풀 개수가
 * 테넌트 수만큼 늘어난다. 상한 없는 {@code Map<Long, HikariDataSource>} 는 테넌트가 늘수록
 * PostgreSQL {@code max_connections}(=100) 을 고갈시킨다 — 테스트 스위트를 겹쳐 돌릴 때 이미 겪은
 * 실패 형태와 같은 종류의 자원 고갈이다. 그래서 풀당 {@code maximumPoolSize} 를 작게 잡고(기본 2),
 * 전체 풀 개수는 {@code max-pools} 로 한정해 LRU(가장 오래 쓰이지 않은 것부터) 로 축출한다.
 *
 * <p><b>축출된 풀은 반드시 {@link HikariDataSource#close()} 한다.</b> 클로저가 축출된
 * {@code HikariDataSource} 를 참조로 붙들고 있으면(예: 진행 중인 실행기가 이미 받아 간 {@code
 * DSLContext}), 맵에서는 사라져도 실제 DB 커넥션은 살아 있어 {@code max_connections} 을 계속
 * 갉아먹는 눈에 보이지 않는 누수가 된다. 이 클래스는 축출 시점에 즉시 {@code close()} 를 호출해
 * 그 커넥션들을 실제로 반납한다.
 */
@Component
public class TenantPipelineDataSourceRegistry {

  private final String jdbcUrl;
  private final String passwordSecret;
  private final int maxPools;
  private final int maxSize;
  private final long idleTimeoutMs;

  /**
   * 테넌트 id → (풀, DSLContext). {@code accessOrder=true} LinkedHashMap 으로 접근 순서를 유지해
   * LRU 를 구현한다. {@link #removeEldestEntry} 가 상한 초과 시 가장 오래 전에 쓰인 항목을 스스로
   * 제거하면서 그 풀을 닫는다. 이 맵은 스레드 안전하지 않으므로 모든 접근은 이 클래스의 synchronized
   * 메서드를 통해서만 이뤄진다(동시 요청이 같은 테넌트 풀을 동시에 만드는 경합을 막는다).
   */
  private final Map<Long, TenantPool> pools;

  public TenantPipelineDataSourceRegistry(
      @Value("${app.pipeline.datasource.url}") String jdbcUrl,
      @Value("${app.pipeline.role-password-secret}") String passwordSecret,
      @Value("${app.pipeline.tenant-pool.max-pools:8}") int maxPools,
      @Value("${app.pipeline.tenant-pool.max-size:2}") int maxSize,
      @Value("${app.pipeline.tenant-pool.idle-timeout-ms:600000}") long idleTimeoutMs) {
    this.jdbcUrl = jdbcUrl;
    this.passwordSecret = passwordSecret;
    this.maxPools = maxPools;
    this.maxSize = maxSize;
    this.idleTimeoutMs = idleTimeoutMs;
    this.pools =
        new LinkedHashMap<>(16, 0.75f, true) {
          @Override
          protected boolean removeEldestEntry(Map.Entry<Long, TenantPool> eldest) {
            boolean overflow = size() > TenantPipelineDataSourceRegistry.this.maxPools;
            if (overflow) {
              // 맵에서 빠지기 직전에 닫아야 새 풀 생성과 옛 풀 종료 사이에 창(window)이 생기지 않는다.
              eldest.getValue().dataSource().close();
            }
            return overflow;
          }
        };
  }

  /**
   * 테넌트 {@code tenantId} 전용 파이프라인 실행 {@link DSLContext} 를 돌려준다. 풀이 없으면 새로
   * 만들고, 있으면 재사용하며 LRU 순서를 최신으로 갱신한다.
   *
   * <p>{@code get} 을 먼저 시도하는 이유: {@code Map.computeIfAbsent} 는 키가 이미 있을 때
   * {@code LinkedHashMap} 의 {@code afterNodeAccess}(LRU 순서 갱신)를 타지 않는 내부 {@code
   * getNode} 경로를 쓴다 — 그래서 재사용 히트가 LRU 순서에 반영되지 않는 채로 남는다. 명시적으로
   * {@link Map#get} 을 먼저 호출해야 히트 시에도 접근 순서가 갱신된다.
   */
  public synchronized DSLContext dslFor(long tenantId) {
    TenantPool existing = pools.get(tenantId);
    if (existing != null) {
      return existing.dslContext();
    }
    return pools.computeIfAbsent(tenantId, this::createPool).dslContext();
  }

  /** 현재 열려 있는 테넌트 풀 개수. 누수 없이 상한을 지키는지 테스트가 관측하는 용도. */
  public synchronized int poolCount() {
    return pools.size();
  }

  private TenantPool createPool(long tenantId) {
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcUrl);
    dataSource.setUsername(TenantPipelineRole.roleName(tenantId));
    dataSource.setPassword(TenantPipelineRole.password(tenantId, passwordSecret));
    dataSource.setMaximumPoolSize(maxSize);
    dataSource.setIdleTimeout(idleTimeoutMs);
    dataSource.setPoolName("pipeline-tenant-" + tenantId + "-pool");
    dataSource.setConnectionTestQuery("SELECT 1");
    // pipeline_executor_t{id} 비밀번호는 RolePasswordSyncCallback 이 Flyway migrate 직후 동기화한다.
    // 그 시점 이전에 이 풀이 즉시 접속을 검증하면 동기화 전 상태로 실패할 수 있어 지연 검증으로 둔다.
    dataSource.setInitializationFailTimeout(-1);

    // PipelineSandboxDataSourceConfig#pipelineDslContext 와 동일한 패턴: 이 커넥션은 테넌트 GUC/RLS
    // 가 아니라 스키마+롤 분리로 격리되므로 TenantAware 트랜잭션 매니저가 필요 없다("고려했지만
    // 불필요"임을 명시한다).
    DefaultConfiguration config = new DefaultConfiguration();
    config.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(dataSource)));
    config.set(SQLDialect.POSTGRES);
    config.set(new SpringTransactionProvider(new DataSourceTransactionManager(dataSource)));
    config.set(new DefaultExecuteListenerProvider(ExceptionTranslatorExecuteListener.DEFAULT));
    DSLContext dslContext = DSL.using(config);

    return new TenantPool(dataSource, dslContext);
  }

  /** 테넌트 하나의 풀과 그 위에 얹은 DSLContext 를 함께 보관하는 내부 레코드. */
  private record TenantPool(HikariDataSource dataSource, DSLContext dslContext) {}
}
