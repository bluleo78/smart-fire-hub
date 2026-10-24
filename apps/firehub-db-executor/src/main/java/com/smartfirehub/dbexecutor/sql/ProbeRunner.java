package com.smartfirehub.dbexecutor.sql;

import com.smartfirehub.dbexecutor.config.ExecutionTimeouts;
import com.smartfirehub.dbexecutor.store.StoreDescriptor;
import com.smartfirehub.dbexecutor.store.StoreDescriptorClient;
import com.smartfirehub.dbexecutor.store.TenantPools;
import com.smartfirehub.dbexecutor.web.dto.ProbeColumn;
import com.smartfirehub.dbexecutor.web.dto.ProbeResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * {@code /execute/probe} — 사용자 SELECT 의 결과 컬럼을 행을 읽지 않고 알아낸다(스펙 §4.2).
 *
 * <p>{@code SELECT * FROM (\n<query>\n) WHERE 1 = 0} 을 실행해 {@link ResultSetMetaData} 만 읽는다. 개행으로
 * 감싸는 이유는 끝이 {@code --} 주석이어도 닫는 괄호가 주석에 먹히지 않게 하기 위해서다(api 래퍼와 같은 이유). 사용자 SQL 이 서버에서 평가되므로 {@code
 * /execute/sql} 과 같은 재검증을 거치고, SELECT·WITH 만 받는다.
 *
 * <p>컬럼 이름은 <b>바꾸지 않고</b> 드라이버가 준 그대로 돌려준다(스펙 §4.2). 따옴표 없는 별칭은 Tibero 가 대문자로 접어 {@code AMOUNT},
 * 따옴표 소문자 별칭은 {@code amount} 로 온다 — 여기서 소문자로 바꾸면 둘을 api 가 가를 수 없어 적재 문장이 없는 이름({@code
 * src."AMOUNT"})을 참조한다. 정규화는 api 가 한다. ({@code /execute/sql} 의 rows·columns 소문자 규칙은 그대로다.)
 *
 * <p>시간 제한·대여 실패 처리는 {@link SqlRunner} 와 같은 {@link LeasedExecution} 을 쓴다 — 기한은 요청 도착부터(기본 30초),
 * 대여(로그인) 실패면 기술자 캐시를 지운다.
 */
@Component
public class ProbeRunner {

  static final String SELECT_ONLY = "컬럼 탐지는 SELECT·WITH 문만 할 수 있습니다.";

  private final int timeoutSeconds;
  private final LeasedExecution execution;

  public ProbeRunner(
      @Qualifier("tenantPools") TenantPools pools,
      ExecutionTimeouts timeouts,
      StoreDescriptorClient descriptors) {
    this.timeoutSeconds = timeouts.probe().timeoutSeconds();
    this.execution = new LeasedExecution(pools, descriptors);
  }

  /** 컬럼 탐지 시간 초과 문구. */
  static String timeoutMessage(int seconds) {
    return "컬럼 탐지 시간이 " + seconds + "초를 넘어 취소했습니다.";
  }

  /**
   * 검증 → 래핑 실행 → 메타데이터. 오류는 {@code success=false} 로 돌려준다.
   *
   * @param deadline 요청 전체 기한(컨트롤러 도착 시점부터). 닫기는 호출자 몫이다.
   */
  public ProbeResponse probe(StoreDescriptor d, String query, Deadline deadline) {
    String body;
    try {
      body = SqlRunner.validatedBody(query, d.schema());
      String first = TiberoSqlLexer.firstKeyword(body);
      if (!first.equals("SELECT") && !first.equals("WITH")) {
        throw new TiberoSqlGuard.Rejected(SELECT_ONLY);
      }
    } catch (TiberoSqlGuard.Rejected e) {
      return ProbeResponse.failure(e.getMessage(), null);
    }
    String wrapped = "SELECT * FROM (\n" + body + "\n) WHERE 1 = 0";

    return execution.withLease(
        d,
        deadline,
        LeasedExecution.failures(timeoutMessage(timeoutSeconds), null, ProbeResponse::failure),
        c -> {
          List<ProbeColumn> columns = columns(c, deadline, wrapped);
          // 읽기만 했지만 트랜잭션을 깨끗이 닫아 커넥션을 반납한다(실패 경로의 ROLLBACK 은 LeasedExecution 이 한다).
          LeasedExecution.rollbackQuietly(c);
          return new ProbeResponse(true, columns, null, null);
        });
  }

  private List<ProbeColumn> columns(Connection c, Deadline deadline, String wrapped)
      throws SQLException, Deadline.TimedOut {
    return deadline.execute(
        c,
        timeoutSeconds,
        st -> {
          try (ResultSet rs = st.executeQuery(wrapped)) {
            ResultSetMetaData md = rs.getMetaData();
            List<ProbeColumn> out = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
              out.add(
                  new ProbeColumn(
                      // 이름은 바꾸지 않는다 — 대문자 접힘과 따옴표 소문자를 api 가 가를 수 있게(클래스 설명 참조).
                      md.getColumnLabel(i),
                      md.getColumnType(i),
                      md.getColumnTypeName(i),
                      md.getPrecision(i),
                      md.getScale(i)));
            }
            return out;
          }
        });
  }
}
