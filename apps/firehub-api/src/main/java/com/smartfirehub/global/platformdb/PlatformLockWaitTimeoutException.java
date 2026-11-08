package com.smartfirehub.global.platformdb;

import org.springframework.dao.CannotAcquireLockException;

/**
 * 묶인 대기 트랜잭션 잠금({@link PlatformLocks#xactLock(String, java.time.Duration)})이 제한 시간 안에 잠금을 얻지 못했다는
 * 표시(WD-644 × WD-493).
 *
 * <p>왜 따로 두나: PG 는 대기 제한을 {@code lock_timeout} 으로 걸어 시간 초과가 SQLState {@code 55P03} 의 SQLException
 * 으로 오지만, Tibero {@code DBMS_LOCK.REQUEST} 는 시간 초과를 예외가 아니라 반환값 1 로 알린다. SQLState 가 없는 저장소의 시간 초과도
 * 호출자(커밋 뒤 PYTHON 읽기 동기화)가 "잠금이 바쁨" 으로 가를 수 있게 이 예외로 던진다. {@link CannotAcquireLockException} 을 이어받아
 * 표시를 모르는 호출자에게도 Spring 의 일시(Transient) 잠금 실패로 보인다.
 */
public class PlatformLockWaitTimeoutException extends CannotAcquireLockException {

  /**
   * @param message 잠금 키 대신 잠금 id 등 추적용 정보만 싣는다(키에는 테넌트 id·내부 잠금 이름이 들어간다)
   */
  public PlatformLockWaitTimeoutException(String message) {
    super(message);
  }
}
