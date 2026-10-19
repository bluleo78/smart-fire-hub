package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.dataset.storage.DataStoreBackend;

/**
 * 접속할 외부 DB 의 위치(WD-73). 호스트·포트·SID 만 담고 자격 증명은 담지 않는다.
 *
 * <p>호스트·SID 는 JDBC URL 에 그대로 들어가므로 호출자(요청 검증)가 정규식으로 미리 걸러야 한다.
 */
public record ExternalDbTarget(DataStoreBackend backend, String host, int port, String database) {

  /**
   * JDBC URL 을 만든다. 현재는 Tibero 만 지원한다.
   *
   * @throws IllegalArgumentException Tibero 가 아닌 백엔드
   */
  public String jdbcUrl() {
    if (backend != DataStoreBackend.TIBERO) {
      throw new IllegalArgumentException("지원하지 않는 저장소 백엔드입니다: " + backend);
    }
    return "jdbc:tibero:thin:@" + host + ":" + port + ":" + database;
  }
}
