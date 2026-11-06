package com.smartfirehub.global.platformdb.tibero;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.smartfirehub.global.platformdb.ConditionalOnTiberoPlatformDb;
import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformDbErrors} 의 Tibero 구현(WD-496) — Tibero 고유의 음수 오류 코드와 메시지의 객체 이름으로 판정한다.
 *
 * <p>오류 코드는 모두 실서버 측정값이다(2026-11-06 — 같은 판정을 {@code TiberoPlatformDbLiveTest} 가 실서버에서 다시 확인한다. 다중 행
 * 삽입 중복 -4008 은 데이터 저장소 측정값 {@code TiberoDialect.MULTI_INSERT_DUPLICATE}). Tibero 는 무결성 위반을
 * SQLState {@code 23000} 하나로만 보내므로 종류는 코드로 가른다. 호출자(전역 예외 처리기 등)가 PG 와 같은 판정을 쓰도록 {@link
 * DbErrorDetail} 의 SQLState 는 같은 뜻의 PostgreSQL 값으로 옮겨 담는다.
 *
 * <p>객체 이름: 메시지에 {@code ('스키마'.'제약')} 또는 {@code ('스키마'.'테이블'.'열')} 꼴로 대문자 이름이 실린다(따옴표 없이 만든 객체).
 * 호출자는 PG 처럼 소문자 이름(예: {@code idx_async_job_active_unique})으로 비교하므로 소문자로 옮긴다.
 *
 * <p>행 보안(RLS): Tibero 에는 PG 의 RLS 가 없다. 테넌트 격리는 WD-492 가 다른 방식으로 만든다 — 그래서 {@link
 * #isRowSecurityDenied} 는 언제나 false 다.
 */
@Component
@ConditionalOnTiberoPlatformDb
public class TiberoPlatformDbErrors implements PlatformDbErrors {

  /** 유일 제약(기본 키 포함) 위반. */
  static final int UNIQUE_VIOLATION = -10007;

  /** 다중 행 삽입(INSERT ALL 등) 중의 유일 위반 — 단건과 같은 중복 키로 본다. */
  static final int MULTI_INSERT_DUPLICATE = -4008;

  /** 외래 키 위반 — 삽입·수정 쪽(참조 대상 없음, "primary key not found"). */
  static final int FK_MISSING_TARGET = -10008;

  /** 외래 키 위반 — 삭제·부모 키 변경 쪽(다른 곳에서 참조 중, "foreign key exists"). */
  static final int FK_IN_USE = -10009;

  /** 체크 제약 위반. */
  static final int CHECK_VIOLATION = -10006;

  /** NOT NULL 위반 — Tibero 는 빈 문자열('')도 NULL 로 저장하므로 빈 문자열을 넣어도 이 코드다. */
  static final int NOT_NULL_VIOLATION = -10005;

  /** 열 길이 초과. */
  static final int VALUE_TOO_LARGE = -11048;

  /** {@code FOR UPDATE NOWAIT} 잠금 실패. */
  static final int LOCK_NOWAIT_FAILED = -12033;

  /** {@code FOR UPDATE WAIT n} 잠금 대기 시간 초과. */
  static final int LOCK_WAIT_TIMEOUT = -12034;

  /** 원인 체인 탐색 깊이 상한 — 순환 체인에서도 끝나게 한다(PG 구현과 같은 범위). */
  private static final int MAX_DEPTH = 16;

  /** 메시지의 {@code ('A'.'B')} 또는 {@code ('A'.'B'.'C')} — 스키마·제약 또는 스키마·테이블·열. */
  private static final Pattern QUOTED_NAMES =
      Pattern.compile("\\('([^']+)'\\.'([^']+)'(?:\\.'([^']+)')?\\)");

  /** 길이 초과 메시지의 {@code "스키마"."테이블"."열"}. */
  private static final Pattern DOUBLE_QUOTED_COLUMN =
      Pattern.compile("\"([^\"]+)\"\\.\"([^\"]+)\"\\.\"([^\"]+)\"");

  @Override
  public Optional<DbErrorDetail> findError(Throwable e) {
    return Optional.ofNullable(findTiberoError(e)).map(TiberoPlatformDbErrors::toDetail);
  }

  /** getCause 만 걷는다(PG 구현과 같은 계약). 한 노드라도 제약 이름이 같으면 true. 이미 본 예외로 돌아오면 멈춘다. */
  @Override
  public boolean violatesConstraintOnCauseChain(Throwable e, String constraintName) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
      if (t instanceof SQLException sqle && isTibero(sqle)) {
        String constraint = toDetail(sqle).constraint();
        if (constraint != null && constraint.equalsIgnoreCase(constraintName)) {
          return true;
        }
      }
    }
    return false;
  }

  @Override
  public boolean isRowSecurityDenied(Throwable mostSpecificCause) {
    return false;
  }

  /** 종류는 코드로 가른다 — Tibero 는 외래 키 방향을 코드로 나눠 보내므로 PG 처럼 세부 메시지를 볼 필요가 없다. */
  @Override
  public IntegrityViolation classifyIntegrityViolation(Throwable integrityViolation) {
    SQLException sqle = findTiberoError(integrityViolation);
    if (sqle == null) {
      return IntegrityViolation.UNCLASSIFIED;
    }
    return switch (sqle.getErrorCode()) {
      case UNIQUE_VIOLATION, MULTI_INSERT_DUPLICATE -> IntegrityViolation.DUPLICATE_KEY;
      case FK_IN_USE -> IntegrityViolation.FOREIGN_KEY_IN_USE;
      case FK_MISSING_TARGET -> IntegrityViolation.FOREIGN_KEY_MISSING_TARGET;
      case CHECK_VIOLATION -> IntegrityViolation.CHECK_CONSTRAINT;
      default -> IntegrityViolation.UNCLASSIFIED;
    };
  }

  /**
   * 원인 체인(getCause·getNextException)에서 처음 만나는 Tibero 서버 오류. 깊이 상한과 이미 본 예외 집합으로 순환에서도 끝난다.
   *
   * @return 없으면 null
   */
  static SQLException findTiberoError(Throwable e) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    Deque<Throwable> queue = new ArrayDeque<>();
    if (e != null) queue.add(e);
    int visited = 0;
    while (!queue.isEmpty() && visited++ < MAX_DEPTH) {
      Throwable t = queue.poll();
      if (!seen.add(t)) continue;
      if (t instanceof SQLException sqle) {
        if (isTibero(sqle)) return sqle;
        if (sqle.getNextException() != null) queue.add(sqle.getNextException());
      }
      if (t.getCause() != null) queue.add(t.getCause());
    }
    return null;
  }

  /** Tibero 서버 오류인가 — 고유 코드는 음수이고 메시지가 {@code JDBC-<코드>:} 로 시작한다. */
  private static boolean isTibero(SQLException e) {
    String msg = e.getMessage();
    return e.getErrorCode() < 0 && msg != null && msg.startsWith("JDBC" + e.getErrorCode() + ":");
  }

  /** 코드·메시지를 벤더 중립 값으로 옮긴다. 메시지·세부는 판정용이고 사용자 응답에는 싣지 않는다. */
  static DbErrorDetail toDetail(SQLException e) {
    int code = e.getErrorCode();
    String msg = e.getMessage();
    String constraint = null;
    String column = null;
    Matcher quoted = msg == null ? null : QUOTED_NAMES.matcher(msg);
    if (quoted != null && quoted.find()) {
      if (quoted.group(3) == null) {
        constraint = lower(quoted.group(2));
      } else {
        column = lower(quoted.group(3));
      }
    } else if (msg != null) {
      Matcher col = DOUBLE_QUOTED_COLUMN.matcher(msg);
      if (col.find()) column = lower(col.group(3));
    }
    return new DbErrorDetail(sqlState(code), constraint, column, msg, null);
  }

  /** Tibero 코드를 같은 뜻의 PostgreSQL SQLState 로 옮긴다. 플랫폼 판정에 필요한 것만 여기서 더하고, 나머지는 데이터 저장소 대응표를 쓴다. */
  static String sqlState(int code) {
    return switch (code) {
      case FK_MISSING_TARGET, FK_IN_USE -> "23503";
      case CHECK_VIOLATION -> "23514";
      case LOCK_NOWAIT_FAILED, LOCK_WAIT_TIMEOUT -> "55P03";
      default -> TiberoDialect.sqlState(code);
    };
  }

  private static String lower(String name) {
    return name.toLowerCase(Locale.ROOT);
  }
}
