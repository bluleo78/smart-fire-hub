package com.smartfirehub.support;

import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * "Tibero JDBC 드라이버가 없을 때" 동작을 실제 클래스패스로 검증하는 통합 테스트용 가정.
 *
 * <p>왜 필요한가: 드라이버 jar 는 커밋하지 않고 {@code apps/firehub-api/libs/} 에 선택적으로 둔다. jar 를 둔 개발 머신(실서버
 * 확인용)에서는 드라이버가 있으므로 "드라이버 없음" 시나리오가 성립하지 않는다 — 그때는 실패 대신 건너뛴다. 드라이버 없음 메시지 자체는 가짜 드라이버 클래스명을 주입하는
 * 단위 테스트({@code JdbcExternalDbConnectorTest})가 늘 검증한다.
 */
public final class TiberoDriverAssumptions {

  private static final String DRIVER_CLASS = "com.tmax.tibero.jdbc.TbDriver";

  private TiberoDriverAssumptions() {}

  /** 테스트 클래스패스에 Tibero 드라이버가 있으면 테스트를 건너뛴다. */
  public static void assumeDriverAbsent() {
    assumeFalse(isDriverPresent(), "Tibero JDBC 드라이버가 클래스패스에 있어 '드라이버 없음' 시나리오를 건너뜀");
  }

  private static boolean isDriverPresent() {
    try {
      Class.forName(DRIVER_CLASS, false, TiberoDriverAssumptions.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
