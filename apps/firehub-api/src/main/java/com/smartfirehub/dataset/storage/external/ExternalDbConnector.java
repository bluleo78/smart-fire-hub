package com.smartfirehub.dataset.storage.external;

import java.sql.Connection;
import java.time.Duration;

/**
 * 외부 DB 접속 추상화. 테스트에서 가짜로 바꿔 끼우기 위해 인터페이스로 분리한다.
 *
 * <p>반환된 커넥션의 종료 책임은 호출자에게 있다.
 */
public interface ExternalDbConnector {

  /**
   * 주어진 자격으로 접속한다.
   *
   * @throws ExternalDbConnectionException 드라이버 없음·시간 초과·접속 실패. 메시지에 비밀번호는 없다.
   */
  Connection open(ExternalDbTarget target, String username, String password)
      throws ExternalDbConnectionException;

  /**
   * {@link #open(ExternalDbTarget, String, String)} 와 같되 접속 대기 상한을 부르는 쪽이 정한다(WD-664 6) — 사용자 트랜잭션의
   * 커밋 직전처럼 플랫폼 커넥션을 쥔 채 접속하는 경로가 기본 상한보다 짧게 포기하게 한다. 구현의 기본 상한보다 긴 값은 기본 상한으로 줄인다.
   *
   * <p>기본 구현은 상한을 무시하고 기본 접속을 쓴다 — 시간 제한을 걸 수 없는 테스트용 가짜를 위한 것이다. 운영 구현({@link
   * JdbcExternalDbConnector})은 재정의한다.
   *
   * @param timeout 접속 대기 상한(양수)
   */
  default Connection open(
      ExternalDbTarget target, String username, String password, Duration timeout)
      throws ExternalDbConnectionException {
    return open(target, username, password);
  }
}
