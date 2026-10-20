package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.apiconnection.service.EncryptionService;
import com.smartfirehub.dataset.storage.DataStoreAccountRole;
import com.smartfirehub.dataset.storage.DataStoreBackend;
import com.smartfirehub.dataset.storage.StoredAccount;
import com.smartfirehub.dataset.storage.TenantDataStoreRepository;
import com.smartfirehub.dataset.storage.TenantDataStoreRow;
import com.smartfirehub.dataset.storage.TenantDataStoreStatus;
import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.dataset.storage.tibero.TiberoErrors;
import com.smartfirehub.global.exception.CodedApiException;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 테넌트별 Tibero 소유 계정 커넥션 풀과, 그 커넥션을 Spring 트랜잭션에 묶는 컴포넌트(WD-74).
 *
 * <p><b>왜 묶는가.</b> {@code replaceDatasetData} 는 truncate 후 insert 를 한 {@code @Transactional} 로
 * 실행하고, {@code addRow} 는 insert 뒤 같은 트랜잭션에서 읽는다. 외부 DB 커넥션이 호출마다 따로 열리면 중간 실패 시 기존 행이 사라지고, 방금 쓴 행을
 * 읽지 못한다. 그래서 {@link #withDml} 은 트랜잭션 안에서 테넌트당 커넥션 하나를 공유하고, PostgreSQL 커밋 직전({@code
 * beforeCommit})에 커밋한다.
 *
 * <p><b>DDL 은 왜 따로.</b> Oracle 계열 DDL 은 암묵적으로 커밋되므로 묶인 커넥션에서 실행하면 진행 중인 DML 이 조용히 확정된다. {@link
 * #withDdl} 은 항상 새 자동 커밋 커넥션을 쓴다 — 그것도 <b>풀이 아닌 단발 직결 커넥션</b>이다. 풀(최대 4)에서 꺼내면, 묶인 DML 커넥션이 대여 중인
 * 트랜잭션 안의 DDL(예: 행 쓰기 뒤 컬럼 추가)과 동시 요청이 겹칠 때 풀이 바닥나 서로를 기다리다 시간 초과로 실패한다. DDL 은 드물어 호출마다 접속해도 된다. 직결
 * 커넥션도 풀 커넥션과 같은 세션 NLS 설정({@link TiberoDialect#SESSION_INIT_SQL})으로 시작한다.
 *
 * <p><b>풀 관리.</b> {@link com.smartfirehub.global.config.TenantPipelineDataSourceRegistry} 와 같은
 * 규칙이다: LRU, 대여(inUse) 중인 풀은 축출하지 않고(상한을 잠시 넘기고 경고), 종료 시 모두 닫는다. 묶인 커넥션도 {@code afterCompletion}
 * 까지 대여로 센다. 풀은 READY 저장소에 대해서만 만들어지고 READY 는 PENDING 으로 돌아가지 않으므로(R3), 이미 있는 풀에서 꺼낼 때는 상태를 다시 읽지
 * 않는다.
 *
 * <p><b>잠금 범위.</b> 풀 맵·대여 카운트는 이 객체의 모니터로 보호한다. 풀이 없을 때의 PostgreSQL 읽기(저장소 행·계정)와 비밀번호 복호화는 잠금 밖에서
 * 하고, 잠금 안에서는 다시 확인(그새 다른 스레드가 만들었을 수 있다) → 풀 객체 생성(접속하지 않음) → 등록 → 축출 대상 선정만 한다. 축출된 풀은 잠금을 나온 뒤
 * 닫는다 — 풀 닫기는 커넥션 종료를 기다릴 수 있어, 잠금 안에서 닫으면 다른 테넌트의 요청까지 멈춘다.
 *
 * <p><b>풀을 건드리지 않는 경로.</b> {@link #withDdl}·{@link #schemaOfTransient} 는 풀을 만들지도, LRU 순서를 바꾸지도 않는다.
 * 풀이 있으면 그 풀을 만들 때 읽어 둔 접속 정보를 쓰고, 없으면 저장소 행을 직접 읽는다 — 고아 staging 스윕 같은 배경 작업이 풀을 늘리거나 요청 중인 테넌트의
 * 풀을 밀어내지 않게 하기 위해서다. 캐시한 접속 정보를 먼저 쓰는 이유는 하나 더 있다: 롤백 보상·커밋 뒤 삭제는 PostgreSQL 트랜잭션이 끝난 뒤의 동기화 콜백에서
 * {@code withDdl} 을 부르므로, 그 시점에 PG 읽기에 기대지 않는 편이 안전하다.
 */
@Slf4j
@Component
public class ExternalStoreConnections {

  /** 커넥션을 받아 JDBC 작업을 수행한다. {@link SQLException} 은 호출부에서 Spring 예외로 감싸진다. */
  @FunctionalInterface
  public interface SqlWork<T> {
    T apply(Connection c) throws SQLException;
  }

  /** 트랜잭션 리소스 키 — 테넌트별로 하나의 묶인 커넥션을 구분한다. 이 컴포넌트 밖에서는 만들 수 없다. */
  private record BoundKey(long tenantId) {}

  /**
   * 묶인 커넥션과 커밋·정리 여부. 커밋되지 않은 채 끝나면 afterCompletion 이 롤백한다. {@code completed} 는 {@link
   * #rollbackBound} 가 먼저 정리했음을 뜻한다 — 그 뒤의 afterCompletion 은 아무것도 하지 않는다(이중 반납 방지).
   */
  private static final class BoundConnection {
    final Connection connection;
    boolean committed;
    boolean completed;

    BoundConnection(Connection connection) {
      this.connection = connection;
    }
  }

  /**
   * 테넌트 풀과, 그 풀을 만들 때 읽은 소유 계정 접속 정보(스키마 포함). 접속 정보는 DDL 직결 커넥션({@link #withDdl})이 PG 를 다시 읽지 않고 쓴다
   * — 풀은 READY 저장소에 대해서만 만들어지고 READY 는 되돌아가지 않으므로(R3) 풀이 살아 있는 동안 유효하다. 비밀번호는 Hikari 풀도 이미 메모리에 들고
   * 있으므로 노출 범위가 늘지 않으며, {@link OwnerCredentials#toString} 이 비밀번호를 빼므로 로그로 새지 않는다.
   */
  private record TenantPool(DataSource dataSource, OwnerCredentials owner) {
    String schema() {
      return owner.schema();
    }
  }

  /** 저장소 행에서 읽은 소유 계정 접속 정보. 복호화한 비밀번호를 담으므로 {@code toString} 에서 뺀다(로그·예외 메시지로 새지 않게). */
  private record OwnerCredentials(
      ExternalDbTarget target, String username, String password, String schema) {
    @Override
    public String toString() {
      return "OwnerCredentials[target=" + target + ", username=" + username + "]";
    }
  }

  private final TenantDataStoreRepository repository;
  private final EncryptionService encryption;
  private final ExternalStorePoolFactory factory;
  private final int maxPools;

  /** accessOrder=true 로 LRU 순서를 유지한다. 모든 접근은 이 객체의 모니터(synchronized) 안에서만 한다. */
  private final Map<Long, TenantPool> pools = new LinkedHashMap<>(16, 0.75f, true);

  /** 테넌트 id → 대여 중인 커넥션 수. 0 이면 항목을 지운다. */
  private final Map<Long, Integer> inUse = new HashMap<>();

  public ExternalStoreConnections(
      TenantDataStoreRepository repository,
      EncryptionService encryption,
      ExternalStorePoolFactory factory,
      @Value("${app.data-store.tibero.max-pools:8}") int maxPools) {
    this.repository = repository;
    this.encryption = encryption;
    this.factory = factory;
    this.maxPools = maxPools;
  }

  /** 테넌트 저장소의 스키마 이름({@code schema_name}). 풀이 없으면 READY 확인과 함께 만든다. */
  public String schemaOf(long tenantId) {
    return pool(tenantId, false).schema();
  }

  /**
   * 풀을 만들거나 LRU 순서를 바꾸지 않고 스키마 이름을 돌려준다. 풀이 있으면 그 캐시 값을, 없으면 저장소 행을 직접 읽는다(READY 확인 포함). 배경 작업용.
   */
  public String schemaOfTransient(long tenantId) {
    TenantPool existing;
    synchronized (this) {
      existing = peek(tenantId);
    }
    return existing != null ? existing.schema() : requireReadyRow(tenantId).schemaName();
  }

  /**
   * DML·조회용. 트랜잭션이 활성이면 테넌트별 묶인 커넥션(autoCommit=false)을 쓰고, 아니면 자동 커밋 커넥션을 호출마다 열고 닫는다.
   *
   * <p>실제 트랜잭션과 동기화가 모두 활성일 때만 묶는다 — 동기화만 켜진 읽기 전용·비트랜잭션 구간에서는 afterCompletion 이 오지 않을 수 있어 묶으면
   * 누수된다.
   *
   * <p><b>완료 단계에서 부를 때.</b> {@code afterCompletion} 에서는 Spring 이 동기화를 이미 비웠으므로 자동 커밋 경로로 간다. 묶인
   * 커넥션이 {@code beforeCommit} 에서 이미 커밋된 뒤({@code afterCommit}, 커밋 경로의 {@code beforeCompletion} 등)에
   * 부르면 그 커넥션을 다시 쓰지 않고 호출마다 자동 커밋 커넥션을 쓴다 — 다시 쓰면 작업이 아무도 커밋하지 않는 새 Tibero 트랜잭션에 들어가 정리 시점의 드라이버
   * 동작에 따라 커밋되거나 사라진다.
   *
   * <p><b>주의(감지하지 못하는 경우).</b> 이 트랜잭션에서 이 테넌트의 커넥션을 아직 한 번도 묶지 않은 채 {@code afterCommit} (예:
   * {@code @TransactionalEventListener} 기본 단계)에서 <b>처음</b> 부르면, Spring API 로는 완료 단계임을 알 수 없어 새로
   * 묶는다. 그 커넥션의 {@code beforeCommit} 은 이미 지나갔으므로 afterCompletion 이 롤백한다 — 읽기는 문제없지만 <b>쓰기는
   * 사라진다</b>. 커밋 뒤 쓰기가 필요하면 {@code REQUIRES_NEW} 트랜잭션 안에서 부르거나 트랜잭션 밖(비동기 등)에서 부른다.
   */
  public <T> T withDml(long tenantId, SqlWork<T> work) {
    if (TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive()) {
      return runBound(tenantId, work);
    }
    return runAutoCommit(tenantId, work);
  }

  /**
   * DDL 용. 트랜잭션 묶음과 무관하게 항상 <b>풀 밖의 단발 직결</b> 자동 커밋 커넥션을 열어 쓰고 닫는다(DDL 이 DML 을 암묵 커밋하지 않도록, 그리고 묶인
   * DML 커넥션과 풀을 다투지 않도록 — 클래스 Javadoc). 접속은 {@link ExternalStorePoolFactory#openDirect}(10초 접속 제한)를
   * 거치고, 열자마자 세션 NLS 설정을 적용한다.
   *
   * <p>풀 목록은 건드리지 않는다: 풀이 있으면 LRU 순서를 바꾸지 않고 그 풀의 접속 정보만 빌리고, 없으면 저장소 행을 읽되 풀을 만들지 않는다. 그래서 배경
   * 스윕(고아 staging 삭제)도 이 메서드를 그대로 쓴다(예전 {@code withDdlTransient} 와 합쳤다).
   */
  public <T> T withDdl(long tenantId, SqlWork<T> work) {
    OwnerCredentials owner;
    synchronized (this) {
      TenantPool existing = peek(tenantId);
      owner = existing != null ? existing.owner() : null;
    }
    if (owner == null) {
      // PG 읽기·복호화는 잠금 밖에서 한다(풀 생성 경로와 같은 규칙).
      owner = loadOwner(tenantId);
    }
    Connection c = openDirect(owner);
    try (c) {
      c.setAutoCommit(true);
      return work.apply(c);
    } catch (SQLException e) {
      throw wrap(e);
    }
  }

  /**
   * 소유 계정으로 풀 밖의 단발 커넥션을 열고 세션 NLS 설정을 적용한다. 설정이 실패하면 커넥션을 닫고 예외를 던진다(설정 안 된 세션으로 DDL 을 돌리지 않도록).
   */
  private Connection openDirect(OwnerCredentials owner) {
    Connection c;
    try {
      c = factory.openDirect(owner.target(), owner.username(), owner.password());
    } catch (ExternalDbConnectionException e) {
      // 메시지는 비밀번호를 가린 한국어 사유다.
      throw new DataAccessResourceFailureException(e.getMessage());
    }
    try {
      initSession(c);
      return c;
    } catch (SQLException e) {
      JdbcClose.closeQuietly(c);
      throw wrap(e);
    }
  }

  /**
   * 세션 NLS 설정({@link TiberoDialect#SESSION_INIT_SQL})을 실행한다. 풀 커넥션은 Hikari {@code
   * connectionInitSql} 로 같은 문장을 실행하므로({@link ExternalStorePoolFactory#create}) 두 경로의 세션이 같아진다.
   */
  static void initSession(Connection c) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(TiberoDialect.SESSION_INIT_SQL);
    }
  }

  /**
   * 이 테넌트의 묶인 커넥션이 있고 아직 커밋되지 않았으면 지금 롤백·종료·반납하고 묶음을 푼다. 멱등이다(없거나 이미 정리됐으면 아무것도 하지 않는다).
   *
   * <p>왜 필요한가: 롤백 보상(테이블·컬럼 DROP)은 {@code afterCompletion} 에서 별도 DDL 커넥션으로 실행되는데, 동기화 콜백은 등록 순서대로
   * 불린다. DDL 보상이 묶인 커넥션보다 먼저 등록됐으면(예: createTable → 행 쓰기 → 롤백) 묶인 커넥션이 아직 그 테이블의 행 잠금을 쥐고 있어 DROP 이
   * ORA-00054 로 실패한다. 보상이 DDL 전에 이 메서드를 불러 잠금을 먼저 푼다. 커밋된 커넥션(beforeCommit 성공 뒤 PG 커밋 실패)은 잠금이 이미
   * 풀렸으므로 건드리지 않고 afterCompletion 에 맡긴다.
   */
  public void rollbackBound(long tenantId) {
    BoundKey key = new BoundKey(tenantId);
    Object resource = TransactionSynchronizationManager.getResource(key);
    if (!(resource instanceof BoundConnection bound) || bound.completed || bound.committed) {
      return;
    }
    bound.completed = true;
    try {
      cleanUpBound(tenantId, bound);
    } finally {
      TransactionSynchronizationManager.unbindResourceIfPossible(key);
      release(tenantId);
    }
  }

  /**
   * 묶인 커넥션을 (커밋되지 않았으면) 롤백하고 autoCommit 을 되돌린 뒤 닫는다. 실패는 로그만 남긴다 — 드라이버 메시지에 접속 정보가 섞일 수 있어 예외 클래스
   * 이름만 남긴다({@link JdbcClose} 와 같은 규칙).
   */
  private static void cleanUpBound(long tenantId, BoundConnection bound) {
    if (!bound.committed) {
      try {
        bound.connection.rollback();
      } catch (SQLException e) {
        log.warn("테넌트 {} Tibero 롤백 실패: {}", tenantId, e.getClass().getName());
      }
    }
    try {
      bound.connection.setAutoCommit(true);
    } catch (SQLException e) {
      log.debug("테넌트 {} autoCommit 복원 실패(무시): {}", tenantId, e.getClass().getName());
    }
    JdbcClose.closeQuietly(bound.connection);
  }

  /** 컨텍스트 종료 시 남은 풀을 모두 닫는다. 목록은 잠금 안에서 비우고 닫기는 잠금 밖에서 한다. */
  @PreDestroy
  public void closeAll() {
    List<DataSource> toClose;
    synchronized (this) {
      toClose = new ArrayList<>();
      pools.values().forEach(p -> toClose.add(p.dataSource()));
      pools.clear();
      inUse.clear();
    }
    toClose.forEach(ExternalStoreConnections::closePool);
  }

  /** 풀에서 자동 커밋 커넥션을 빌려 작업하고, 끝나면 닫고 대여를 반납한다. */
  private <T> T runAutoCommit(long tenantId, SqlWork<T> work) {
    DataSource ds = lease(tenantId);
    try (Connection c = open(ds)) {
      c.setAutoCommit(true);
      return work.apply(c);
    } catch (SQLException e) {
      throw wrap(e);
    } finally {
      release(tenantId);
    }
  }

  private <T> T runBound(long tenantId, SqlWork<T> work) {
    BoundKey key = new BoundKey(tenantId);
    BoundConnection bound;
    if (TransactionSynchronizationManager.hasResource(key)) {
      bound = (BoundConnection) TransactionSynchronizationManager.getResource(key);
      if (bound.committed || bound.completed) {
        // 이미 커밋(또는 정리)된 묶인 커넥션 — 트랜잭션 완료 단계에서 불린 것이다(withDml Javadoc). 다시 쓰지 않는다.
        return runAutoCommit(tenantId, work);
      }
    } else {
      bound = bind(tenantId, key);
    }
    try {
      return work.apply(bound.connection);
    } catch (SQLException e) {
      throw wrap(e);
    }
  }

  /** 풀에서 커넥션을 꺼내 트랜잭션에 묶고 동기화를 등록한다. 대여는 afterCompletion 까지 유지된다. */
  private BoundConnection bind(long tenantId, BoundKey key) {
    DataSource ds = lease(tenantId);
    Connection c = null;
    try {
      c = open(ds);
      c.setAutoCommit(false);
      BoundConnection bound = new BoundConnection(c);
      TransactionSynchronizationManager.bindResource(key, bound);
      try {
        TransactionSynchronizationManager.registerSynchronization(
            new BoundSynchronization(tenantId, key, bound));
      } catch (RuntimeException e) {
        TransactionSynchronizationManager.unbindResourceIfPossible(key);
        throw e;
      }
      return bound;
    } catch (SQLException | RuntimeException e) {
      // 묶기에 실패하면 afterCompletion 이 오지 않으므로 여기서 직접 정리한다.
      JdbcClose.closeQuietly(c);
      release(tenantId);
      if (e instanceof SQLException se) {
        throw wrap(se);
      }
      throw (RuntimeException) e;
    }
  }

  /** 묶인 커넥션의 커밋·정리를 Spring 트랜잭션 수명에 맞춘다. */
  private final class BoundSynchronization implements TransactionSynchronization {
    private final long tenantId;
    private final BoundKey key;
    private final BoundConnection bound;

    BoundSynchronization(long tenantId, BoundKey key, BoundConnection bound) {
      this.tenantId = tenantId;
      this.key = key;
      this.bound = bound;
    }

    /** PostgreSQL 커밋 직전에 Tibero 를 커밋한다. 실패하면 예외로 PostgreSQL 도 롤백시킨다. */
    @Override
    public void beforeCommit(boolean readOnly) {
      try {
        bound.connection.commit();
        bound.committed = true;
      } catch (SQLException e) {
        throw wrap(e);
      }
    }

    /**
     * REQUIRES_NEW 로 바깥 트랜잭션이 일시중단될 때 묶인 리소스를 풀어 둔다. Spring 은 동기화 목록만 비우고 리소스는 그대로 두므로, 풀지 않으면 안쪽
     * 트랜잭션의 withDml 이 바깥 커넥션을 재사용해 안쪽 쓰기가 바깥 트랜잭션과 함께 커밋·롤백된다({@code
     * DataSourceUtils.ConnectionSynchronization} 와 같은 처리).
     */
    @Override
    public void suspend() {
      TransactionSynchronizationManager.unbindResourceIfPossible(key);
    }

    /** 일시중단이 끝나면 바깥 트랜잭션의 묶인 커넥션을 다시 묶는다. */
    @Override
    public void resume() {
      TransactionSynchronizationManager.bindResource(key, bound);
    }

    @Override
    public void afterCompletion(int status) {
      if (bound.completed) {
        // rollbackBound 가 이미 롤백·종료·반납했다 — 다시 하면 대여 카운트가 두 번 줄어든다.
        return;
      }
      bound.completed = true;
      try {
        if (bound.committed && status != STATUS_COMMITTED) {
          // Tibero 는 커밋됐는데 PostgreSQL 은 커밋되지 않은 단일 단계 커밋의 틈 — 두 저장소가 어긋났을 수 있다.
          log.warn("테넌트 {}: Tibero 는 커밋됐으나 PostgreSQL 트랜잭션은 커밋되지 않음(status={})", tenantId, status);
        }
        cleanUpBound(tenantId, bound);
      } finally {
        TransactionSynchronizationManager.unbindResourceIfPossible(key);
        release(tenantId);
      }
    }
  }

  private static Connection open(DataSource ds) {
    try {
      return ds.getConnection();
    } catch (SQLException e) {
      throw wrap(e);
    }
  }

  /**
   * {@link SQLException} 을 Spring 예외 계층으로 번역한다. SQL 문장·비밀이 메시지에 실리지 않도록 sql 인자는 null 로 두고 원인은 보존한다.
   * 번역기가 분류하지 못하면 {@link UncategorizedSQLException}.
   *
   * <p>왜 번역하는가: 예전에는 늘 {@code UncategorizedSQLException} 이라 중복 키·NOT NULL 위반·길이 초과가 500 이 됐다. 번역하면
   * {@code DataIntegrityViolationException}(→ GlobalExceptionHandler 409) 이 되고, {@code
   * DatasetService} 의 NOT NULL 변경 실패 처리(친화 메시지)도 PG 와 똑같이 동작한다.
   */
  static DataAccessException wrap(SQLException e) {
    DataAccessException translated = TRANSLATOR.translate("Tibero", null, e);
    return translated != null ? translated : new UncategorizedSQLException("Tibero", null, e);
  }

  /**
   * Tibero 오류 번역기. 먼저 {@link TiberoDialect} 의 명시 매핑(중복 키·NOT NULL 삽입·NOT NULL 켜기 실패·길이 초과 — 코드 또는
   * 메시지 표기)을 보고, 없으면 Spring 기본 {@code sql-error-codes.xml} 의 Oracle 항목(코드 표)을 쓴다. 그것도 못 하면 Spring
   * 기본 대체 번역(SQLException 하위 타입 → SQLState) 을 탄다. 실서버의 Tibero 코드는 ORA 와 다른 음수 자체 코드라(-10007 등)
   * Oracle 표로는 잡히지 않으므로 명시 매핑을 Oracle 표보다 앞에 둔다.
   */
  private static final SQLErrorCodeSQLExceptionTranslator TRANSLATOR =
      new SQLErrorCodeSQLExceptionTranslator("Oracle") {
        @Override
        protected DataAccessException customTranslate(String task, String sql, SQLException ex) {
          String message = buildMessage(task, sql, ex);
          if (TiberoErrors.hasAnyCode(ex, TiberoDialect.UNIQUE_VIOLATION)) {
            return new DuplicateKeyException(message, ex);
          }
          if (TiberoErrors.hasAnyCode(ex, TiberoDialect.INTEGRITY_VIOLATIONS)) {
            return new DataIntegrityViolationException(message, ex);
          }
          return null;
        }
      };

  /** 풀을 닫는다(Hikari 는 Closeable). 닫기 실패는 클래스 이름만 남긴다({@link JdbcClose}). */
  private static void closePool(DataSource ds) {
    if (ds instanceof AutoCloseable closeable) {
      JdbcClose.closeQuietly(closeable);
    }
  }

  /** 풀을 확보하고 대여 카운트를 올린다. 축출은 대여 중인 풀을 건너뛴다. */
  private DataSource lease(long tenantId) {
    return pool(tenantId, true).dataSource();
  }

  private synchronized void release(long tenantId) {
    inUse.compute(tenantId, (k, v) -> (v == null || v <= 1) ? null : v - 1);
  }

  /**
   * 풀을 돌려준다(LRU 갱신). {@code lease} 면 같은 잠금 안에서 대여 카운트도 올린다 — 등록과 대여 사이에 축출되지 않게 하기 위해서다.
   *
   * <p>없으면 저장소 행·계정 읽기와 복호화를 <b>잠금 밖에서</b> 한 뒤, 잠금 안에서 다시 확인하고(그새 다른 스레드가 만들었으면 그것을 쓴다) 풀 객체를 만들어
   * 등록한다. 풀 객체 생성은 접속하지 않으므로({@link ExternalStorePoolFactory#create}) 잠금 안에 두어 테넌트당 풀이 정확히 하나만 생기게
   * 한다. 상한을 넘겨 축출된 풀은 잠금을 나온 뒤 닫는다.
   */
  private TenantPool pool(long tenantId, boolean lease) {
    synchronized (this) {
      TenantPool existing = pools.get(tenantId);
      if (existing != null) {
        if (lease) {
          inUse.merge(tenantId, 1, Integer::sum);
        }
        return existing;
      }
    }
    OwnerCredentials owner = loadOwner(tenantId);
    TenantPool result;
    List<DataSource> evicted = List.of();
    synchronized (this) {
      result = pools.get(tenantId);
      if (result == null) {
        result =
            new TenantPool(
                factory.create(tenantId, owner.target(), owner.username(), owner.password()),
                owner);
        pools.put(tenantId, result);
        evicted = evictIfNeeded(tenantId);
      }
      if (lease) {
        inUse.merge(tenantId, 1, Integer::sum);
      }
    }
    evicted.forEach(ExternalStoreConnections::closePool);
    return result;
  }

  /**
   * LRU 순서를 바꾸지 않고 풀을 찾는다. {@code accessOrder=true} 인 {@link LinkedHashMap} 은 {@code get}·{@code
   * getOrDefault} 도 순서를 바꾸므로 항목을 훑는다(상한 {@code max-pools} 개라 짧다). 호출자가 잠금을 쥐고 있어야 한다.
   */
  private TenantPool peek(long tenantId) {
    if (!pools.containsKey(tenantId)) {
      return null;
    }
    for (Map.Entry<Long, TenantPool> e : pools.entrySet()) {
      if (e.getKey() == tenantId) {
        return e.getValue();
      }
    }
    return null;
  }

  /** READY 인 Tibero 저장소 행을 읽는다. 없거나 Tibero 가 아니면 {@link IllegalStateException}, READY 가 아니면 409. */
  private TenantDataStoreRow requireReadyRow(long tenantId) {
    TenantDataStoreRow row =
        repository
            .find(tenantId)
            .orElseThrow(() -> new IllegalStateException("외부 데이터셋 저장소가 없는 테넌트입니다: " + tenantId));
    if (row.backend() != DataStoreBackend.TIBERO) {
      throw new IllegalStateException("Tibero 저장소가 아닌 테넌트입니다: " + tenantId);
    }
    if (row.status() != TenantDataStoreStatus.READY) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "DATA_STORE_NOT_READY", "데이터셋 저장소 계정 확인이 끝나지 않았습니다");
    }
    return row;
  }

  /** READY 저장소 행과 소유 계정을 읽고 비밀번호를 복호화한다. 풀 생성과 단발 커넥션이 같은 규칙을 쓴다. 잠금 밖에서 부른다. */
  private OwnerCredentials loadOwner(long tenantId) {
    TenantDataStoreRow row = requireReadyRow(tenantId);
    StoredAccount owner =
        repository.findAccounts(tenantId).stream()
            .filter(a -> a.role() == DataStoreAccountRole.OWNER)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("저장소 소유 계정이 없는 테넌트입니다: " + tenantId));
    return new OwnerCredentials(
        new ExternalDbTarget(row.backend(), row.host(), row.port(), row.database()),
        owner.username(),
        encryption.decrypt(owner.encryptedPassword()),
        row.schemaName());
  }

  /**
   * 상한 초과 시 대여 중이 아닌 가장 오래된 풀을 목록에서 뺀다. 방금 만든 풀은 제외하고, 후보가 없으면 경고만 남긴다. 호출자가 잠금을 쥐고 있어야 하며, 돌려준 풀은
   * 잠금을 나온 뒤 닫아야 한다.
   */
  private List<DataSource> evictIfNeeded(long servingTenantId) {
    List<DataSource> evicted = new ArrayList<>();
    while (pools.size() > maxPools) {
      Long victim = null;
      for (Long id : pools.keySet()) {
        if (id != servingTenantId && inUse.getOrDefault(id, 0) == 0) {
          victim = id;
          break;
        }
      }
      if (victim == null) {
        log.warn("Tibero 테넌트 풀 상한({}) 초과 — 모두 사용 중이라 축출 생략 (현재 {}개)", maxPools, pools.size());
        break;
      }
      evicted.add(pools.remove(victim).dataSource());
      inUse.remove(victim);
    }
    return evicted;
  }
}
