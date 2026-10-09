package com.smartfirehub.analytics.service;

import com.smartfirehub.analytics.dto.AnalyticsQueryResponse;
import com.smartfirehub.analytics.repository.AnalyticsQueryRunRepository;
import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ExportFormat;
import com.smartfirehub.dataimport.service.DataExportService;
import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import com.smartfirehub.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * 쿼리 결과 내보내기(스펙 §4.4): 실행 기록의 SQL 을 <b>내보내는 시점의 자격</b>으로 다시 판정하고 다시 실행해 파일로 만든다. 클라이언트가 보낸 행은 받지
 * 않는다 — 예전 POST /query-results/export 는 rows 를 그대로 직렬화해 서버가 출처를 몰라 정책을 판정할 수 없었다. 판정·실행은
 * GuardedSqlExecutor 를 그대로 쓴다(판정 = 실행).
 *
 * <p>트랜잭션: 이 서비스 자체에는 {@code @Transactional} 을 두지 않는다 — 판정 거부(403)가 트랜잭션 프록시 안에서 던져지면 바깥 트랜잭션이
 * rollback-only 로 오염된다(GuardedSqlExecutor 주석 참조). 성공 감사는 AuditLogRepository 의 클래스 레벨 트랜잭션이 GUC 를 심어
 * 이 테넌트로 남는다.
 */
@Service
@RequiredArgsConstructor
public class QueryResultExportService {

  /** 실행 기록 없음·남의 것·만료 — 모두 같은 404(Review Focus 3). */
  static final String RUN_NOT_FOUND_CODE = "QUERY_RUN_NOT_FOUND";

  private final AnalyticsQueryRunRepository runRepository;
  private final GuardedSqlExecutor guardedSqlExecutor;
  private final DatasetAccessGuard guard;
  private final DataExportService dataExportService;
  private final AuditLogService auditLogService;
  private final UserRepository userRepository;

  /** 내보낼 파일 — 본문·이름·형식. */
  public record ExportFile(StreamingResponseBody body, String filename, String contentType) {}

  /**
   * 실행 기록 {@code runId} 를 조회자 {@code c} 의 현재 자격으로 재판정·재실행해 파일로 만든다.
   *
   * <ul>
   *   <li>기록 없음·남의 기록·만료 → 404 QUERY_RUN_NOT_FOUND(구분 불가)
   *   <li>참조 데이터셋 중 볼 수 없는 것 → 열람 거부 403(실행 경로와 같은 응답·감사)
   *   <li>참조 데이터셋 중 내보낼 수 없는 것 → 403 POLICY_BLOCKED(여러 데이터셋 문구, 첫 차단 데이터셋을 감사)
   * </ul>
   */
  public ExportFile export(UUID runId, ExportFormat format, Clearance c) {
    AnalyticsQueryRunRepository.Run run =
        runRepository
            .findOwned(runId, c.userId())
            .orElseThrow(
                () ->
                    new CodedApiException(
                        HttpStatus.NOT_FOUND,
                        RUN_NOT_FOUND_CODE,
                        "실행 기록을 찾을 수 없습니다. 쿼리를 다시 실행한 뒤 내보내세요."));
    // 저장된 플래그는 믿지 않는다 — 실행 뒤 등급이 오르거나 자격이 줄었을 수 있으므로 지금 다시 판정한다(설계 결정 6).
    GuardedSqlExecutor.AnalyticsJudgment j = guardedSqlExecutor.judgeAnalytics(c, run.sqlText());
    if (!j.denied() && !j.exportAllowed()) {
      // 어느 데이터셋이 막는지 감사에 남기고 403(가드가 첫 차단 데이터셋을 감사한다).
      guard.requireExportAll(c, j.touchedDatasetIds());
      // 백스톱(fail-closed): SQL 판정은 내보내기 불가인데 데이터셋별 재판정이 통과한 경우에도 내보내지 않는다 — 두 판정의 불일치로 새는 길을
      // 구조적으로 막는다. 파싱 실패 SQL 은 기록되지 않으므로(성공한 SELECT 만 기록) 여기 오는 일은 정책 불가뿐이다.
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          DatasetAccessGuard.POLICY_BLOCKED_CODE,
          DatasetAccessGuard.EXPORT_MULTI_MESSAGE,
          Map.of("action", "EXPORT", "policyKey", "export_policy"));
    }
    // 거부면 executeJudgedAnalytics 가 감사 + 403. 내보내기는 읽기 전용으로만 재실행한다.
    AnalyticsQueryResponse r = guardedSqlExecutor.executeJudgedAnalytics(j, run.maxRows(), true);
    if (r.error() != null || !"SELECT".equals(r.queryType())) {
      throw new CodedApiException(
          HttpStatus.BAD_REQUEST, "QUERY_RUN_NOT_EXPORTABLE", "내보낼 수 있는 조회 결과가 아닙니다.");
    }
    StreamingResponseBody body = dataExportService.exportQueryResult(r.columns(), r.rows(), format);
    // 결과가 행 상한으로 잘렸으면 파일 이름에 남긴다 — 파일만 봐도 전체가 아님을 알 수 있게(#658 웹 동작과 같은 규칙).
    String filename =
        "query_result_"
            + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + (r.truncated() ? "_상위" + r.rows().size() + "행" : "")
            + "."
            + format.getExtension();
    int rowCount = r.rows().size();
    String username =
        userRepository.findById(c.userId()).map(u -> u.name()).orElse(String.valueOf(c.userId()));
    auditLogService.log(
        c.userId(),
        username,
        "DATA_EXPORT",
        "query_result",
        runId.toString(),
        format.name() + " 쿼리 결과 내보내기 (" + rowCount + "행)",
        null,
        null,
        "SUCCESS",
        null,
        Map.of("format", format.name(), "rowCount", rowCount));
    return new ExportFile(body, filename, format.getContentType());
  }
}
