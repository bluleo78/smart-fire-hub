package com.smartfirehub.global.datastore;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.jooq.ConnectionProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 분리 모드 데이터 저장소 DSLContext 의 jOOQ {@link ConnectionProvider} — 데이터 저장소 연결을 플랫폼 Spring 트랜잭션에
 * 묶는다(WD-494 스펙 2절).
 *
 * <p><b>왜 두 번째 트랜잭션 매니저가 아닌가.</b> 서비스는 {@code @Transactional} 하나(플랫폼 DB 트랜잭션 매니저)로 메타 갱신과 데이터 저장소
 * DDL·DML 을 함께 감싼다. 데이터 저장소 연결을 그 트랜잭션의 동기화에 얹으면 호출부를 바꾸지 않고 "같이 커밋·같이 롤백"을 얻는다 — Tibero 외부
 * 저장소({@code ExternalStoreConnections})가 이미 쓰는 방식과 같다. 테넌트별 대여 카운트·DDL 경로가 달라 그 클래스와 공통 추출은 하지 않았다.
 *
 * <p><b>acquire/release 규칙.</b>
 *
 * <ul>
 *   <li>실제 Spring 트랜잭션 안: 트랜잭션당 연결 하나(autoCommit false)를 이 객체 전용 키로 묶고 동기화를 등록한다. 같은 트랜잭션의 acquire
 *       는 모두 그 연결을 돌려주고, release 는 아무것도 하지 않는다(완료 시 동기화가 반납).
 *   <li>묶인 연결이 이미 커밋(beforeCommit 지남)됐거나 정리됐으면 — 트랜잭션 완료 단계에서 불린 것이므로 — 자동 커밋 연결을 따로 연다.
 *   <li>트랜잭션 밖: acquire 마다 풀에서 자동 커밋 연결을 꺼내고 release 에서 닫는다(문장마다 자동 커밋).
 * </ul>
 *
 * <p><b>남는 틈.</b> 데이터 저장소는 beforeCommit 에서 커밋됐는데 플랫폼 커밋이 실패하면 두 DB 가 어긋난다(단일 단계 커밋). 경고 로그만 남긴다.
 *
 * <p><b>완료 단계에서 처음 묶는 경우.</b> 이 트랜잭션에서 아직 묶지 않은 채 {@code afterCommit}(예: {@code
 * TransactionalEventListener} 기본 단계)에서 <b>처음</b> 쓰면, Spring API 로는 완료 단계임을 알 수 없어 새로 묶는다. 그 연결은
 * beforeCommit 을 받지 못하므로, afterCompletion 이 플랫폼 커밋(STATUS_COMMITTED)을 보면 그 자리에서 커밋한다 — 롤백하면 쓰기가 조용히
 * 사라지기 때문이다. 이 커밋은 플랫폼 커밋 뒤라 실패해도 되돌릴 수 없어 경고 로그만 남긴다.
 *
 * <p><b>읽기 전용.</b> 묶을 때 Spring 트랜잭션의 읽기 전용 표시를 연결에 옮긴다 — {@code readOnly = true} 트랜잭션이 플랫폼에는 못 쓰는데
 * 데이터 저장소에는 쓸 수 있으면 안 된다. 풀에 돌려주기 전에 되돌린다.
 *
 * <p>DataSource 를 {@code TransactionAwareDataSourceProxy} 로 감싸지 않는다 — 그 프록시는 {@code
 * DataSourceUtils} 로 자기 연결 홀더를 묶어 이 바인딩과 경쟁한다.
 */
@Slf4j
public class DataStoreConnectionBinding implements ConnectionProvider {

  private final DataSource dataSource;

  /**
   * 트랜잭션 리소스 키. DataSource 자체를 키로 쓰지 않는다 — {@code DataSourceUtils} 가 그 키로 연결 홀더를 묶으므로 누가 이 풀에
   * Spring 트랜잭션 매니저를 붙이면 충돌한다.
   */
  private final Object key = new Object();

  public DataStoreConnectionBinding(@DataStoreDb DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /** 묶인 연결과 커밋·정리 여부. 커밋되지 않은 채 끝나면 afterCompletion 이 롤백한다. */
  private static final class BoundConnection {
    final Connection connection;
    boolean committed;
    boolean completed;

    BoundConnection(Connection connection) {
      this.connection = connection;
    }
  }

  @Override
  public Connection acquire() {
    // 묶을 수 있으면 트랜잭션 연결을 쓴다. null = 트랜잭션 밖이거나, 이미 커밋(또는 정리)된 묶음의 완료 단계 호출 — 다시 쓰면 아무도
    // 커밋하지 않으므로 자동 커밋 연결을 따로 연다. 판단 규칙은 boundConnectionOrNull 한 곳에만 둔다.
    Connection bound = boundConnectionOrNull();
    return bound != null ? bound : openAutoCommit();
  }

  @Override
  public void release(Connection connection) {
    BoundConnection bound = currentBound();
    if (bound != null && bound.connection == connection) {
      // 묶인 연결은 트랜잭션 완료(afterCompletion) 때 반납한다.
      return;
    }
    try {
      connection.close();
    } catch (SQLException e) {
      throw translate("데이터 저장소 연결 반납 실패", e);
    }
  }

  /**
   * SAVEPOINT 용 — 실제 Spring 트랜잭션 안이면 그 트랜잭션에 묶인(필요하면 지금 묶는) 연결을, 트랜잭션 밖이거나 묶음이 이미 커밋된 완료 단계면 {@code
   * null} 을 돌려준다. {@link DataStoreTransactionProvider} 가 SAVEPOINT 와 로컬 트랜잭션 중 무엇을 쓸지 이것으로 정한다.
   */
  Connection boundConnectionOrNull() {
    if (!inActualTransaction()) {
      return null;
    }
    BoundConnection bound = currentBound();
    if (bound == null) {
      return bind().connection;
    }
    return bound.committed || bound.completed ? null : bound.connection;
  }

  /**
   * 실제 트랜잭션과 동기화가 모두 활성일 때만 묶는다 — 동기화만 켜진 비트랜잭션 구간(SUPPORTS 등)에서는 afterCompletion 이 오지 않을 수 있어 묶으면
   * 연결이 샌다.
   */
  private static boolean inActualTransaction() {
    return TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive();
  }

  private BoundConnection currentBound() {
    return (BoundConnection) TransactionSynchronizationManager.getResource(key);
  }

  /** 풀에서 연결을 꺼내 autoCommit 을 끄고 현재 트랜잭션에 묶은 뒤 동기화를 등록한다. */
  private BoundConnection bind() {
    Connection c = null;
    try {
      c = dataSource.getConnection();
      c.setAutoCommit(false);
      // BEGIN 이 나가기 전(연결이 유휴)이라 지금 바꿔도 된다. 읽기 전용 트랜잭션이면 데이터 저장소 쓰기도 DB 가 거부하게 한다.
      c.setReadOnly(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
      BoundConnection bound = new BoundConnection(c);
      TransactionSynchronizationManager.bindResource(key, bound);
      try {
        TransactionSynchronizationManager.registerSynchronization(new BoundSynchronization(bound));
      } catch (RuntimeException e) {
        TransactionSynchronizationManager.unbindResourceIfPossible(key);
        throw e;
      }
      return bound;
    } catch (SQLException | RuntimeException e) {
      // 묶기에 실패하면 afterCompletion 이 오지 않으므로 여기서 직접 반납한다.
      closeQuietly(c);
      if (e instanceof SQLException se) {
        throw translate("데이터 저장소 연결을 트랜잭션에 묶지 못함", se);
      }
      throw (RuntimeException) e;
    }
  }

  private Connection openAutoCommit() {
    try {
      Connection c = dataSource.getConnection();
      // 풀 기본이 자동 커밋이지만, 앞선 사용자가 상태를 바꿔 놓았을 가능성에 대비해 명시한다.
      if (!c.getAutoCommit()) {
        c.setAutoCommit(true);
      }
      return c;
    } catch (SQLException e) {
      throw translate("데이터 저장소 연결 획득 실패", e);
    }
  }

  /** SQLState 기준 번역기 — 연결 획득·커밋 실패를 쿼리 실패와 같은 Spring 예외 계층으로 올린다. */
  private static final SQLExceptionTranslator TRANSLATOR = new SQLStateSQLExceptionTranslator();

  /**
   * 연결 획득·커밋·반납의 {@link SQLException} 을 Spring {@link DataAccessException} 으로 바꾼다. 쿼리 실행 예외는 {@code
   * ExceptionTranslatorExecuteListener} 가 번역하지만 그 밖(beforeCommit 등)은 거치지 않으므로 여기서 맞춘다.
   */
  private static DataAccessException translate(String task, SQLException e) {
    DataAccessException translated = TRANSLATOR.translate(task, null, e);
    return translated != null ? translated : new UncategorizedSQLException(task, null, e);
  }

  private static void closeQuietly(Connection c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (SQLException e) {
      log.debug("데이터 저장소 연결 닫기 실패(무시): {}", e.getClass().getName());
    }
  }

  /** 묶인 연결의 커밋·정리를 플랫폼 Spring 트랜잭션 수명에 맞춘다. */
  private final class BoundSynchronization implements TransactionSynchronization {
    private final BoundConnection bound;

    BoundSynchronization(BoundConnection bound) {
      this.bound = bound;
    }

    /** 플랫폼 커밋 직전에 데이터 저장소를 커밋한다. 실패하면 예외가 플랫폼 트랜잭션도 롤백시킨다. */
    @Override
    public void beforeCommit(boolean readOnly) {
      try {
        bound.connection.commit();
        bound.committed = true;
      } catch (SQLException e) {
        throw translate("데이터 저장소 커밋 실패", e);
      }
    }

    /**
     * REQUIRES_NEW 로 바깥 트랜잭션이 일시중단될 때 묶음을 풀어 둔다. Spring 은 동기화 목록만 비우고 리소스는 그대로 두므로, 풀지 않으면 안쪽 트랜잭션이
     * 바깥 연결을 재사용해 안쪽 쓰기가 바깥과 함께 커밋·롤백된다.
     */
    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResourceIfPossible(key);
    }

    /** 일시중단이 끝나면 바깥 트랜잭션의 연결을 다시 묶는다. */
    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(key, bound);
    }

    /**
     * 커밋되지 않은 연결을 마무리한 뒤 읽기 전용·autoCommit 을 되돌려 풀에 반납한다. 플랫폼이 커밋됐는데 아직 커밋하지 않은 연결은 beforeCommit
     * 뒤(afterCommit 등)에 처음 묶인 것이라 커밋하고, 그 밖의 미커밋 연결은 롤백한다.
     */
    @Override
    public void afterCompletion(int status) {
      if (bound.completed) {
        return;
      }
      bound.completed = true;
      try {
        if (bound.committed && status != STATUS_COMMITTED) {
          // 데이터 저장소는 커밋됐는데 플랫폼은 커밋되지 않은 단일 단계 커밋의 틈 — 두 DB 가 어긋났을 수 있다.
          log.warn("데이터 저장소는 커밋됐으나 플랫폼 트랜잭션은 커밋되지 않음(status={})", status);
        }
        if (!bound.committed && status == STATUS_COMMITTED) {
          // beforeCommit 스냅숏 뒤에 처음 묶인 연결 — 플랫폼은 이미 커밋됐으므로 따라 커밋한다. 실패해도 플랫폼을 되돌릴 수 없어 로그만 남긴다.
          try {
            bound.connection.commit();
            bound.committed = true;
          } catch (SQLException e) {
            log.warn("플랫폼 커밋 뒤 데이터 저장소 커밋 실패 — 두 DB 가 어긋났을 수 있다: {}", e.getClass().getName());
          }
        }
        if (!bound.committed) {
          try {
            bound.connection.rollback();
          } catch (SQLException e) {
            log.warn("데이터 저장소 롤백 실패: {}", e.getClass().getName());
          }
        }
        try {
          // 트랜잭션이 끝난 뒤라 바꿀 수 있다. 다음 대여자가 읽기 전용 연결을 받지 않게 한다.
          bound.connection.setReadOnly(false);
        } catch (SQLException e) {
          log.debug("데이터 저장소 readOnly 복원 실패(무시): {}", e.getClass().getName());
        }
        try {
          bound.connection.setAutoCommit(true);
        } catch (SQLException e) {
          log.debug("데이터 저장소 autoCommit 복원 실패(무시): {}", e.getClass().getName());
        }
        closeQuietly(bound.connection);
      } finally {
        TransactionSynchronizationManager.unbindResourceIfPossible(key);
      }
    }
  }
}
