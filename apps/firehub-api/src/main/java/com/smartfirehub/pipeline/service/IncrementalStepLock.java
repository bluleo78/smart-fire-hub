package com.smartfirehub.pipeline.service;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.service.executor.ExecutionCancelCheck;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * 증분 스텝({@code {{last_run_at}}}) 단위 직렬화 잠금(WD-223).
 *
 * <p><b>왜 필요한가.</b> 증분 스텝은 「책갈피 읽기 → 변경분 적재 → 책갈피 전진」을 한다. 같은 스텝의 실행 둘이 겹치면(연쇄·데이터 변경·API·웹훅·수동
 * 실행에는 겹침 정책이 없다 — SCHEDULE 만 SKIP) 두 실행이 같은 책갈피를 읽어 같은 구간을 각자 적재한다 — APPEND 출력에는 새 행이 두 번씩 들어가고 둘
 * 다 성공으로 끝난다. 이 잠금은 그 세 단계 전체를 덮어, 뒤 실행이 앞 실행이 책갈피를 전진시킨 <b>뒤에</b> 새 책갈피로 읽게 한다. 뒤 실행은 건너뛰지 않고 기다렸다
 * 실행한다(데이터 반영이 늦어지지 않게).
 *
 * <p><b>왜 세션 범위인가.</b> 덮을 구간이 커넥션 셋에 걸친다 — 책갈피 읽기·후보·전진은 앱 커넥션, 적재는 테넌트 파이프라인 롤 커넥션(또는 실행기 HTTP)
 * 이다. 트랜잭션 범위 잠금으로는 묶을 수 없어 출력 세션 잠금(#735)과 같은 장치({@link SessionAdvisoryLock})를 쓴다.
 *
 * <p><b>키.</b> {@code fh_incremental_step:<테넌트>:<스텝 id>} — 책갈피가 스텝마다 따로이므로 스텝 단위다(같은 출력에 쓰는 서로 다른
 * 증분 스텝은 공유 상태가 없어 직렬화하지 않는다). 접두어로 출력 잠금 키({@code "<스키마>"."<테이블>"})와 키 공간을 나눈다.
 *
 * <p><b>획득 순서(교착 없음).</b> 이 잠금을 먼저 잡고, 그 안에서만 출력 잠금(전체 재생성 DELETE 의 트랜잭션 범위 잠금)을 잡는다. 출력 잠금을 쥔 채 이
 * 잠금을 기다리는 경로는 없다 — 출력 트랜잭션 잠금은 한 트랜잭션 안에서만 쥐고, 출력 세션 잠금(PYTHON REPLACE)은 증분 스텝이 쓰지 않는다.
 *
 * <p><b>대기 상한·취소.</b> 앞 실행이 비정상적으로 오래 걸리면 영원히 기다리지 않도록 상한({@code
 * app.pipeline.incremental-lock.max-wait-ms}, 기본 30분)을 두고, 넘기면 한국어 안내로 스텝을 실패시킨다(책갈피는 전진하지 않으므로 다음
 * 실행이 그 구간을 그대로 읽는다 — 데이터 손실 없음). 대기 중에도 협조적 취소(WD-153)를 매 회차 확인한다 — 취소되면 잠금을 얻기 전이라 출력은 그대로이고 스텝은
 * CANCELLED 로 남는다.
 *
 * <p><b>잠금 커넥션은 메인 풀이 아니라 잠금 전용 작은 풀에서 얻는다.</b> 잠금을 쥔 실행은 스텝 내내 커넥션 하나를 붙잡는다. 메인 풀(Hikari 기본 10)에서
 * 얻으면 증분 스텝이 pipelineExecutor 최대치(10)만큼 동시에 돌 때 메인 풀이 바닥나 로그인·조회를 포함한 API 전체가 커넥션 타임아웃으로 멈춘다. 그래서
 * 같은 접속 정보(URL·자격)로 이름이 다른 별도 풀({@value #POOL_NAME}, 최대 {@code
 * app.pipeline.incremental-lock.pool-size}, 기본 10 = pipelineExecutor 최대)을 만들고, 대기자의 폴링도 이 풀만 쓴다. 이
 * 풀이 바닥나면 메인 풀로 넘어가지 않고 상한 안에서 다시 시도하다가 같은 안내로 실패한다 ({@link SessionAdvisoryLock#acquire}). 풀은 처음 쓸
 * 때 만든다 — 증분 스텝을 돌리지 않는 기동·테스트 컨텍스트는 커넥션을 열지 않는다.
 *
 * <p><b>테넌트 GUC·RLS 는 필요 없다.</b> advisory 잠금 함수는 테이블을 읽지 않아 RLS 대상이 아니고, 메인 풀의 GUC 주입도 트랜잭션
 * 매니저(TenantAwareTransactionManager)가 할 뿐 커넥션 자체에는 없다. 테넌트 구분은 키 문자열이 한다.
 */
@Component
public class IncrementalStepLock implements DisposableBean {

  /** 키 접두어 — 다른 advisory 잠금과 키 공간을 나눈다. */
  static final String KEY_PREFIX = "fh_incremental_step:";

  /** 잠금 전용 풀 이름 — 메인 풀(HikariPool-1)과 구분해 커넥션 목록·로그에서 알아볼 수 있게 한다. */
  static final String POOL_NAME = "incremental-step-lock";

  /** 잠금 전용 풀이 바닥났을 때 한 번 기다리는 시간 — 짧게 끊어 취소·상한을 자주 확인한다(Hikari 최소 250ms). */
  static final long POOL_CONNECTION_TIMEOUT_MS = 1000;

  /** 잠금 커넥션을 얻을 풀 공급자(처음 쓸 때 만든다). 테스트는 직접 만든 풀을 넘긴다. */
  private final Supplier<DataSource> lockPool;

  private final long maxWaitMs;

  /** 이 객체가 만든 풀(닫을 책임). 외부에서 받은 풀이면 null. */
  private volatile HikariDataSource ownedPool;

  /**
   * 운영 생성자 — 메인 데이터소스와 같은 접속 정보로 잠금 전용 풀을 만든다(처음 쓸 때).
   *
   * @param properties 메인 데이터소스 접속 정보(spring.datasource.*)
   * @param maxWaitMs 대기 상한(밀리초)
   * @param poolSize 잠금 전용 풀 최대 크기 — 동시에 잠금을 쥘 수 있는 증분 스텝 수
   */
  @Autowired
  public IncrementalStepLock(
      DataSourceProperties properties,
      @Value("${app.pipeline.incremental-lock.max-wait-ms:1800000}") long maxWaitMs,
      @Value("${app.pipeline.incremental-lock.pool-size:10}") int poolSize) {
    this.maxWaitMs = maxWaitMs;
    this.lockPool = () -> ownedPool(properties, poolSize);
  }

  /** 테스트용 — 잠금 커넥션을 얻을 풀을 직접 넘긴다(닫는 것은 호출자 몫). */
  IncrementalStepLock(DataSource lockPool, long maxWaitMs) {
    this.maxWaitMs = maxWaitMs;
    this.lockPool = () -> lockPool;
  }

  /** 잠금 전용 풀을 한 번만 만든다. 기동 시 접속을 시도하지 않고(initializationFailTimeout=-1), 쉬는 커넥션은 남기지 않는다. */
  private DataSource ownedPool(DataSourceProperties properties, int poolSize) {
    HikariDataSource pool = ownedPool;
    if (pool != null) {
      return pool;
    }
    synchronized (this) {
      if (ownedPool == null) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(POOL_NAME);
        // pg_stat_activity 에서 잠금 커넥션을 알아볼 수 있게 접속 이름을 붙인다(PostgreSQL JDBC 의 ApplicationName).
        config.addDataSourceProperty("ApplicationName", POOL_NAME);
        config.setJdbcUrl(properties.determineUrl());
        config.setUsername(properties.determineUsername());
        config.setPassword(properties.determinePassword());
        String driver = properties.determineDriverClassName();
        if (driver != null) {
          config.setDriverClassName(driver);
        }
        config.setMaximumPoolSize(Math.max(1, poolSize));
        config.setMinimumIdle(0);
        config.setIdleTimeout(60_000);
        config.setConnectionTimeout(POOL_CONNECTION_TIMEOUT_MS);
        config.setInitializationFailTimeout(-1);
        ownedPool = new HikariDataSource(config);
      }
      return ownedPool;
    }
  }

  /** 컨텍스트 종료 시 이 객체가 만든 풀을 닫는다(쥔 잠금은 세션 종료로 서버가 회수한다). */
  @Override
  public void destroy() {
    HikariDataSource pool = ownedPool;
    if (pool != null) {
      pool.close();
    }
  }

  /** 테넌트·스텝 키. 스텝 id 는 전역 시퀀스지만 키 공간이 DB 전체 공유라 테넌트를 함께 넣어 둔다. */
  static String key(long tenantId, long stepId) {
    return KEY_PREFIX + tenantId + ":" + stepId;
  }

  /**
   * 이 증분 스텝의 잠금을 얻을 때까지 기다린다. 돌려준 핸들을 닫으면 풀린다(여러 번 닫아도 된다).
   *
   * @param stepId 증분 스텝 id
   * @param stepName 안내 문구용 스텝 이름
   * @param cancel 대기 중 확인할 협조적 취소
   */
  public SessionAdvisoryLock.Held acquire(
      long stepId, String stepName, ExecutionCancelCheck cancel) {
    long tenantId = TenantContext.require("증분 스텝 잠금");
    return SessionAdvisoryLock.acquire(
        lockPool.get(),
        key(tenantId, stepId),
        "증분 스텝 잠금",
        maxWaitMs,
        cancel,
        () -> timeoutMessage(stepName, maxWaitMs));
  }

  /** 대기 상한을 넘겼을 때의 안내 — 무엇이 막았는지와 사용자가 할 일을 함께 말한다. */
  static String timeoutMessage(String stepName, long maxWaitMs) {
    long seconds = Math.max(1, maxWaitMs / 1000);
    String waited = seconds >= 60 ? (seconds / 60) + "분" : seconds + "초";
    return "같은 증분 스텝('"
        + stepName
        + "')의 이전 실행이 "
        + waited
        + " 넘게 끝나지 않아 이번 실행을 중단했습니다. 이전 실행이 끝난 뒤 다시 실행해 주세요"
        + "(책갈피는 그대로라 빠지는 데이터는 없습니다).";
  }
}
