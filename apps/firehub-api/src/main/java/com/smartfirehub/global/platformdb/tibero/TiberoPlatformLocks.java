package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.PlatformLockWaitTimeoutException;
import com.smartfirehub.global.platformdb.PlatformLocks;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * {@link PlatformLocks} 의 Tibero 구현(WD-493) — {@code SYS.DBMS_LOCK} 의 사용자 잠금으로 PG advisory 잠금을
 * 대신한다.
 *
 * <p><b>잠금 id.</b> Tibero 는 사용자 잠금 id 로 {@code 1,073,741,824} 미만을 거절하고(실측: 반환 3), 2,000,000,000 이상은
 * 예약 범위로 본다(Oracle 과 같은 규칙). 그래서 {@link #MIN_ID}..{@link #MAX_ID} 만 쓴다. 앞쪽 {@link #LONG_KEY_SPACE}
 * 칸은 정수 키, 나머지는 문자열 키의 SHA-256 앞 8바이트 해시로 채워 두 키 공간이 섞이지 않게 한다(PG 의 "정수 키·문자열 해시 공간 분리"와 같은 성질).
 * {@code ALLOCATE_UNIQUE} 는 쓰지 않는다 — 현재 트랜잭션을 커밋해 버리므로 트랜잭션 잠금 용도로 쓸 수 없다.
 *
 * <p><b>스키마 이름공간.</b> DBMS_LOCK 사용자 잠금은 인스턴스 전역이다 — 같은 Tibero 인스턴스에 설치본·계정·실서버 테스트 계정이 여럿 있으면 같은 키가
 * 같은 id 를 써서 서로를 막는다(PG advisory 잠금은 DB 별이라 이런 일이 없다). 그래서 기동 때 플랫폼 계정의 현재 스키마를 한 번 읽어 id 에 섞는다.
 * 문자열 키는 {@code 스키마 + ":" + 키} 를 해시한다. 정수 키는 칸 안에서 스키마 해시만큼 돌린 자리({@code (키 mod 칸 + 스키마 오프셋) mod
 * 칸})를 쓴다 — 같은 스키마 안에서는 예전처럼 칸 크기만큼 떨어지지 않은 정수 키끼리 절대 겹치지 않고, 스키마가 다르면 다른 id 가 된다. 스키마가 다른 두 키가 우연히
 * 같은 id 가 되는 일은 남지만, 그 결과는 불필요한 대기일 뿐 정합성 문제는 아니다.
 *
 * <p><b>트랜잭션 잠금.</b> {@code release_on_commit => TRUE} 로 요청해 커밋·롤백 때 풀린다. 호출자의 Spring 트랜잭션 커넥션에서
 * 잡아야 하므로 {@link DataSourceUtils#getConnection} 으로 @Primary 플랫폼 풀의 묶인 커넥션을 얻는다(@Primary {@code
 * TenantAwareTransactionManager} 가 같은 DataSource 로 트랜잭션을 묶는다). 그 DataSource 가 현재 트랜잭션에 묶여 있지 않으면
 * 거절한다 — 트랜잭션 동기화만 켜져 있고 이 풀이 묶이지 않았으면 {@code DataSourceUtils} 가 자동 커밋 새 커넥션을 내줘 잠그자마자 풀리기 때문이다.
 *
 * <p><b>세션 잠금.</b> {@code release_on_commit => FALSE}, timeout 0 — 기다리지 않는 시도형이며 커밋에도 남는다. 해제는 키별
 * {@code RELEASE} 다(PG 의 {@code unlock_all} 과 달리 다른 키는 그대로 둔다 — 계약은 "그 키만 풀면 된다").
 *
 * <p><b>권한.</b> 플랫폼 계정에 {@code GRANT EXECUTE ON SYS.DBMS_LOCK} 이 필요하다(ADR 0001). 없으면 첫 잠금 때가 아니라 기동
 * 때 {@link #afterPropertiesSet} 이 안내와 함께 멈춘다. 스키마도 같은 때 읽으므로 이 빈은 초기화가 끝난 뒤에만 잠글 수 있다.
 */
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformLocks implements PlatformLocks, InitializingBean {

  /** Tibero 가 받는 사용자 잠금 id 하한(실측: 이보다 작으면 반환 3). */
  static final int MIN_ID = 1_073_741_824;

  /** 사용자 잠금 id 상한 — 2,000,000,000 이상은 예약 범위. */
  static final int MAX_ID = 1_999_999_999;

  /** 정수 키가 차지하는 앞쪽 칸 수 — 문자열 키는 그 뒤를 쓴다. */
  static final int LONG_KEY_SPACE = 1_000_000;

  /** 권한이 없을 때 운영자에게 보여 줄 안내. */
  static final String MISSING_GRANT_MESSAGE =
      "플랫폼 계정에 GRANT EXECUTE ON SYS.DBMS_LOCK 이 필요합니다(ADR 0001)";

  /** 기동 권한 확인에 쓰는 키 — 다른 잠금 키와 겹치지 않는 고정 이름. */
  static final String GRANT_CHECK_KEY = "fh_dbms_lock_grant_check";

  /** 권한 없음 오류 코드(Tibero 는 음수로 보낸다, 절댓값으로 비교). */
  static final int MISSING_GRANT_ERROR = 15146;

  /** 트랜잭션 잠금 대기 상한(초) — DBMS_LOCK.MAXWAIT(32767)는 "끝없이 기다림"이다. PG 의 xact 잠금과 같은 무기한 대기. */
  static final int XACT_TIMEOUT_SECONDS = 32767;

  /** 시도형 트랜잭션 잠금({@link #tryXactLock(String, Duration)})의 재시도 간격(밀리초) — PG 구현과 같은 50ms. */
  static final long TRY_XACT_POLL_MILLIS = 50;

  /** 배타 모드(X_MODE). */
  private static final int X_MODE = 6;

  private static final String REQUEST_XACT_SQL = requestSql(true);
  private static final String REQUEST_SESSION_SQL = requestSql(false);
  private static final String RELEASE_SQL =
      "DECLARE r INTEGER; BEGIN r := DBMS_LOCK.RELEASE(id => ?); ? := r; END;";

  /** 현재 스키마 조회(실서버에서 접속 계정 스키마를 돌려줌을 확인). */
  static final String CURRENT_SCHEMA_SQL =
      "SELECT SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL";

  /**
   * @Primary 플랫폼 풀 — 트랜잭션 잠금은 여기서 호출자 트랜잭션에 묶인 커넥션을 얻는다.
   */
  private final DataSource dataSource;

  /** 트랜잭션 잠금의 SQLException 을 스프링 예외로 옮기는 Tibero 번역기(잠금 실패 코드 포함). */
  private final SQLExceptionTranslator translator = TiberoSqlExceptionTranslator.create();

  /** 잠금 id 이름공간 — 기동 때({@link #afterPropertiesSet}) 한 번 읽은 플랫폼 계정의 현재 스키마. 그 전에는 null. */
  private volatile String schema;

  /**
   * 플랫폼 잠금을 만든다.
   *
   * @param dataSource @Primary 플랫폼 풀 — 트랜잭션 매니저가 묶는 바로 그 인스턴스여야 {@code xactLock} 이 호출자 트랜잭션 커넥션에서
   *     잡힌다(다른 풀이면 자동 커밋 커넥션에서 잡혀 곧바로 풀린다). 기동 권한 확인도 이 풀에서 한다.
   */
  public TiberoPlatformLocks(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * 문자열 키로 호출자 트랜잭션에 묶인 배타 잠금을 기다려 잡는다(커밋·롤백 때 풀림).
   *
   * <p>오류 메시지에는 키 문자열을 싣지 않고 "문자열 키" 와 id 만 남긴다 — 키에는 테넌트 id·내부 잠금 이름이 들어가고 길이 제한도 없어, 사용자 응답·로그로
   * 새어 나가면 내부 구조를 드러내거나 로그를 부풀린다. id 만으로도 서버 쪽({@code V$LOCK} 등)에서 추적할 수 있다.
   */
  @Override
  public void xactLock(String key) {
    xactLockId(lockId(requireSchema(), key));
  }

  /** 정수 키로 호출자 트랜잭션에 묶인 배타 잠금을 기다려 잡는다(커밋·롤백 때 풀림). */
  @Override
  public void xactLock(long key) {
    xactLockId(lockId(requireSchema(), key));
  }

  /**
   * 호출자 트랜잭션의 커넥션에서 커밋 해제형 배타 잠금을 기다려 잡는다.
   *
   * <p>이 풀(DataSource)이 현재 트랜잭션에 묶여 있어야 한다. {@code isActualTransactionActive()} 만 보면 다른 DataSource
   * 의 트랜잭션 안이거나 동기화만 켜진 상태에서도 통과해, {@code DataSourceUtils} 가 이 풀의 자동 커밋 새 커넥션을 내주고 잠금이 문장 끝에 바로
   * 풀린다. 메시지에는 키 대신 id 만 싣는다(위 {@link #xactLock(String)} 설명 참고).
   */
  private void xactLockId(int id) {
    requireBoundTransaction(id);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      checkXactResult(request(connection, REQUEST_XACT_SQL, id, XACT_TIMEOUT_SECONDS));
    } catch (SQLException e) {
      throw translateXact(e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /**
   * 문자열 키 트랜잭션 잠금의 시도형(WD-624) — 호출자 트랜잭션 커넥션에서 기다리지 않는 커밋 해제형 요청(timeout 0)을 {@link
   * #TRY_XACT_POLL_MILLIS} 간격으로 {@code maxWait} 까지 되풀이한다.
   *
   * <p>왜 DBMS_LOCK 의 timeout 인자로 한 번에 기다리지 않나: 인자가 초 단위 정수라 1초 미만 상한을 표현할 수 없고, 인터럽트에도 반응하지 않는다. 짧은
   * 시도를 되풀이하면 PG 구현과 같은 의미(상한·인터럽트 시 false)를 그대로 지킨다. 못 잡아도 호출자 트랜잭션은 그대로 쓸 수 있다(Tibero 는 반환값 1 로
   * 끝날 뿐 트랜잭션을 깨뜨리지 않는다). 메시지에는 키 대신 id 만 싣는다({@link #xactLock(String)} 설명 참고).
   */
  @Override
  public boolean tryXactLock(String key, Duration maxWait) {
    int id = lockId(requireSchema(), key);
    requireBoundTransaction(id);
    long deadline = System.nanoTime() + maxWait.toNanos();
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      while (true) {
        if (checkTryResult(request(connection, REQUEST_XACT_SQL, id, 0))) {
          return true;
        }
        if (System.nanoTime() >= deadline) {
          return false;
        }
        try {
          Thread.sleep(TRY_XACT_POLL_MILLIS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    } catch (SQLException e) {
      throw translateXact(e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /**
   * 문자열 키 트랜잭션 잠금의 기다리지 않는 시도형(WD-644) — 호출자 트랜잭션 커넥션에서 커밋 해제형 요청을 timeout 0 으로 <b>한 번</b>만 한다. 커밋
   * 뒤 PYTHON 읽기 동기화 실행기가 처음 몇 번에 쓴다(바쁜 테넌트가 단일 스레드를 붙잡지 않게). 못 잡아도 호출자 트랜잭션은 그대로다(반환값 1 은 예외가 아니다).
   * 메시지에는 키 대신 id 만 싣는다({@link #xactLock(String)} 설명 참고).
   *
   * @return 잡았으면(0) 또는 이 트랜잭션이 이미 쥐었으면(4) true, 다른 세션이 쥐고 있으면(1) false
   */
  @Override
  public boolean tryXactLock(String key) {
    int id = lockId(requireSchema(), key);
    requireBoundTransaction(id);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    try {
      return checkTryResult(request(connection, REQUEST_XACT_SQL, id, 0));
    } catch (SQLException e) {
      throw translateXact(e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
  }

  /**
   * 문자열 키 트랜잭션 잠금의 묶인 대기(WD-644) — DBMS_LOCK 의 대기 줄에 서서 최대 {@code timeout} 기다린다. 시도형을 되풀이하는 {@link
   * #tryXactLock(String, Duration)} 과 달리 대기 줄에 서므로, 잠금이 쉴 새 없이 쓰이는 테넌트에서도 굶지 않는다(커밋 뒤 실행기가 시도형 몇 번
   * 뒤에 이 방식으로 격상한다).
   *
   * <p>DBMS_LOCK 의 timeout 인자는 초 단위 정수다 — {@link #boundedWaitSeconds} 로 올림한다(1초 미만은 1초). 시간 초과(반환값
   * 1)는 SQLState 가 없으므로 {@link PlatformLockWaitTimeoutException} 으로 던져 호출자가 "바쁨" 으로 가르게 한다(PG 의
   * 55P03 과 같은 자리). 교착(2)·잘못된 호출은 {@link #checkXactResult} 규칙 그대로다. 메시지에는 키 대신 id 만 싣는다.
   */
  @Override
  public void xactLock(String key, Duration timeout) {
    int id = lockId(requireSchema(), key);
    requireBoundTransaction(id);
    Connection connection = DataSourceUtils.getConnection(dataSource);
    int code;
    try {
      code = request(connection, REQUEST_XACT_SQL, id, boundedWaitSeconds(timeout));
    } catch (SQLException e) {
      throw translateXact(e);
    } finally {
      DataSourceUtils.releaseConnection(connection, dataSource);
    }
    if (code == 1) {
      throw new PlatformLockWaitTimeoutException(
          "Tibero 트랜잭션 잠금 대기 시간 초과(id " + id + ", 제한 " + timeout + ")");
    }
    checkXactResult(code);
  }

  /**
   * 묶인 대기 시간 → DBMS_LOCK timeout(초). 초 단위 정수만 받으므로 올림하고(1초 미만 → 1초, 0 이하도 1초 — 0 이면 시도형이 되어 줄에 서지
   * 않는다), 무기한 대기 값({@link #XACT_TIMEOUT_SECONDS})보다 작게 묶는다.
   */
  static int boundedWaitSeconds(Duration timeout) {
    long millis = Math.max(1, timeout.toMillis());
    long seconds = (millis + 999) / 1000;
    return (int) Math.min(seconds, XACT_TIMEOUT_SECONDS - 1);
  }

  /**
   * 이 풀(DataSource)이 현재 트랜잭션에 묶여 있는지 확인한다 — 묶이지 않았으면 {@code DataSourceUtils} 가 자동 커밋 새 커넥션을 내줘 잠금이
   * 문장 끝에 바로 풀리므로 거절한다(위 {@link #xactLockId} 설명 참고).
   */
  private void requireBoundTransaction(int id) {
    if (!TransactionSynchronizationManager.hasResource(dataSource)) {
      throw new IllegalStateException(
          "Tibero 플랫폼 트랜잭션 잠금은 플랫폼 DataSource 트랜잭션 안에서만 잡을 수 있습니다(id " + id + ")");
    }
  }

  /**
   * 트랜잭션 잠금 요청의 SQLException 을 스프링 예외로 옮긴다. 번역기는 SQL 문 없이 부른다 — 메시지에 문장을 싣지 않는다. 표에 없는 코드면 null 이 올
   * 수 있어 직접 감싼다.
   */
  private RuntimeException translateXact(SQLException e) {
    DataAccessException translated = translator.translate("DBMS_LOCK.REQUEST", null, e);
    if (translated != null) {
      return translated;
    }
    return new IllegalStateException("Tibero 트랜잭션 잠금 실패(오류 코드 " + e.getErrorCode() + ")", e);
  }

  @Override
  public boolean trySessionLock(Connection connection, String key) throws SQLException {
    return checkTryResult(
        request(connection, REQUEST_SESSION_SQL, lockId(requireSchema(), key), 0));
  }

  @Override
  public void releaseSessionLock(Connection connection, String key) throws SQLException {
    checkReleaseResult(release(connection, lockId(requireSchema(), key)));
  }

  /**
   * 기동 때 잠금 id 이름공간(현재 스키마)을 읽고 DBMS_LOCK 실행 권한을 확인한다. 권한이 없으면 첫 잠금(가입·파이프라인 실행 등) 때 사용자 요청이 실패하는
   * 대신 여기서 안내와 함께 멈춘다.
   *
   * <p>확인 키를 다른 인스턴스가 쥐고 있으면(반환 1) 호출 자체가 됐으므로 권한은 있다. 이 세션이 쥐었으면(0 잡음, 4 이미 쥠) 바로 푼다 — 풀에 돌려줄 세션에
   * 확인 키 잠금을 남기면 다른 인스턴스의 확인이 계속 1 을 받는다. 확인 키도 스키마 이름공간 안의 id 다(같은 인스턴스의 다른 설치본과 겹치지 않게).
   */
  @Override
  public void afterPropertiesSet() {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      String resolved = currentSchema(connection);
      int id = lockId(resolved, GRANT_CHECK_KEY);
      // 세션 시도와 같은 판정 — 잡았으면(0·4) 바로 풀고, 1(남이 쥠)은 통과, 그 밖은 checkTryResult 가 던진다.
      if (checkTryResult(request(connection, REQUEST_SESSION_SQL, id, 0))) {
        checkReleaseResult(release(connection, id));
      }
      this.schema = resolved;
    } catch (SQLException e) {
      if (Math.abs(e.getErrorCode()) == MISSING_GRANT_ERROR) {
        throw new IllegalStateException(MISSING_GRANT_MESSAGE, e);
      }
      throw new IllegalStateException("DBMS_LOCK 확인 실패(오류 코드 " + e.getErrorCode() + ")", e);
    }
  }

  /**
   * 커넥션 세션의 현재 스키마 이름을 {@code SYS_CONTEXT('USERENV','CURRENT_SCHEMA')} 로 읽는다 — 잠금 id·알림 이름의 이름공간이다.
   * 비면 이름공간 없이 잠그면 안 되므로 예외를 던진다.
   */
  static String currentSchema(Connection connection) throws SQLException {
    String value = queryString(connection, CURRENT_SCHEMA_SQL);
    if (value == null || value.isBlank()) {
      throw new SQLException("Tibero 현재 스키마를 알 수 없습니다");
    }
    return value;
  }

  /** 기동 때 읽은 스키마 — 초기화 전이면 이름공간 없는 id 로 잠그지 않도록 바로 실패한다. */
  private String requireSchema() {
    String s = schema;
    if (s == null) {
      throw new IllegalStateException(
          "TiberoPlatformLocks 가 초기화되지 않았습니다(afterPropertiesSet 전에는 잠글 수 없음)");
    }
    return s;
  }

  // ── id 매핑·반환값 판정(패키지 전용, 단위 테스트 대상) ─────────────────

  /**
   * 정수 키 → 앞쪽 {@link #LONG_KEY_SPACE} 칸. 칸 안 자리({@code 키 mod 칸}, 음수도 칸 안)를 스키마 오프셋만큼 돌린다 — 같은
   * 스키마에서는 예전처럼 서로 다른 자리가 서로 다른 id 이고(겹침 없음), 스키마가 다르면 같은 키도 다른 id 가 된다. 오프셋은 {@code 스키마 + "#L"} 의
   * SHA-256 앞 8바이트라 문자열 키 해시({@code 스키마 + ":" + 키})와 입력이 겹치지 않는다.
   */
  static int lockId(String schema, long key) {
    long offset = Math.floorMod(TiberoNameHash.hash64(schema + "#L"), (long) LONG_KEY_SPACE);
    long slot = Math.floorMod(key, (long) LONG_KEY_SPACE);
    return MIN_ID + (int) Math.floorMod(slot + offset, (long) LONG_KEY_SPACE);
  }

  /**
   * 문자열 키 → 정수 칸 뒤의 나머지 공간. {@code 스키마 + ":" + 키} 의 SHA-256 앞 8바이트라 JVM·인스턴스가 달라도 같은 스키마·키면 같은 id 가
   * 나오고, 스키마가 다르면 (해시 충돌이 아닌 한) 다른 id 가 된다.
   */
  static int lockId(String schema, String key) {
    long h = TiberoNameHash.hash64(schema + ":" + key);
    long stringSpace = (long) MAX_ID - MIN_ID - LONG_KEY_SPACE + 1; // 925,258,176
    return (int) (MIN_ID + LONG_KEY_SPACE + Math.floorMod(h, stringSpace));
  }

  /** 트랜잭션 잠금 반환값 — 0 잡음, 4 이미 쥠(재진입), 1 시간 초과·2 교착은 잠금 실패, 그 밖은 잘못된 호출. */
  static void checkXactResult(int code) {
    switch (code) {
      case 0, 4 -> {}
      case 1, 2 ->
          throw new CannotAcquireLockException(
              "Tibero 트랜잭션 잠금을 얻지 못했습니다(DBMS_LOCK 반환값 " + code + ")");
      default ->
          throw new IllegalStateException("Tibero 트랜잭션 잠금 요청 오류(DBMS_LOCK 반환값 " + code + ")");
    }
  }

  /** 세션 잠금 시도 반환값 — 0·4 잡음, 1 다른 세션이 쥠, 그 밖은 잘못된 호출. */
  static boolean checkTryResult(int code) {
    return switch (code) {
      case 0, 4 -> true;
      case 1 -> false;
      default -> throw new IllegalStateException("Tibero 세션 잠금 요청 오류(DBMS_LOCK 반환값 " + code + ")");
    };
  }

  /** 해제 반환값 — 0 풀림, 4 쥐지 않음(계약상 예외 없이 끝나야 한다), 그 밖은 SQLException. */
  static void checkReleaseResult(int code) throws SQLException {
    if (code != 0 && code != 4) {
      throw new SQLException("Tibero 잠금 해제 오류(DBMS_LOCK 반환값 " + code + ")");
    }
  }

  // ── 내부 ──────────────────────────────────────────────

  /** REQUEST 문장 — 커밋 해제 여부만 다르다(PL/SQL BOOLEAN 은 바인드할 수 없어 문장에 고정). */
  private static String requestSql(boolean releaseOnCommit) {
    return "DECLARE r INTEGER; BEGIN r := DBMS_LOCK.REQUEST(id => ?, lockmode => "
        + X_MODE
        + ", timeout => ?, release_on_commit => "
        + (releaseOnCommit ? "TRUE" : "FALSE")
        + "); ? := r; END;";
  }

  /**
   * 주어진 커넥션에서 DBMS_LOCK.REQUEST 를 부르고 반환값(0·1·2·3·4·5)을 그대로 돌려준다. 판정은 호출자가 용도별 check* 로 한다 — 같은
   * 반환값도 트랜잭션 잠금(1 = 실패 예외)과 세션 시도(1 = false)에서 뜻이 달라서다. 함수 반환값은 PL/SQL 블록의 OUT 바인드로 받는다(BOOLEAN 인자
   * 때문에 {@code {? = call}} 형식을 쓸 수 없다).
   */
  private static int request(Connection connection, String sql, int id, int timeoutSeconds)
      throws SQLException {
    try (CallableStatement cs = connection.prepareCall(sql)) {
      cs.setInt(1, id);
      cs.setInt(2, timeoutSeconds);
      cs.registerOutParameter(3, Types.INTEGER);
      cs.execute();
      return cs.getInt(3);
    }
  }

  /**
   * 주어진 커넥션 세션이 쥔 그 id 의 잠금을 DBMS_LOCK.RELEASE 로 풀고 반환값(0 풀림, 4 쥐지 않음 등)을 돌려준다. 판정은 {@link
   * #checkReleaseResult} 가 한다 — 쥐지 않은 키 해제(4)를 예외 없이 넘기는 계약을 한곳에 두기 위해서다.
   */
  private static int release(Connection connection, int id) throws SQLException {
    try (CallableStatement cs = connection.prepareCall(RELEASE_SQL)) {
      cs.setInt(1, id);
      cs.registerOutParameter(2, Types.INTEGER);
      cs.execute();
      return cs.getInt(2);
    }
  }

  /** 한 칸짜리 조회의 첫 값을 문자열로 돌려준다(행이 없으면 null). */
  private static String queryString(Connection connection, String sql) throws SQLException {
    try (Statement st = connection.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }
}
