package com.smartfirehub.global.config;

import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.callback.BaseCallback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * pipeline_executor DB 롤의 실제 비밀번호를 PIPELINE_EXECUTOR_PASSWORD 환경변수 값과 동기화한다.
 *
 * <p>배경: V32 마이그레이션이 롤을 'pipeline_exec_pwd'로 하드코딩 생성했는데, 클라이언트(api/executor)에는
 * 환경변수로 다른 값(예: k8s 시크릿 랜덤 생성)을 주입하는 환경이 있어 인증 실패가 발생했다. 비밀번호는
 * 마이그레이션(1회성 버전 관리)이 아니라 Flyway migrate 실행마다(=매 앱 기동마다) 동기화해야 로테이션에도
 * 대응할 수 있어, 마이그레이션 SQL이 아닌 콜백으로 구현한다.
 *
 * <p>{@code AFTER_MIGRATE}는 대기 중인 마이그레이션이 없어도 migrate() 호출 시 항상 실행되므로,
 * 매 기동마다 현재 환경변수 값으로 ALTER ROLE이 재실행된다(멱등).
 */
public class PipelineExecutorPasswordSyncCallback extends BaseCallback {

  private final String password;

  public PipelineExecutorPasswordSyncCallback(String password) {
    this.password = password;
  }

  @Override
  public boolean supports(Event event, Context context) {
    return event == Event.AFTER_MIGRATE;
  }

  @Override
  public void handle(Event event, Context context) {
    // 비밀번호는 트러스트된 환경변수 값이지만, SQL 리터럴 삽입이라 홑따옴표는 이스케이프한다.
    String escaped = password.replace("'", "''");
    try (Statement stmt = context.getConnection().createStatement()) {
      stmt.execute("ALTER ROLE pipeline_executor PASSWORD '" + escaped + "'");
    } catch (SQLException e) {
      throw new IllegalStateException("pipeline_executor 비밀번호 동기화 실패", e);
    }
  }

  @Override
  public String getCallbackName() {
    return "pipelineExecutorPasswordSync";
  }
}
