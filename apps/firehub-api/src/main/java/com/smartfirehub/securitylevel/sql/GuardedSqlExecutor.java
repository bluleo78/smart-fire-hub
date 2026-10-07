package com.smartfirehub.securitylevel.sql;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.pipeline.service.executor.ExecutorClient;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 애드혹 SQL 실행의 단일 관문(스펙 §4.1). 실행 서비스(DataTableQueryService·AnalyticsQueryExecutionService)를 직접 부르는
 * 코드는 ArchUnit(SqlGateArchitectureTest)이 거부한다.
 *
 * <p><b>판정 = 실행(구조적 보장).</b> 사용자 원문을 여기서 {@link NormalizedSql#of} 로 단 한 번 정규화하고, 그 {@link
 * NormalizedSql#text()} String 인스턴스를 판정({@link DatasetAccessGuard#requireSql})에 넘긴 뒤 같은 {@link
 * NormalizedSql} 을 실행 서비스의 정규화 오버로드에 넘긴다 — 실행 서비스는 다시 정규화하지 않는다. 원문을 판정하고 정규화본을 실행하면 리터럴을 인지하지 못하는
 * 주석 제거 때문에 두 문자열이 다른 테이블을 참조할 수 있다(리터럴 안의 블록 주석 시작·줄 주석 표기가 지워지며 리터럴 안에 숨어 있던 숨김 테이블 서브쿼리가 코드로
 * 드러난다 — GuardedSqlExecutorTest 가 실측 문자열로 고정). 같은 문자열에 대한 {@code SqlValidator.validate} 는 실행 서비스가
 * 실행 직전에 한다(가드 계약).
 *
 * <p><b>트랜잭션.</b> 이 클래스와 가드에는 {@code @Transactional} 이 없다 — 판정 거부(403)가 {@code @Transactional} 프록시
 * 안에서 던져지면 호출자의 바깥 트랜잭션이 rollback-only 로 오염된다(판단 사항 19). 판정은 실행 서비스 프록시에 들어가기 <b>전</b>에 끝난다.
 */
@Service
@RequiredArgsConstructor
public class GuardedSqlExecutor {

  private final DatasetAccessGuard guard;
  private final DataTableQueryService dataTableQueryService;
  private final AnalyticsQueryExecutionService analyticsExecution;
  private final ExecutorClient executorClient;

  /**
   * 데이터셋 /query — 정규화 실패({@link SqlQueryException})·파싱 실패({@link UnsafeSqlException})는 기존과 같이
   * 예외(400), 열람 거부는 403.
   */
  public SqlQueryResponse executeDatasetQuery(Clearance c, String sql, int maxRows) {
    NormalizedSql normalized = NormalizedSql.of(sql);
    guard.requireSql(c, normalized.text(), SqlAccessMode.INTERACTIVE);
    return dataTableQueryService.executeQuery(normalized, maxRows);
  }

  /**
   * 애널리틱스 — 기존 계약상 문법·검증 오류는 200 + error 필드다(AnalyticsQueryExecutionService.execute). 정규화·판정 전 파싱
   * 오류도 같은 형태로 돌려 웹 쿼리 편집기 표시가 바뀌지 않게 한다. 열람 거부는 403(구조화 코드 — {@code CodedApiException} 이 그대로
   * 올라간다). 판정({@link #judgeAnalytics})과 실행({@link #executeJudgedAnalytics})을 한 번에 한다.
   */
  public AnalyticsQueryResponse executeAnalytics(
      Clearance c, String sql, int maxRows, boolean readOnly) {
    return executeJudgedAnalytics(judgeAnalytics(c, sql), maxRows, readOnly);
  }

  /**
   * 애널리틱스 SQL 판정 결과 — 판정한 정규화본({@link NormalizedSql})과 판정 결과를 함께 들고 다니는 토큰. 차트·대시보드는 거부를 예외가 아니라
   * 값으로 받아 위젯만 denied 로 바꾸고(스펙 §4.2 4행), 통과한 토큰만 {@link #executeJudgedAnalytics} 로 실행한다 — 판정을 두 번
   * 하지 않고, 판정한 바로 그 문자열이 실행된다(판정 = 실행).
   *
   * <p>생성자는 이 관문만 부른다 — 바깥에서 "허용" 토큰을 만들어 판정 없이 실행할 수 없게 한다.
   */
  public static final class AnalyticsJudgment {
    /** 판정·실행할 정규화본. 정규화·파싱 실패면 null. */
    private final NormalizedSql normalized;

    /** 판정 결과. 정규화·파싱 실패면 null. */
    private final SqlAccessResult access;

    /** 정규화·파싱 실패 메시지(실행 시 200 + error 로 돌려준다). 성공이면 null. */
    private final String parseError;

    private AnalyticsJudgment(NormalizedSql normalized, SqlAccessResult access, String parseError) {
      this.normalized = normalized;
      this.access = access;
      this.parseError = parseError;
    }

    /**
     * 조회자가 SQL 이 참조하는 데이터셋 중 하나라도 볼 수 없는가. 정규화·파싱 실패는 "거부 아님"(false) — 실행 시 기존 계약대로 200 + error 가
     * 된다.
     */
    public boolean denied() {
      return access != null && !access.allowed();
    }
  }

  /**
   * 애널리틱스 SQL 을 실행하지 않고 판정만 한다. 원문을 {@link NormalizedSql#of} 로 한 번 정규화하고 그 정규화본을 판정한다 — 원문을 판정하면
   * 정규화가 리터럴 안에 숨은 테이블 참조를 드러내는 SQL 에서 판정은 통과하고 실행 문자열은 숨김 테이블을 읽는다.
   */
  public AnalyticsJudgment judgeAnalytics(Clearance c, String sql) {
    try {
      NormalizedSql normalized = NormalizedSql.of(sql);
      SqlAccessResult access = guard.checkSql(c, normalized.text(), SqlAccessMode.INTERACTIVE);
      return new AnalyticsJudgment(normalized, access, null);
    } catch (SqlQueryException | UnsafeSqlException e) {
      return new AnalyticsJudgment(null, null, e.getMessage());
    }
  }

  /**
   * 판정 토큰을 실행한다 — 판정한 정규화본 그대로. 정규화·파싱 실패 토큰은 AnalyticsQueryExecutionService.errorResponse 와 같은
   * 모양(queryType "UNKNOWN", 200 + error)으로, 거부 토큰은 {@link DatasetAccessGuard#requireSql} 과 같은 403
   * 으로 끝난다.
   */
  public AnalyticsQueryResponse executeJudgedAnalytics(
      AnalyticsJudgment judgment, int maxRows, boolean readOnly) {
    if (judgment.parseError != null) {
      return new AnalyticsQueryResponse(
          "UNKNOWN", List.of(), List.of(), 0, 0L, 0, false, judgment.parseError);
    }
    SqlAccessResult r = judgment.access;
    if (!r.allowed()) {
      throw new CodedApiException(HttpStatus.FORBIDDEN, r.code(), r.message());
    }
    return analyticsExecution.execute(judgment.normalized, maxRows, readOnly);
  }

  /**
   * 이상탐지 메트릭 수집(폴러) — 소유자 자격({@code c})으로 판정한 뒤 executor 로 한 행만 읽는다(스펙 §4.2 6행). 호출자가 만든 {@link
   * NormalizedSql} 의 같은 String 을 판정하고 실행하므로 판정 = 실행이다. 열람 거부는 403({@code CodedApiException});
   * 호출자(폴러)는 이를 메트릭 수집 실패로 취급해 건너뛴다. 구분 불가 메시지라 로그·이력에 숨김 데이터셋 이름이 남지 않는다.
   */
  public ExecutorClient.QueryExecuteResult executeMetricQuery(
      Clearance c, NormalizedSql normalized) {
    // 끝 공백만 뗀 같은 문자열을 판정·실행한다(주석을 걷어낸 자리에 공백이 남을 수 있다 — 가드도 판정 시 strip 하므로 동일).
    String sql = normalized.text().strip();
    guard.requireSql(c, sql, SqlAccessMode.INTERACTIVE);
    return executorClient.executeQuery(sql, 1, true);
  }
}
