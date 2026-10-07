package com.smartfirehub.securitylevel.sql;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.service.AnalyticsQueryExecutionService;
import com.smartfirehub.dataset.dto.SqlQueryResponse;
import com.smartfirehub.dataset.exception.SqlQueryException;
import com.smartfirehub.dataset.service.DataTableQueryService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.util.NormalizedSql;
import com.smartfirehub.pipeline.exception.UnsafeSqlException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
   * 올라간다).
   */
  public AnalyticsQueryResponse executeAnalytics(
      Clearance c, String sql, int maxRows, boolean readOnly) {
    NormalizedSql normalized;
    try {
      normalized = NormalizedSql.of(sql);
      guard.requireSql(c, normalized.text(), SqlAccessMode.INTERACTIVE);
    } catch (SqlQueryException | UnsafeSqlException e) {
      // AnalyticsQueryExecutionService.errorResponse 와 같은 모양(queryType "UNKNOWN") — 웹 표시가 같게.
      return new AnalyticsQueryResponse(
          "UNKNOWN", List.of(), List.of(), 0, 0L, 0, false, e.getMessage());
    }
    return analyticsExecution.execute(normalized, maxRows, readOnly);
  }

  /**
   * 차트·대시보드용 사전 판정 — 거부를 예외가 아니라 값으로 돌려준다(위젯 하나 때문에 페이지 전체가 403 이 되지 않게, 스펙 §4.2 4행).
   *
   * <p>{@link #executeAnalytics} 와 <b>같은 문자열</b>({@link NormalizedSql#of} 정규화본)을 판정한다. 원문을 판정하면
   * 정규화가 리터럴 안에 숨은 테이블 참조를 드러내는 SQL 에서 사전 판정은 통과하고 실행 판정은 403 을 던져, 결국 위젯별 denied 가 아니라 요청 전체 실패가
   * 된다. 정규화·파싱 실패는 "거부 아님"(false)으로 돌려준다 — 실행 시 {@link #executeAnalytics} 가 기존 계약대로 200 + error 로
   * 바꾼다.
   *
   * @return 조회자가 SQL 이 참조하는 데이터셋 중 하나라도 볼 수 없으면 true
   */
  public boolean isAnalyticsDenied(Clearance c, String sql) {
    try {
      return !guard.checkSql(c, NormalizedSql.of(sql).text(), SqlAccessMode.INTERACTIVE).allowed();
    } catch (SqlQueryException | UnsafeSqlException e) {
      return false;
    }
  }

  /**
   * 실행 관문이 던진 예외가 SQL 열람 거부(403)인가. 사전 판정과 실행 판정 사이에 등급·자격이 바뀐 경합에서 실행 관문이 거부하면, 호출자(차트·대시보드)는 이를
   * 403 이 아니라 위젯 denied 로 바꿔야 한다. 그 밖의 코드는 그대로 다시 던지게 false.
   */
  public static boolean isSqlAccessDenial(CodedApiException e) {
    return DatasetAccessGuard.SQL_ACCESS_DENIED_CODE.equals(e.code())
        || DatasetAccessGuard.SQL_WRITE_DOWNGRADE_CODE.equals(e.code());
  }
}
