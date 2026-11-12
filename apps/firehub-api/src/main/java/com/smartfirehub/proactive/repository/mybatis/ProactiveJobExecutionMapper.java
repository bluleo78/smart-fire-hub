package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.ExecutionInsert;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.ExecutionRow;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.LevelCount;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.ReportRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * proactive_job_execution MyBatis 매퍼(WD-709). SQL 은 {@code
 * mybatis/proactive/ProactiveJobExecutionMapper.xml}. 테넌트는 정책(RLS)이 맡아 tenant_id 를 술어·INSERT 값으로 쓰지
 * 않는다 — 단 인터페이스가 테넌트를 인자로 받는 {@code findStaleExecutions} 는 명시 술어를 그대로 둔다(스펙 D1).
 *
 * <p>상태 갱신 문장은 모두 활성(PENDING·RUNNING) 조건을 건 조건부 갱신(CAS)이고, 리포지토리가 갱신 건수 1 로 성공을 판정한다.
 */
@PlatformMapper
public interface ProactiveJobExecutionMapper {

  int insert(ExecutionInsert row);

  /** 활성 실행의 상태와(넘긴 경우) 시작·완료 시각을 바꾼다. */
  int updateStatus(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("startedAt") LocalDateTime startedAt,
      @Param("completedAt") LocalDateTime completedAt);

  /** 활성 실행의 상태·완료 시각과(넘긴 경우) 결과 JSON 을 바꾼다. */
  int updateResult(
      @Param("id") Long id,
      @Param("status") String status,
      @Param("resultJson") String resultJson,
      @Param("completedAt") LocalDateTime completedAt);

  /** 활성 실행을 FAILED 로 닫는다(메시지는 리포지토리가 길이 한도로 자른 값). */
  int updateError(
      @Param("id") Long id,
      @Param("errorMessage") String errorMessage,
      @Param("completedAt") LocalDateTime completedAt);

  /** 활성 실행의 하트비트를 갱신한다 — chunks 는 InChunks.of 로 나눈 id 목록. */
  int touchHeartbeat(@Param("chunks") List<List<Long>> chunks, @Param("now") LocalDateTime now);

  /**
   * 활성 실행이 있는 테넌트 id — 정책을 넘는 함수(PG {@code proactive_active_execution_tenant_ids()}, Tibero {@code
   * FH_TENANT.proactive_active_execution_tenant_ids} 커서)를 부른다. PG 는 반환 목록으로, Tibero 는 커서 OUT 인자로
   * {@link TenantIdsQuery#getTenantIds()} 에 돌려준다(이때 반환 목록은 비어 있다).
   */
  List<Long> tenantIdsWithActiveExecutions(@Param("query") TenantIdsQuery query);

  /** 지정 테넌트(명시 tenant_id 술어)의 오래된 활성 실행 id. */
  List<Long> findStaleExecutions(
      @Param("tenantId") long tenantId, @Param("threshold") LocalDateTime threshold);

  /** 작업의 실행(본문 포함) id 내림차순 페이지. */
  List<ExecutionRow> findByJobId(
      @Param("jobId") Long jobId, @Param("limit") int limit, @Param("offset") int offset);

  /** 작업의 실행(본문 제외) id 내림차순 페이지. */
  List<ExecutionRow> findSummariesByJobId(
      @Param("jobId") Long jobId, @Param("limit") int limit, @Param("offset") int offset);

  /** 작업마다 최근 실행 한 건(본문 제외) — chunks 는 InChunks.of 로 나눈 작업 id 목록. */
  List<ExecutionRow> findLatestByJobIds(@Param("chunks") List<List<Long>> chunks);

  ExecutionRow findById(@Param("id") Long id);

  void updateDeliveredChannels(@Param("id") Long id, @Param("channels") String channels);

  /**
   * 사용자 소유 작업의 리포트(완료 시각·id 내림차순) 페이지 — 「리포트」 판정·제목 대체·페이지를 두 DB 모두 SQL 에서 끝낸다(판정식만 databaseId 조각으로
   * 다르다, XML 참조).
   */
  List<ReportRow> findReports(
      @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") int offset);

  int raiseSecurityLevel(
      @Param("executionId") long executionId,
      @Param("ownerUserId") long ownerUserId,
      @Param("levelId") long levelId,
      @Param("runningSince") LocalDateTime runningSince);

  /** 데이터셋 등급 id(중복 제거) — chunks 는 InChunks.of 로 나눈 데이터셋 id 목록. */
  List<Long> findDatasetLevelIds(@Param("chunks") List<List<Long>> chunks);

  List<LevelCount> countByLevel();

  int moveLevel(@Param("fromLevelId") long fromLevelId, @Param("toLevelId") long toLevelId);

  /**
   * {@link #tenantIdsWithActiveExecutions} 의 출력 자리 — 벤더마다 결과가 오는 자리가 달라(PG 결과 집합, Tibero 커서 OUT 인자)
   * 한 매퍼 시그니처로 두려고 가변 객체로 둔다. MyBatis 가 Tibero 커서 결과를 {@code tenantIds} 세터로 넣는다.
   */
  final class TenantIdsQuery {
    private List<Long> tenantIds;

    /** Tibero 커서 OUT 결과(PG 는 null). */
    public List<Long> getTenantIds() {
      return tenantIds;
    }

    public void setTenantIds(List<Long> tenantIds) {
      this.tenantIds = tenantIds;
    }
  }
}
