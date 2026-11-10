package com.smartfirehub.support;

import com.smartfirehub.global.datastore.SeparateDataStoreDbConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.springframework.core.env.Environment;

/**
 * 데이터 저장소에 풀 밖 원시 연결을 여는 테스트 도우미(WD-704).
 *
 * <p>왜: 일부 동시성 테스트는 풀 밖 연결로 데이터 테이블에 {@code LOCK TABLE} 을 걸고 {@code pg_stat_activity} 로 잠금 대기를
 * 관찰한다. 예전에는 {@code spring.datasource.*}(플랫폼 DB)로 열었는데, 분리 모드({@code separateDataStoreTest})에서는 데이터
 * 테이블과 질의가 두 번째 컨테이너에 있어 잠글 테이블이 없고 대기도 보이지 않는다. 데이터 저장소 런타임 풀과 같은 접속 정보({@code
 * firehub.data-store.postgres.url}·{@code password}, 롤 {@code app_tenant})로 열면 기본 모드(별칭 — 플랫폼 DB 와
 * 같은 값)와 분리 모드 모두에서 질의가 도는 DB 에 붙는다.
 *
 * <p>{@link PostgresTestContainer#dataStoreJdbcUrl()} 대신 Spring 환경 값을 읽는 이유: 전역 분리 모드 표시와 상관없이 이
 * 테스트 컨텍스트가 실제로 받은 데이터 저장소 설정을 따르기 위해서다.
 */
public final class DataStoreTestConnections {

  private DataStoreTestConnections() {}

  /**
   * 데이터 저장소 런타임 롤로 자동 커밋을 끈 원시 연결을 연다 — 호출자가 잠금을 쥔 채 두다가 롤백으로 푼다.
   *
   * @param env 테스트 컨텍스트 환경(분리 모드면 데이터 저장소 키가 두 번째 컨테이너를 가리킨다)
   */
  public static Connection openRuntime(Environment env) throws SQLException {
    Connection c =
        DriverManager.getConnection(
            env.getRequiredProperty("firehub.data-store.postgres.url"),
            // 데이터 저장소 런타임 풀과 같은 롤 — 상수를 따로 두면 롤 이름이 바뀔 때 어긋난다.
            SeparateDataStoreDbConfig.RUNTIME_USERNAME,
            env.getRequiredProperty("firehub.data-store.postgres.password"));
    try {
      c.setAutoCommit(false);
      return c;
    } catch (SQLException | RuntimeException e) {
      // 풀 밖 연결이라 실패하면 여기서 닫는다 — 새면 데이터 저장소 서버의 연결 한도를 갉아먹는다.
      c.close();
      throw e;
    }
  }
}
