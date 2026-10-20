package com.smartfirehub.dataset.storage.external;

import com.smartfirehub.dataset.storage.tibero.TiberoDialect;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * 테넌트 Tibero 소유 계정용 Hikari 풀·단발 커넥션 생성기(WD-74).
 *
 * <p>생성을 {@link ExternalStoreConnections} 에서 떼어 낸 이유: 단위 테스트에서 실제 Tibero 드라이버 없이 가짜 {@link
 * DataSource}· 커넥션으로 바꿔 끼우기 위해서다.
 */
@Component
public class ExternalStorePoolFactory {

  private final ExternalDbConnector connector;

  public ExternalStorePoolFactory(ExternalDbConnector connector) {
    this.connector = connector;
  }

  /**
   * 소유 계정 풀을 만든다. 반환 타입은 {@link DataSource} 지만 실체는 {@link HikariDataSource}(Closeable)다. 이 시점에는
   * 접속하지 않는다({@code initializationFailTimeout=-1}) — 호출자가 전역 잠금 안에서 불러도 외부 DB 를 기다리지 않는다.
   *
   * <p>풀 크기를 작게(4) 잡는 이유: 테넌트마다 별도 풀이 생기므로, 개수 상한({@code max-pools})과 곱해져 외부 DB 의 세션 한도를 넘지 않게 하기
   * 위해서다. {@code initializationFailTimeout=-1} 은 기동·풀 생성 시점에 외부 DB 가 잠시 내려가 있어도 풀 생성이 실패하지 않고 첫 사용
   * 때 검증하게 한다.
   */
  public DataSource create(
      long tenantId, ExternalDbTarget target, String username, String password) {
    HikariDataSource ds = new HikariDataSource();
    ds.setDriverClassName(TiberoDialect.DRIVER_CLASS);
    ds.setJdbcUrl(target.jdbcUrl());
    ds.setUsername(username);
    ds.setPassword(password);
    ds.setMaximumPoolSize(4);
    ds.setConnectionTimeout(10_000);
    ds.setIdleTimeout(600_000);
    ds.setConnectionTestQuery(TiberoDialect.VALIDATION_QUERY);
    // 커넥션마다 세션 NLS(날짜·타임스탬프 형식, 소수점 문자)를 고정한다 — DB·클라이언트 기본값에 따라 암묵 변환 결과가 달라지지 않게.
    // DDL 직결 커넥션도 같은 문장을 실행한다(ExternalStoreConnections#initSession).
    ds.setConnectionInitSql(TiberoDialect.SESSION_INIT_SQL);
    ds.setInitializationFailTimeout(-1);
    ds.setPoolName("tibero-tenant-" + tenantId + "-owner");
    return ds;
  }

  /**
   * 풀 없이 단발 커넥션을 연다(같은 URL·소유 계정). 모든 DDL({@link ExternalStoreConnections#withDdl})이 쓴다 — 묶인 DML
   * 커넥션과 풀을 다투지 않고, 고아 staging 스윕 같은 배경 작업이 풀을 새로 만들거나 LRU 순서를 흔들지 않게 하기 위해서다. 닫기와 세션 NLS 설정은 호출자
   * 책임이다.
   *
   * <p>{@code DriverManager} 를 직접 부르지 않고 {@link ExternalDbConnector} 를 거치는 이유: 드라이버 확인과 접속 시간
   * 제한(10초)을 그쪽이 이미 갖고 있다 — {@code DriverManager.setLoginTimeout} 은 JVM 전역이라 여기서 따로 걸 수 없고, 제한이 없으면
   * 응답 없는 외부 DB 하나가 스케줄러 스레드를 붙잡는다. 실패 메시지에는 비밀번호가 없다.
   */
  public Connection openDirect(ExternalDbTarget target, String username, String password)
      throws ExternalDbConnectionException {
    return connector.open(target, username, password);
  }
}
