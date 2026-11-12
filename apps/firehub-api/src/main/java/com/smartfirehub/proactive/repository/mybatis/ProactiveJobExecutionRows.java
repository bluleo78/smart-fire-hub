package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import java.time.LocalDateTime;

/**
 * ProactiveJobExecutionMapper 의 행 값 객체 모음(WD-709). Tibero 는 NUMBER 를 BigDecimal 로 돌려주므로 결과 맵 대신
 * Long 을 명시한 생성자 매핑으로 받고, 리포지토리가 인터페이스 반환형(응답 DTO·Map)으로 옮긴다.
 *
 * <p>등급은 LEFT JOIN 한 {@code sl_*} 열을 {@code SecurityLevelMapper.levelPolicy} 결과 맵(접두사 sl_)으로 바로
 * {@link LevelPolicy} 로 받는다 — 등급이 없어 열이 모두 NULL 이면 MyBatis 가 null 을 넘긴다(정책 문자열은 같은 결과 맵의 Enum 엄격
 * 변환).
 */
public final class ProactiveJobExecutionRows {

  private ProactiveJobExecutionRows() {}

  /**
   * 실행 한 행 + 등급. {@code resultJson} 은 본문까지 읽는 문장(findByJobId·findById)에서만 채워지고 요약 문장에서는 null 이다.
   */
  public record ExecutionRow(
      Long id,
      Long jobId,
      String status,
      LocalDateTime startedAt,
      LocalDateTime completedAt,
      String errorMessage,
      String resultJson,
      String deliveredChannels,
      LocalDateTime createdAt,
      LevelPolicy level) {}

  /** 리포트 목록 한 행 — 두 DB 모두 SQL 이 판정·추출한 {@code title}(작업 이름 대체 포함)·{@code summary} 를 채운다. */
  public record ReportRow(
      Long executionId,
      Long jobId,
      String jobName,
      String title,
      String summary,
      LocalDateTime completedAt,
      LevelPolicy level) {}

  /** 등급 id 별 실행 수. */
  public record LevelCount(Long levelId, Long cnt) {}

  /** create 의 INSERT 파라미터 겸 생성 키 홀더. */
  public static class ExecutionInsert extends GeneratedId {
    private final Long jobId;
    private final LocalDateTime heartbeatAt;

    public ExecutionInsert(Long jobId, LocalDateTime heartbeatAt) {
      this.jobId = jobId;
      this.heartbeatAt = heartbeatAt;
    }

    public Long getJobId() {
      return jobId;
    }

    public LocalDateTime getHeartbeatAt() {
      return heartbeatAt;
    }
  }
}
