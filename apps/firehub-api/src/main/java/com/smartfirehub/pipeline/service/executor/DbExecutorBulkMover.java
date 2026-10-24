package com.smartfirehub.pipeline.service.executor;

import com.smartfirehub.dataset.storage.tibero.TiberoBulkMover;
import com.smartfirehub.dataset.storage.tibero.TiberoPipelineSql;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.exception.ScriptExecutionException;
import com.smartfirehub.pipeline.service.PipelineSqlErrorMessages;
import com.smartfirehub.pipeline.service.executor.ExecutorClient.SqlExecuteResult;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link TiberoBulkMover} 의 db-executor 구현(WD-77). 옮기기 SQL 은 api 가 만들고({@link TiberoPipelineSql})
 * 실행은 {@link DbExecutorClient#executeSql} 이 한다 — api 가 Tibero DML 을 직접 실행하지 않는 규칙을 지킨다.
 *
 * <p><b>왜 한 요청인가.</b> REPLACE 는 선행문(비우기)과 본문(옮기기)을 같은 요청에 실어 실행기가 한 트랜잭션으로 묶게 한다. 따로 보내면 옮기기가 실패했을
 * 때 출력 테이블이 빈 채로 남는다. 선행문이 있으면 실행기가 먼저 그 테이블에 배타 잠금을 건다.
 *
 * <p><b>동시성(REPLACE·APPEND 가 같은 테이블에 겹칠 때).</b> REPLACE 는 선행문 잠금({@code LOCK TABLE … IN EXCLUSIVE
 * MODE})으로 다른 세션의 DML 을 모두 막는다. APPEND 의 {@code INSERT} 는 행 배타(RX) 잠금이 필요한데 EXCLUSIVE 와 충돌하므로,
 * REPLACE 가 커밋할 때까지 기다렸다가 새 내용 뒤에 붙는다. 반대로 APPEND 가 진행 중이면 REPLACE 의 LOCK 이 APPEND 커밋을 기다린다. 어느 쪽이든
 * 직렬화되어 중복·유실이 없다 — APPEND 에 별도 잠금 문장을 더하지 않는 이유다(실서버 확인: db-executor {@code TiberoLiveMoveTest}).
 */
@Component
public class DbExecutorBulkMover implements TiberoBulkMover {

  /** 오류 문구 접두 — 실행 단계 이름. */
  private static final String PREFIX = "출력 테이블 적재";

  private final DbExecutorClient client;
  private final int timeoutSeconds;

  public DbExecutorBulkMover(
      DbExecutorClient client,
      @Value("${app.db-executor.move-timeout-seconds:1800}") int timeoutSeconds) {
    this.client = client;
    this.timeoutSeconds = timeoutSeconds;
  }

  /** REPLACE 면 비우기 선행문({@code DELETE})을 옮기기 본문과 같은 요청에 싣고, APPEND 면 본문만 보낸다. */
  @Override
  public void move(
      long tenantId,
      String schema,
      String table,
      String staging,
      List<String> columns,
      boolean replace) {
    requireCurrentTenant(tenantId);
    run(
        TiberoPipelineSql.moveFromStaging(schema, table, staging, columns),
        replace ? List.of(TiberoPipelineSql.deleteAll(schema, table)) : List.of());
  }

  /** 실행기는 요청 컨텍스트의 테넌트로 접속한다 — 인자의 테넌트와 다르면 남의 스키마에 쓰게 되므로 fail-closed 로 막는다. */
  private static void requireCurrentTenant(long tenantId) {
    long current = TenantContext.require("Tibero 스테이징 옮기기");
    if (current != tenantId) {
      throw new IllegalStateException("요청 테넌트(" + tenantId + ")가 현재 테넌트(" + current + ")와 다릅니다");
    }
  }

  private void run(String body, List<String> preStatements) {
    SqlExecuteResult result = client.executeSql(body, preStatements, timeoutSeconds);
    if (!result.success()) {
      throw new ScriptExecutionException(
          PipelineSqlErrorMessages.describeTiberoError(PREFIX, result.dbError(), result.error()));
    }
  }
}
