package com.smartfirehub.dataset.storage.external;

import java.sql.Connection;

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
}
