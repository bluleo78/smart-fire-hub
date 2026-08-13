package com.smartfirehub.global.config;

import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * 지정한 DB 롤(role)의 실제 비밀번호를 애플리케이션 설정값(환경변수)과 동기화한다.
 *
 * <p>배경: V32 마이그레이션이 {@code pipeline_executor} 롤을, V83 마이그레이션이 {@code app_tenant}
 * 롤을 각각 평문 리터럴 비밀번호로 하드코딩 생성했는데, 클라이언트(api/executor)에는 환경변수로 다른
 * 값(예: k8s 시크릿 랜덤 생성)을 주입하는 환경이 있다. {@code CREATE ROLE} 이 {@code IF NOT EXISTS}
 * 로 가드되어 두 번째 부팅부터는 재실행조차 되지 않으므로, 이후 어떤 마이그레이션도 비밀번호를 갱신하지
 * 않는다. 그 결과 운영자가 실제 비밀번호(예: {@code POSTGRES_RUNTIME_PASSWORD})를 설정해도 DB 안의
 * 비밀번호는 그대로 남아 "비밀번호를 바꿨는데 오히려 인증이 깨지는" 정반대의 결과가 난다. 특히
 * {@code app_tenant} 는 {@code initialization-fail-timeout: -1} 설정 때문에 이 동기화가 없으면 앱이
 * "정상 기동한 것처럼" 보이면서 DB 를 쓰는 모든 요청이 500 으로 실패하는, 기동 실패보다 훨씬 진단하기
 * 어려운 증상으로 나타난다.
 *
 * <p>비밀번호는 마이그레이션(1회성 버전 관리)이 아니라 Flyway migrate 실행마다(=매 앱 기동마다)
 * 동기화해야 로테이션에도 대응할 수 있어, 마이그레이션 SQL이 아닌 콜백으로 구현한다. {@code
 * AFTER_MIGRATE} 는 대기 중인 마이그레이션이 없어도 migrate() 호출 시 항상 실행되므로, 매 기동마다
 * 현재 환경변수 값으로 ALTER ROLE 이 재실행된다(멱등).
 */
public class RolePasswordSyncCallback extends BaseCallback {

  private final String roleName;
  private final String password;

  public RolePasswordSyncCallback(String roleName, String password) {
    this.roleName = roleName;
    this.password = password;
  }

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.AFTER_MIGRATE;
  }

  @Override
  public void handle(Event event, Context context) {
    // 비밀번호는 트러스트된 환경변수 값이지만, SQL 리터럴 삽입이라 홑따옴표는 이스케이프한다.
    String escapedPassword = password.replace("'", "''");
    // 롤 이름도 식별자로 안전하게 삽입하기 위해 큰따옴표로 감싸고 내부 큰따옴표를 두 배로 이스케이프한다.
    // 오늘은 롤 이름이 고정된 소문자 리터럴이라 문제가 없지만, 이후 단계에서 테넌트별 롤
    // (예: pipeline_executor_t{id})처럼 동적으로 생성되는 이름이 들어올 수 있어 미리 방어한다.
    String quotedRoleName = "\"" + roleName.replace("\"", "\"\"") + "\"";
    try (Statement stmt = context.getConnection().createStatement()) {
      stmt.execute("ALTER ROLE " + quotedRoleName + " PASSWORD '" + escapedPassword + "'");
    } catch (SQLException e) {
      throw new IllegalStateException(roleName + " 비밀번호 동기화 실패", e);
    }
  }

  @Override
  public String getCallbackName() {
    return roleName + "PasswordSync";
  }
}
