package com.smartfirehub.proactive.service;

import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 이상탐지 메트릭 SQL 의 보안 등급 판정(스펙 §4.2 6행). 메트릭은 엔티티가 아니라 proactive_job.config.anomaly.metrics[] 이므로
 * config 를 직접 순회한다(판단 사항 9). 저장 시점(작성자 기준)의 선검사만 맡는다 — 폴링 시점(소유자 기준) 판정은 실행 관문
 * (GuardedSqlExecutor#executeMetricQuery)이 실행 문자열 그대로 한다(판정과 실행이 한 곳에 있어야 갈라지지 않는다).
 */
@Component
@RequiredArgsConstructor
public class MetricSqlAccessChecker {

  private final DatasetAccessGuard guard;
  private final ClearanceResolver clearanceResolver;

  /**
   * 생성·수정 시점 — 하나라도 거부면 403. 파싱 불가 SQL 은 여기서 막지 않는다(기존처럼 폴러가 건너뛴다 — 저장 계약 불변).
   *
   * <p>폴러가 실행할 문자열({@link NormalizedSql#of})을 판정한다 — 원문을 판정하면 판정 문자열과 실행 문자열이 갈라진다.
   */
  public void requireMetricQueriesAllowed(Map<String, Object> config, long userId) {
    if (config == null || !(config.get("anomaly") instanceof Map<?, ?> anomaly)) {
      return;
    }
    if (!(anomaly.get("metrics") instanceof List<?> metrics)) {
      return;
    }
    var clearance = clearanceResolver.resolve(userId);
    for (Object o : metrics) {
      if (o instanceof Map<?, ?> metric
          && "dataset".equals(metric.get("source"))
          && metric.get("query") instanceof String query
          && !query.isBlank()) {
        try {
          guard.requireSql(clearance, NormalizedSql.of(query).text(), SqlAccessMode.INTERACTIVE);
        } catch (SqlQueryException | UnsafeSqlException e) {
          // 파싱 불가 SQL — 폴러의 기존 검증(metricSqlValidator)이 실행 전에 건너뛴다.
        }
      }
    }
  }
}
