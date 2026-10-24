package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.DbError;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SqlRunner}·{@link ProbeRunner} 가 함께 쓰는 "커넥션을 빌려 기한 안에서 실행" 뼈대. 실행기가 바뀌어도 대여·기한·실패 규칙이 두
 * 곳에서 갈라지지 않게 여기 한 곳에 둔다.
 *
 * <ol>
 *   <li>기한이 이미 지났으면 커넥션을 빌리지 않는다(기술자 조회에서 기한을 다 썼다).
 *   <li>대여 실패: 접속·인증 실패면 그 테넌트의 기술자 캐시를 지운다 — 비밀번호가 바뀌었으면 다음 요청이 새 기술자로 풀을 다시 만든다. 풀 고갈 대기 시간 초과는
 *       기술자 탓이 아니므로 지우지 않는다({@link #isConnectFailure}). 풀·드라이버 오류 문구에는 풀 이름·host:port 가 들어 있어 응답에는
 *       고정 문구만 싣는다.
 *   <li>빌린 뒤 기한이 지났으면 실행하지 않는다(풀 대여 대기에서 기한을 다 썼다).
 *   <li>실패(시간 초과·DB 오류·예기치 않은 오류)면 먼저 ROLLBACK 한 뒤 응답을 만든다. 기한이 지난 뒤의 DB 오류는 cancel 로 생긴 것이라 시간 초과로
 *       본다. 성공 경로의 커밋·롤백은 작업({@link Work})이 정한다.
 * </ol>
 *
 * <p>로그: 응답에는 고정 문구만 싣는 대신 운영 진단용 원인을 서버 로그에 남긴다. 대여 실패는 WARN 으로 원인 체인의 클래스와 비밀번호를 가린 메시지만 남기고 예외
 * 객체는 넘기지 않는다(SLF4J 는 원인 체인을 가리지 않은 원문으로 찍는다). 예기치 않은 오류는 내부 결함이라 스택과 함께 ERROR 로 남기되 메시지에 SQL 원문은
 * 넣지 않는다.
 */
final class LeasedExecution {

  private static final Logger log = LoggerFactory.getLogger(LeasedExecution.class);

  /** 로그에 남길 원인 체인의 최대 단계 수(드라이버가 만든 비정상적으로 긴 체인 방지). */
  private static final int MAX_CAUSE_DEPTH = 5;

  static final String CONNECT_FAILED = "테넌트 저장소에 연결하지 못했습니다.";
  static final String UNEXPECTED = "SQL 실행 중 예기치 않은 오류가 발생했습니다.";

  /** 빌린 커넥션으로 하는 일. 성공 응답을 돌려준다. */
  @FunctionalInterface
  interface Work<R> {
    R run(Connection c) throws SQLException, Deadline.TimedOut;
  }

  /** 실패 종류별 응답. 대여 후 실패는 ROLLBACK 이 끝난 뒤 불린다. */
  interface Failures<R> {
    /** 대여 전에 기한이 지났다. */
    R expiredBeforeBorrow();

    /** 커넥션을 빌리지 못했다(접속·인증 실패 등). */
    R connectFailed();

    /**
     * 풀이 꽉 차 대기 시간 안에 커넥션을 못 얻었다(기술자 탓이 아님). 기본은 {@link #connectFailed()} 와 같은 응답이다 — OWNER·RO 경로는
     * 기존 문구를 유지하고, 슬롯 스트리밍만 「동시 조회가 많다」로 구분한다(Ruling 17).
     */
    default R poolExhausted() {
      return connectFailed();
    }

    /** 대여 후 시간 초과(기한 경과·cancel). */
    R timedOut();

    /** DB 오류. {@code message} 는 비밀번호를 가린 드라이버 문구다. */
    R dbError(String message, DbError error);

    /** 예기치 않은 오류(내부 결함). */
    R unexpected();
  }

  /** 실패 응답 하나를 만드는 함수 — 사용자에게 보일 문구와 DB 오류(없으면 null)로 응답을 만든다. */
  @FunctionalInterface
  interface FailureResponse<R> {
    R of(String message, DbError error);
  }

  /**
   * 실패 응답이 「문구 + DB 오류」 하나뿐인 실행기(적재·분석 조회·컬럼 탐지·슬롯 스트리밍)가 함께 쓰는 {@link Failures}. 실행기마다 같은 익명 클래스를
   * 다섯 메서드씩 되풀이하지 않게 여기서 한 번 만든다. 대여 전·후 시간 초과는 같은 문구, 접속 실패·예기치 않은 오류는 고정 문구, DB 오류는 가린 드라이버 문구와
   * 오류 코드를 싣는다. ({@link SqlRunner} 는 실패 종류마다 다른 실행 로그 줄을 쌓으므로 이 묶음을 쓰지 않는다.)
   *
   * @param timeoutMessage 시간 초과 문구(대여 전 기한 경과·대여 후 시간 초과 공통)
   * @param poolBusyMessage 풀 고갈 문구 — null 이면 접속 실패와 같은 응답이다({@link Failures#poolExhausted()} 기본값)
   * @param fail 문구·DB 오류로 응답을 만드는 함수(보통 {@code XxxResponse::failure})
   */
  static <R> Failures<R> failures(
      String timeoutMessage, String poolBusyMessage, FailureResponse<R> fail) {
    return new Failures<>() {
      @Override
      public R expiredBeforeBorrow() {
        return fail.of(timeoutMessage, null);
      }

      @Override
      public R connectFailed() {
        return fail.of(CONNECT_FAILED, null);
      }

      @Override
      public R poolExhausted() {
        return poolBusyMessage == null ? connectFailed() : fail.of(poolBusyMessage, null);
      }

      @Override
      public R timedOut() {
        return fail.of(timeoutMessage, null);
      }

      @Override
      public R dbError(String message, DbError error) {
        return fail.of(message, error);
      }

      @Override
      public R unexpected() {
        return fail.of(UNEXPECTED, null);
      }
    };
  }

  /** 커넥션 대여 방법 — 보관소·슬롯 선택을 호출자가 정한다(슬롯 풀은 {@code (d) -> slotPools.borrow(d, k)}). */
  @FunctionalInterface
  interface Borrower {
    TenantPools.Lease borrow(StoreDescriptor d) throws SQLException;
  }

  private final Borrower borrower;
  private final StoreDescriptorClient descriptors;

  LeasedExecution(TenantPools pools, StoreDescriptorClient descriptors) {
    this(pools::borrow, descriptors);
  }

  LeasedExecution(Borrower borrower, StoreDescriptorClient descriptors) {
    this.borrower = borrower;
    this.descriptors = descriptors;
  }

  /** 기한 확인 → 대여 → 기한 확인 → {@code work}. 오류는 예외가 아니라 {@code failures} 가 만든 응답으로 돌려준다. */
  <R> R withLease(StoreDescriptor d, Deadline deadline, Failures<R> failures, Work<R> work) {
    if (deadline.expired()) {
      return failures.expiredBeforeBorrow();
    }
    TenantPools.Lease lease;
    try {
      lease = borrower.borrow(d);
    } catch (SQLException | RuntimeException e) {
      boolean connectFailure = isConnectFailure(e);
      // 예외 객체는 넘기지 않는다 — 원인 체인의 드라이버 메시지에 비밀번호·접속 문자열이 섞일 수 있어 가린 문자열만 남긴다.
      log.warn(
          "테넌트 저장소 커넥션 대여 실패 tenant={} 종류={} 원인={}",
          d.tenantId(),
          connectFailure ? "접속·인증 실패" : "풀 대기 시간 초과",
          describeCauses(e, d));
      if (connectFailure) {
        // 로그인 실패면 기술자가 낡았을 수 있다(비밀번호 교체) — 캐시를 지워 다음 요청이 새로 받게 한다.
        descriptors.invalidate(d.tenantId());
        return failures.connectFailed();
      }
      return failures.poolExhausted();
    }
    try (lease) {
      Connection c = lease.connection();
      try {
        if (deadline.expired()) {
          throw new Deadline.TimedOut();
        }
        return work.run(c);
      } catch (Deadline.TimedOut e) {
        rollbackQuietly(c);
        return failures.timedOut();
      } catch (SQLException e) {
        // 판정은 롤백 전에 한다 — 롤백에 걸린 시간 때문에 DB 오류가 시간 초과로 바뀌지 않게.
        boolean expired = deadline.expired();
        rollbackQuietly(c);
        if (expired) {
          // cancel 로 생긴 오류다 — 드라이버 메시지 대신 시간 초과 문구로 돌려준다.
          return failures.timedOut();
        }
        String message = scrub(e.getMessage(), d);
        return failures.dbError(message, new DbError(e.getErrorCode(), e.getSQLState(), message));
      } catch (RuntimeException e) {
        rollbackQuietly(c);
        // 내부 결함이라 스택이 필요하다(접속 정보가 섞이는 경로가 아니다). SQL 원문은 메시지에 넣지 않는다.
        log.error("SQL 실행 중 예기치 않은 오류 tenant={}", d.tenantId(), e);
        return failures.unexpected();
      }
    }
  }

  /**
   * 대여 실패가 접속·인증 실패(기술자를 지울 이유)인지. Hikari 는 풀 고갈로 {@code connectionTimeout} 을 넘기면 원인 없는 {@link
   * SQLTransientConnectionException} 을 던지고, 로그인 실패로 넘기면 같은 예외에 드라이버의 {@link SQLException} 을 원인으로
   * 붙인다. 앞의 경우만 빼고 나머지(드라이버·풀 생성 오류 포함)는 접속 실패로 본다 — 부하로 풀이 찬 순간마다 기술자를 지우면 api 조회가 몰린다.
   */
  static boolean isConnectFailure(Exception e) {
    return !(e instanceof SQLTransientConnectionException) || e.getCause() instanceof SQLException;
  }

  static void rollbackQuietly(Connection c) {
    try {
      c.rollback();
    } catch (SQLException | RuntimeException ignored) {
      // 끊긴 커넥션이면 롤백도 실패한다 — 풀 반납 시 Hikari 가 커밋되지 않은 작업을 버린다.
    }
  }

  /**
   * 원인 체인을 「클래스: 가린 메시지 &lt;- 클래스: 가린 메시지 …」 한 줄로 만든다(로그용). 단계마다 {@link #scrub} 으로 비밀번호를 가리고, 순환
   * 체인·지나치게 긴 체인은 {@link #MAX_CAUSE_DEPTH} 단계에서 끊는다.
   */
  static String describeCauses(Throwable e, StoreDescriptor d) {
    StringBuilder sb = new StringBuilder();
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Throwable t = e;
    int depth = 0;
    while (t != null && depth < MAX_CAUSE_DEPTH && seen.add(t)) {
      if (depth > 0) {
        sb.append(" <- ");
      }
      sb.append(t.getClass().getName()).append(": ").append(scrub(t.getMessage(), d));
      t = t.getCause();
      depth++;
    }
    return sb.toString();
  }

  /** 드라이버 메시지에 비밀번호가 섞여 있으면 가린다(방어적, 정상 경로에서는 일어나지 않는다). */
  static String scrub(String message, StoreDescriptor d) {
    String m = message == null || message.isBlank() ? "알 수 없는 데이터베이스 오류" : message;
    m = mask(m, d.owner());
    // 읽기 전용 계정 접속 실패 메시지에 RO 비밀번호가 섞일 수 있어 같이 가린다.
    m = mask(m, d.ro());
    // 슬롯 계정(WD-78) 접속 실패 메시지에도 같은 규칙.
    if (d.slots() != null) {
      for (StoreDescriptor.SlotAccount s : d.slots()) {
        String password = s == null ? null : s.password();
        if (password != null && !password.isEmpty()) {
          m = m.replace(password, "****");
        }
      }
    }
    return m;
  }

  private static String mask(String m, StoreDescriptor.Account a) {
    String password = a == null ? null : a.password();
    return password != null && !password.isEmpty() ? m.replace(password, "****") : m;
  }
}
