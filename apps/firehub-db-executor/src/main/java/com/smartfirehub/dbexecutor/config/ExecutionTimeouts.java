package com.smartfirehub.dbexecutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 실행 시간 제한({@code app.sql.*}, {@code app.probe.*}, {@code app.query.*}, 스펙 §4.3·§11).
 *
 * <p>api 의 호출 시간 제한(/execute/sql 60초, /execute/probe 35초)보다 반드시 짧아야 한다 — 그래야 api 가 포기하기 전에 실행기가 먼저
 * 취소·롤백해 결과(실패)를 돌려준다. 실서버 시간 초과 테스트가 값을 줄일 수 있게 설정으로 둔다.
 *
 * @param sql /execute/sql 제한
 * @param probe /execute/probe 제한
 * @param query /execute/query 제한
 */
@ConfigurationProperties(prefix = "app")
public record ExecutionTimeouts(
    @DefaultValue Sql sql, @DefaultValue Probe probe, @DefaultValue Query query) {

  /**
   * @param statementTimeoutSeconds 문장 하나의 {@code setQueryTimeout}(기본 50초)
   * @param totalTimeoutSeconds 요청 전체 제한(기본 55초) — 넘으면 실행 중 문장을 cancel 하고 ROLLBACK
   */
  public record Sql(
      @DefaultValue("50") int statementTimeoutSeconds,
      @DefaultValue("55") int totalTimeoutSeconds) {

    public Sql {
      if (statementTimeoutSeconds <= 0 || totalTimeoutSeconds <= 0) {
        throw new IllegalStateException("app.sql 시간 제한은 1초 이상이어야 합니다.");
      }
    }
  }

  /**
   * @param timeoutSeconds 컬럼 탐지 문장 제한(기본 30초)
   */
  public record Probe(@DefaultValue("30") int timeoutSeconds) {

    public Probe {
      if (timeoutSeconds <= 0) {
        throw new IllegalStateException("app.probe.timeout-seconds 는 1초 이상이어야 합니다.");
      }
    }
  }

  /**
   * @param timeoutSeconds 분석 조회 문장·요청 제한(기본 30초, api 대기 35초보다 짧게)
   */
  public record Query(@DefaultValue("30") int timeoutSeconds) {

    public Query {
      if (timeoutSeconds <= 0) {
        throw new IllegalStateException("app.query.timeout-seconds 는 1초 이상이어야 합니다.");
      }
    }
  }
}
