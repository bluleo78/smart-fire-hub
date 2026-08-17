package com.smartfirehub.global.config;

import com.smartfirehub.global.tenant.TenantPipelineRole;
import com.zaxxer.hikari.HikariDataSource;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
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
 *
 * <p><b>단, 사용 중인 풀은 닫지 않는다.</b> 그래서 프로덕션 호출부는 {@link #dslFor} 가 아니라
 * {@link #withTenantDsl} 로 풀을 <b>대여</b>해야 한다 — 대여 구간에는 축출 후보에서 제외된다.
 * 축출할 후보가 하나도 없으면 상한을 일시적으로 넘기고 경고만 남긴다: 상한은 커넥션 고갈을 늦추기
 * 위한 값이지, 진행 중인 작업을 죽여서 지킬 값이 아니다.
 *
 * <p>컨텍스트 종료 시에는 {@link #closeAllPools} 가 남은 풀을 모두 닫는다 — LRU 축출만으로는
 * 스프링 컨텍스트가 여러 번 뜨는 테스트 스위트에서 커넥션이 JVM 종료까지 남는다.
 */
@Slf4j
@Component
public class TenantPipelineDataSourceRegistry {

  private final String jdbcUrl;
  private final String passwordSecret;
  private final int maxPools;
  private final int maxSize;
  private final long idleTimeoutMs;

  /**
   * 테넌트 id → (풀, DSLContext). {@code accessOrder=true} LinkedHashMap 으로 접근 순서를 유지해
   * LRU 를 구현하고, 상한 초과 시 축출은 {@link #evictIfNeeded} 가 한다. 이 맵은 스레드 안전하지
   * 않으므로 모든 접근은 이 클래스의 synchronized 메서드를 통해서만 이뤄진다(동시 요청이 같은 테넌트
   * 풀을 동시에 만드는 경합을 막는다).
   */
  private final Map<Long, TenantPool> pools;

  /**
   * 테넌트 id → 현재 대여 중인 건수. {@link #withTenantDsl} 이 대여 구간에만 올리고, 0 이 되면
   * 항목을 지운다. {@link #evictIfNeeded} 는 이 값이 0 인 풀만 축출 대상으로 삼는다.
   */
  private final Map<Long, Integer> inUse = new java.util.HashMap<>();

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
    // accessOrder=true 로 LRU 순서만 유지하고, 축출은 removeEldestEntry 가 아니라 evictIfNeeded 가
    // 한다 — removeEldestEntry 는 "이 항목을 버릴지" 예/아니오만 답할 수 있어 **사용 중인 풀을
    // 건너뛰고 다음 후보를 찾는 것이 불가능**하다(코드리뷰 지적 3).
    this.pools = new LinkedHashMap<>(16, 0.75f, true);
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
    TenantPool created = pools.computeIfAbsent(tenantId, this::createPool);
    evictIfNeeded(tenantId);
    return created.dslContext();
  }

  /**
   * 테넌트 풀을 **대여**해 작업을 실행한다 — 프로덕션 호출부가 써야 하는 정본 API.
   *
   * <p><b>왜 {@link #dslFor} 를 직접 쓰면 안 되는가(코드리뷰 지적 3).</b> {@code dslFor} 가 돌려준
   * {@link DSLContext} 를 호출자가 **쓰고 있는 동안** 다른 테넌트들의 요청이 상한을 넘기면, 그
   * 풀이 축출 대상으로 뽑혀 {@code close()} 되고 진행 중인 문장이 죽는다(느린 스텝일수록 LRU 상
   * 오래된 항목이 되어 더 잘 뽑힌다). 이 메서드는 대여 구간에 사용 카운트를 올려 두어
   * {@link #evictIfNeeded} 가 그 풀을 건너뛰게 만든다.
   *
   * <p>Python 쪽 twin(`app/db/connection.py` 의 `_evict_if_needed`)이 이미 같은 규칙으로 동작한다 —
   * 한쪽만 갖고 있던 비대칭을 맞춘 것이다.
   */
  public <T> T withTenantDsl(long tenantId, java.util.function.Function<DSLContext, T> work) {
    DSLContext dsl;
    synchronized (this) {
      dsl = dslFor(tenantId);
      inUse.merge(tenantId, 1, Integer::sum);
    }
    try {
      return work.apply(dsl);
    } finally {
      synchronized (this) {
        // 0 이 되면 항목을 지워 맵이 테넌트 수만큼 자라지 않게 한다.
        inUse.compute(tenantId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
      }
    }
  }

  /**
   * 상한을 넘겼으면 **사용 중이 아닌** 가장 오래된 풀을 닫는다.
   *
   * <p>{@code servingTenantId}(방금 이 호출을 위해 확보한 풀)는 반드시 제외한다 — 사용 카운트는
   * 대여 구간에서만 올라가므로 이 시점엔 0 으로 보여, 다른 풀이 모두 바쁘면 **자기 자신이 뽑혀**
   * 방금 만든 풀을 닫아 돌려주게 된다.
   *
   * <p>축출할 수 있는 풀이 없으면 <b>상한을 일시적으로 넘기고 경고만</b> 남긴다. 상한은 커넥션
   * 고갈을 늦추기 위한 값이지, 진행 중인 작업을 죽여서 지킬 값이 아니다.
   */
  private void evictIfNeeded(long servingTenantId) {
    while (pools.size() > maxPools) {
      Long victim = null;
      for (Long tenantId : pools.keySet()) { // accessOrder=true → 오래 전에 쓰인 것부터
        if (tenantId != servingTenantId && inUse.getOrDefault(tenantId, 0) == 0) {
          victim = tenantId;
          break;
        }
      }
      if (victim == null) {
        log.warn(
            "테넌트 커넥션 풀 상한({}) 초과 — 모든 풀이 사용 중이라 축출을 건너뜀 (현재 {}개)",
            maxPools,
            pools.size());
        return;
      }
      TenantPool evicted = pools.remove(victim);
      inUse.remove(victim);
      try {
        evicted.dataSource().close();
      } catch (RuntimeException e) {
        log.warn("테넌트 {} 커넥션 풀 축출 중 종료 실패", victim, e);
      }
    }
  }

  /**
   * 컨텍스트 종료 시 남은 풀을 모두 닫는다.
   *
   * <p>없으면 풀이 <b>LRU 축출로만</b> 닫히므로, 스프링 컨텍스트가 여러 번 생성되는 테스트
   * 스위트에서 컨텍스트마다 최대 {@code max-pools × max-size} 개의 커넥션이 JVM 종료까지 남아
   * 이 클래스가 스스로 경고하는 {@code max_connections} 고갈을 실제로 일으킨다(코드리뷰 지적 4).
   */
  @jakarta.annotation.PreDestroy
  public synchronized void closeAllPools() {
    pools.values()
        .forEach(
            pool -> {
              try {
                pool.dataSource().close();
              } catch (RuntimeException e) {
                log.warn("테넌트 커넥션 풀 종료 실패", e);
              }
            });
    pools.clear();
    inUse.clear();
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
