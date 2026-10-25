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
   * {@code /execute/sql} 시간 제한(스펙 §6, WD-236).
   *
   * <p>사용자 본문 제한(문장·전체)은 <b>본문에만</b> 건다. REPLACE 의 선행 문장(출력 잠금·비우기 DELETE)은 기존 출력 행 수에 비례해 오래 걸리므로
   * 본문 시간을 깎지 않게 별도 상한({@code preStatementTimeoutSeconds})을 받는다.
   *
   * @param statementTimeoutSeconds 본문 문장 하나의 {@code setQueryTimeout}(기본 50초)
   * @param totalTimeoutSeconds 본문 전체 제한(기본 55초) — 넘으면 실행 중 문장을 cancel 하고 ROLLBACK. 선행 문장이 없으면 요청
   *     도착부터, 있으면 본문 시작부터 잰다
   * @param preStatementTimeoutSeconds 선행 문장의 문장마다·합계 상한(기본 1800초, 적재 상한과 같은 값) — 요청 도착부터 잰다
   */
  public record Sql(
      @DefaultValue("50") int statementTimeoutSeconds,
      @DefaultValue("55") int totalTimeoutSeconds,
      @DefaultValue("1800") int preStatementTimeoutSeconds) {

    public Sql {
      if (statementTimeoutSeconds <= 0 || totalTimeoutSeconds <= 0) {
        throw new IllegalStateException("app.sql 시간 제한은 1초 이상이어야 합니다.");
      }
      if (preStatementTimeoutSeconds <= 0) {
        throw new IllegalStateException("app.sql.pre-statement-timeout-seconds 는 1초 이상이어야 합니다.");
      }
    }

    /**
     * 컨트롤러가 요청 도착 시점에 잡는 기한(초). 선행 문장이 없으면 본문 전체 기한 그대로(기존 동작 — 기술자 조회·대여·본문이 모두 그 안), 있으면 {@code
     * 선행 상한 + 본문 전체 기한} 이다. 실행기({@code SqlRunner})는 이 기한의 남은 시간이 본문 몫({@code bodySeconds}) 아래로 떨어지면
     * 선행 상한을 넘긴 것으로 보아 본문을 시작하지 않는다 — 그래서 본문은 언제나 이 기한 안에서 끝나고, api 의 HTTP 대기({@code 본문 대기 + 선행
     * 상한})를 넘겨 커밋하는 일이 없다. 컨트롤러와 테스트가 같은 계산을 쓰도록 여기 한 곳에 둔다.
     *
     * @param bodySeconds 본문 전체 기한(요청의 {@code timeout_seconds} 를 자른 값, 없으면 {@link
     *     #totalTimeoutSeconds})
     * @param hasPreStatements 선행 문장이 하나라도 있는지
     */
    public int requestSeconds(int bodySeconds, boolean hasPreStatements) {
      return hasPreStatements ? preStatementTimeoutSeconds + bodySeconds : bodySeconds;
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
