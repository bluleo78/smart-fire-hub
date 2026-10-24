package com.smartfirehub.dbexecutor.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param load /execute/load 제한이자 요청별 {@code timeout_seconds} 의 상한
 */
@ConfigurationProperties(prefix = "app")
public record ExecutionTimeouts(
    @DefaultValue Sql sql,
    @DefaultValue Probe probe,
    @DefaultValue Query query,
    @DefaultValue Load load) {

  /** 설정 바인딩이 쓰는 주 생성자(생성자가 둘이라 바인딩 대상을 명시한다). */
  @ConstructorBinding
  public ExecutionTimeouts {}

  /** 적재 제한을 기본값(1800초)으로 두는 편의 생성자(코드에서 직접 만들 때). 설정 바인딩은 주 생성자를 쓴다. */
  public ExecutionTimeouts(Sql sql, Probe probe, Query query) {
    this(sql, probe, query, new Load(1800));
  }

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

  /**
   * @param timeoutSeconds 적재(스테이징 INSERT)·긴 SQL 의 전체 기한이자 요청별 {@code timeout_seconds} 상한(기본 1800초).
   *     사용자 SQL 용 50/55/60초를 쓰지 않는다 — 적재는 행 수에 비례해 오래 걸리고, 파이프라인 단계 시간 제한에 맞춘다(스펙 §3.3).
   */
  public record Load(@DefaultValue("1800") int timeoutSeconds) {

    public Load {
      if (timeoutSeconds <= 0) {
        throw new IllegalStateException("app.load.timeout-seconds 는 1초 이상이어야 합니다.");
      }
    }
  }
}
