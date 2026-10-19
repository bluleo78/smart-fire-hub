package com.smartfirehub.dataset.storage.external;

import lombok.extern.slf4j.Slf4j;

/**
 * 외부 DB JDBC 자원을 조용히 닫는 공용 도우미(WD-73).
 *
 * <p>연결 테스트({@link DataStoreConnectionTester})와 접속기({@link JdbcExternalDbConnector})가 각자 들고 있던 "닫기
 * 실패는 삼키고 로그" 코드를 한곳으로 모았다. 닫기 실패는 이미 끝난 검사·접속 결과와 무관하므로 호출자에게 던지지 않는다. 계정 실행기({@link
 * ExternalAccountProvisioner})는 try-with-resources 로 닫고 그 실패를 직접 처리하므로 쓰지 않는다.
 *
 * <p>로그에는 예외 클래스 이름만 남긴다 — 드라이버 메시지에 접속 문자열·SQL({@code IDENTIFIED BY} 포함)이 섞일 수 있어, 예외 객체를 넘기면
 * SLF4J 가 메시지와 스택을 함께 찍기 때문이다.
 */
@Slf4j
final class JdbcClose {

  private JdbcClose() {}

  /** {@code resource} 를 닫는다. null 이면 아무것도 하지 않고, 닫기 실패는 클래스 이름만 WARN 으로 남긴다. */
  static void closeQuietly(AutoCloseable resource) {
    if (resource == null) {
      return;
    }
    try {
      resource.close();
    } catch (Exception e) {
      log.warn("외부 DB 자원 닫기 실패: {}", e.getClass().getName());
    }
  }
}
