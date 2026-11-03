package com.smartfirehub.global.platformdb.postgres;

import com.smartfirehub.dataset.service.DataExceptionTranslator;
import com.smartfirehub.global.platformdb.DbErrorDetail;
import com.smartfirehub.global.platformdb.PlatformDbErrors;
import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.stereotype.Component;

/**
 * {@link PlatformDbErrors} 의 PostgreSQL 구현 — pgjdbc 의 {@link ServerErrorMessage} 와 PG 영문 메시지 문구로
 * 판정한다.
 *
 * <p>전역 예외 처리기·각 서비스에 흩어져 있던 판정을 분기·문구 그대로 옮겼다. 연쇄 예외까지 보는 탐색은 {@link
 * DataExceptionTranslator#findServerError} 를 재사용한다(OOS 호출자와 공유하므로 그쪽은 그대로 둔다).
 */
@Component
public class PostgresPlatformDbErrors implements PlatformDbErrors {

  /** PG insufficient_privilege — RLS 정책 위반이 이 SQLState 로 온다. */
  private static final String RLS_DENIED_SQLSTATE = "42501";

  @Override
  public Optional<DbErrorDetail> findError(Throwable e) {
    return Optional.ofNullable(DataExceptionTranslator.findServerError(e))
        .map(PostgresPlatformDbErrors::toDetail);
  }

  /**
   * getCause 만 걷는다 — 연쇄 예외(getNextException)는 보지 않는다. 이미 본 예외로 돌아오면(순환) 멈춘다. 노드마다 서버 오류의 제약 이름을 비교해
   * 하나라도 같으면 true 다 — 첫 제약 이름만 보면 바깥 PSQLException 이 다른 제약을 달 때 결과가 달라지므로 기존 술어와 똑같이 맞춘다.
   */
  @Override
  public boolean violatesConstraintOnCauseChain(Throwable e, String constraintName) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    for (Throwable t = e; t != null && seen.add(t); t = t.getCause()) {
      if (t instanceof PSQLException pe
          && pe.getServerErrorMessage() != null
          && constraintName.equals(pe.getServerErrorMessage().getConstraint())) {
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean isRowSecurityDenied(Throwable mostSpecificCause) {
    return mostSpecificCause instanceof SQLException sqle
        && RLS_DENIED_SQLSTATE.equals(sqle.getSQLState());
  }

  /** 분류(중복/외래 키/체크)는 직접 원인 메시지로 판정한다. 외래 키는 방향(삭제·연결)을 서버 구조화 오류까지 보고 가린다. */
  @Override
  public IntegrityViolation classifyIntegrityViolation(Throwable integrityViolation) {
    if (integrityViolation.getCause() == null) {
      return IntegrityViolation.UNCLASSIFIED;
    }
    String causeMsg = integrityViolation.getCause().getMessage();
    // 원인 메시지가 없으면 문구로 가를 근거가 없다.
    if (causeMsg == null) {
      return IntegrityViolation.UNCLASSIFIED;
    }
    if (causeMsg.contains("duplicate key")) {
      return IntegrityViolation.DUPLICATE_KEY;
    } else if (causeMsg.contains("foreign key")) {
      return classifyForeignKey(
          causeMsg, DataExceptionTranslator.findServerError(integrityViolation));
    } else if (causeMsg.contains("check constraint")) {
      return IntegrityViolation.CHECK_CONSTRAINT;
    }
    // 위 분류 어디에도 들지 않는 원인은 UNCLASSIFIED — 호출자가 원문을 응답에 붙이지 않는다.
    return IntegrityViolation.UNCLASSIFIED;
  }

  /**
   * 외래 키 위반(23503)의 방향을 가른다(WD-99).
   *
   * <ul>
   *   <li>삭제·키 변경 쪽: 메시지 {@code update or delete on table}, 세부 {@code is still referenced}
   *   <li>삽입·수정 쪽: 메시지 {@code insert or update on table}, 세부 {@code is not present}
   * </ul>
   */
  private static IntegrityViolation classifyForeignKey(
      String causeMsg, ServerErrorMessage serverError) {
    StringBuilder text = new StringBuilder(causeMsg);
    if (serverError != null) {
      text.append('\n').append(Objects.toString(serverError.getMessage(), ""));
      text.append('\n').append(Objects.toString(serverError.getDetail(), ""));
    }
    String all = text.toString();
    if (all.contains("still referenced") || all.contains("update or delete on table")) {
      return IntegrityViolation.FOREIGN_KEY_IN_USE;
    }
    if (all.contains("is not present") || all.contains("insert or update on table")) {
      return IntegrityViolation.FOREIGN_KEY_MISSING_TARGET;
    }
    return IntegrityViolation.FOREIGN_KEY_OTHER;
  }

  /** pgjdbc 구조화 오류를 벤더 중립 값으로 옮긴다. */
  private static DbErrorDetail toDetail(ServerErrorMessage sem) {
    return new DbErrorDetail(
        sem.getSQLState(), sem.getConstraint(), sem.getColumn(), sem.getMessage(), sem.getDetail());
  }
}
