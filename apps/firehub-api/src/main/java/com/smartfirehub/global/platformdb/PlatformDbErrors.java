package com.smartfirehub.global.platformdb;

import java.util.Optional;

/**
 * 플랫폼 DB 오류 판정 — 예외가 어떤 제약 위반인지·행 보안 거부인지를 DB 벤더에 의존하지 않고 묻는 창구.
 *
 * <p>호출자는 {@code org.postgresql} 타입이나 PG 영문 메시지 문구를 직접 보지 않고 이 인터페이스로 판정한다. 제약·열 이름 같은 스키마 리터럴은
 * 호출자가 쥐고, 여기서는 "무슨 오류인가"만 답한다. 구현은 DB 별로 있다({@code PostgresPlatformDbErrors}).
 */
public interface PlatformDbErrors {

  /** 데이터 무결성 위반의 종류 — 전역 예외 처리기가 사용자 문구를 고르는 데 쓴다. 원문은 응답에 싣지 않는다. */
  enum IntegrityViolation {
    /** 유일 제약 중복. */
    DUPLICATE_KEY,
    /** 외래 키 위반 — 삭제·키 변경 쪽(다른 곳에서 참조 중). */
    FOREIGN_KEY_IN_USE,
    /** 외래 키 위반 — 삽입·수정 쪽(연결 대상 없음). */
    FOREIGN_KEY_MISSING_TARGET,
    /** 외래 키 위반이지만 방향을 가릴 수 없음. */
    FOREIGN_KEY_OTHER,
    /** 체크 제약 위반. */
    CHECK_CONSTRAINT,
    /** 위 어디에도 들지 않음. */
    UNCLASSIFIED
  }

  /**
   * 원인 체인에서 DB 가 보고한 구조화 오류를 찾는다.
   *
   * <p>탐색 계약: getCause 와 SQLException.getNextException 을 함께 따라가고, 깊이 상한을 둬 순환 체인에서도 끝난다 ({@code
   * DataExceptionTranslator.findServerError} 와 같은 탐색). 다른 DB 구현도 이 범위를 그대로 지켜야 호출자 판정이 같다.
   */
  Optional<DbErrorDetail> findError(Throwable e);

  /** 위반된 제약 이름 — findError(e).map(constraint). */
  default Optional<String> violatedConstraint(Throwable e) {
    return findError(e).map(DbErrorDetail::constraint);
  }

  /**
   * 원인 체인(getCause 만, getNextException 은 보지 않는다) 어딘가의 서버 오류가 해당 제약을 위반했는가. 이미 본 예외로 돌아오면(순환) 멈춘다.
   * 체인의 한 노드라도 제약 이름이 같으면 true — 바깥 예외가 다른 제약을 달고 있어도 안쪽이 맞으면 true 다(진행 중 임포트 충돌 판정의 기존 술어 그대로).
   */
  boolean violatesConstraintOnCauseChain(Throwable e, String constraintName);

  /** 행 보안(RLS) 거부인가 — 가장 깊은 원인이 SQLException 이고 SQLState 42501. */
  boolean isRowSecurityDenied(Throwable mostSpecificCause);

  /**
   * 무결성 위반 예외(예: DataIntegrityViolationException)의 종류를 가른다. 직접 원인(getCause)의 메시지 문구와 서버 구조화 오류의
   * 메시지·세부로 판정한다.
   */
  IntegrityViolation classifyIntegrityViolation(Throwable integrityViolation);
}
