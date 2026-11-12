package com.smartfirehub.proactive.repository.mybatis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformJson;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.TiberoTextLimits;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionResponse;
import com.smartfirehub.proactive.dto.ProactiveJobExecutionSummaryResponse;
import com.smartfirehub.proactive.dto.ReportListItemResponse;
import com.smartfirehub.proactive.repository.ProactiveJobExecutionRepository;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionMapper.TenantIdsQuery;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.ExecutionInsert;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.ExecutionRow;
import com.smartfirehub.proactive.repository.mybatis.ProactiveJobExecutionRows.LevelCount;
import com.smartfirehub.proactive.util.ProactiveTime;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.dto.SecurityLevelSummary;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;

/**
 * ProactiveJobExecutionRepository 의 MyBatis 구현(WD-709) — {@code
 * JooqProactiveJobExecutionRepository} 와 같은 의미.
 *
 * <p>클래스 단위 {@code @Transactional} 은 jOOQ 구현 그대로이며 지우면 안 된다 — 주 호출자 {@code
 * ProactiveJobAsyncRunner.executeJob} 은 {@code @Async} 만 있고 트랜잭션이 없어, 트랜잭션이 없으면 RLS 테넌트 컨텍스트가 심기지
 * 않아 INSERT 가 NOT NULL 위반으로 깨지고 조회·갱신은 0행이 된다(전파 REQUIRED 라 컨트롤러 경로는 불변). {@code
 * tenantIdsWithActiveExecutions} 도 jOOQ 처럼 클래스 속성을 그대로 따른다(아웃박스와 달리 NOT_SUPPORTED 가 아니다).
 *
 * <p>DB 차이: 리포트 목록은 두 DB 모두 판정·페이지를 SQL 에서 끝내고 판정식만 databaseId 조각으로 다르다({@link
 * #findReportsByUserId}, XML 참조). 오류 메시지는 Tibero 바이트 한도 때문에 두 DB 모두 {@link TiberoTextLimits} 로 자른다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisProactiveJobExecutionRepository implements ProactiveJobExecutionRepository {

  private final ProactiveJobExecutionMapper mapper;
  private final ObjectMapper objectMapper;

  public MyBatisProactiveJobExecutionRepository(
      ProactiveJobExecutionMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  /** 생성과 등록 사이 정리기가 돌아도 새 실행을 고아로 보지 않도록 하트비트를 바로 남긴다(jOOQ 와 같은 자바 UTC 시각). */
  @Override
  public Long create(Long jobId) {
    ExecutionInsert insert = new ExecutionInsert(jobId, ProactiveTime.nowUtc());
    mapper.insert(insert);
    return insert.getId();
  }

  @Override
  public boolean updateStatus(
      Long id, String status, LocalDateTime startedAt, LocalDateTime completedAt) {
    return mapper.updateStatus(id, status, startedAt, completedAt) == 1;
  }

  @Override
  public boolean updateResult(
      Long id, String status, Map<String, Object> result, LocalDateTime completedAt) {
    // 결과가 null 이면 result 열을 건드리지 않는다(jOOQ 와 같다). 직렬화 실패만 감싸고 DB 예외는 그대로 둔다(WD-609).
    String json = PlatformJson.write(objectMapper, result, "result");
    return mapper.updateResult(id, status, json, completedAt) == 1;
  }

  @Override
  public boolean updateError(Long id, String errorMessage) {
    return mapper.updateError(
            id, TiberoTextLimits.truncateErrorMessage(errorMessage), ProactiveTime.nowUtc())
        == 1;
  }

  /** 등록된 활성 실행만 하트비트를 갱신한다. RLS 때문에 호출자는 테넌트 컨텍스트 안에 있어야 한다. */
  @Override
  public int touchHeartbeat(Collection<Long> executionIds, LocalDateTime now) {
    if (executionIds == null || executionIds.isEmpty()) return 0;
    return mapper.touchHeartbeat(InChunks.of(executionIds), now);
  }

  /**
   * 활성 실행이 있는 테넌트 id(WD-258) — 정책을 넘는 함수라 테넌트 컨텍스트 없이도 정수 목록만 받는다. 반환은 완전히 구체화된 리스트다(호출자가 이 메서드를 마친
   * 뒤 테넌트별로 새 트랜잭션을 연다).
   */
  @Override
  public List<Long> tenantIdsWithActiveExecutions() {
    TenantIdsQuery query = new TenantIdsQuery();
    List<Long> rows = mapper.tenantIdsWithActiveExecutions(query);
    // Tibero 는 커서 OUT 인자로 돌려주고 반환 목록은 비어 있다 — 채워진 쪽을 쓴다.
    return query.getTenantIds() != null ? query.getTenantIds() : rows;
  }

  /** 오래된 활성 실행 ID만 조회한다 — 인자로 받은 테넌트를 명시 술어로 건다(스펙 D1). */
  @Override
  public List<Long> findStaleExecutions(long tenantId, LocalDateTime threshold) {
    return mapper.findStaleExecutions(tenantId, threshold);
  }

  @Override
  public List<ProactiveJobExecutionResponse> findByJobId(Long jobId, int limit, int offset) {
    return mapper.findByJobId(jobId, limit, offset).stream().map(this::toResponse).toList();
  }

  /** 목록 endpoint 전용 경량 뷰 — result(리포트 본문)를 읽지 않는다(#604). */
  @Override
  public List<ProactiveJobExecutionSummaryResponse> findSummariesByJobId(
      Long jobId, int limit, int offset) {
    return mapper.findSummariesByJobId(jobId, limit, offset).stream()
        .map(this::toSummaryResponse)
        .toList();
  }

  /** 잡마다 가장 최근 실행 한 건(WD-134) — 본문은 읽지 않아 응답의 result 는 항상 null 이다. */
  @Override
  public Map<Long, ProactiveJobExecutionResponse> findLatestByJobIds(Collection<Long> jobIds) {
    Map<Long, ProactiveJobExecutionResponse> latest = new HashMap<>();
    if (jobIds.isEmpty()) return latest;
    for (ExecutionRow row : mapper.findLatestByJobIds(InChunks.of(jobIds))) {
      latest.put(row.jobId(), toResponse(row));
    }
    return latest;
  }

  @Override
  public Optional<ProactiveJobExecutionResponse> findById(Long id) {
    return Optional.ofNullable(mapper.findById(id)).map(this::toResponse);
  }

  /** 쉼표 구분 문자열로 저장한다(예: "CHAT,EMAIL"). 빈 목록·null 은 NULL. */
  @Override
  public void updateDeliveredChannels(Long executionId, List<String> channels) {
    String value = (channels != null && !channels.isEmpty()) ? String.join(",", channels) : null;
    mapper.updateDeliveredChannels(executionId, value);
  }

  /**
   * 사용자가 소유한 작업들이 만든 리포트(COMPLETED + htmlContent 가 있고 비어 있지 않음)를 완료 시각·id 내림차순으로 잡 횡단 조회한다. 판정·제목
   * 대체·페이지(limit·offset)를 SQL 이 한 번에 하므로 리포트가 아닌 실행이 사이에 있어도 페이지는 리포트만 센다(jOOQ 와 같다).
   */
  @Override
  public List<ReportListItemResponse> findReportsByUserId(Long userId, int limit, int offset) {
    return mapper.findReports(userId, limit, offset).stream()
        .map(
            row ->
                new ReportListItemResponse(
                    row.executionId(),
                    row.jobId(),
                    row.jobName(),
                    row.title(),
                    row.summary(),
                    row.completedAt(),
                    levelOf(row.level())))
        .toList();
  }

  /**
   * 리포트 등급을 올린다(WD-198) — 지금 등급이 없거나 rank 가 더 낮을 때만, 작성자 조건은 늘, runningSince 가 있으면 진행 중이던 실행만(한 문장
   * UPDATE). 다른 테넌트의 등급 id 는 RLS 로 보이지 않아 갱신되지 않는다.
   *
   * @return 갱신된 행 수(0 또는 1)
   */
  @Override
  public int raiseSecurityLevel(
      long executionId, long ownerUserId, long levelId, LocalDateTime runningSince) {
    return mapper.raiseSecurityLevel(executionId, ownerUserId, levelId, runningSince);
  }

  /** 데이터셋들의 등급 id(중복 제거, WD-198). RLS 로 현재 테넌트만 보인다. */
  @Override
  public Set<Long> findDatasetLevelIds(Collection<Long> datasetIds) {
    if (datasetIds == null || datasetIds.isEmpty()) return new HashSet<>();
    return new HashSet<>(mapper.findDatasetLevelIds(InChunks.of(datasetIds)));
  }

  /** 등급별 리포트(실행) 수 — 등급 삭제의 「사용 중」 판정(WD-198). */
  @Override
  public Map<Long, Long> countByLevel() {
    Map<Long, Long> counts = new HashMap<>();
    for (LevelCount c : mapper.countByLevel()) {
      counts.put(c.levelId(), c.cnt());
    }
    return counts;
  }

  /** 등급 삭제 시 그 등급의 리포트를 옮길 등급으로 옮긴다(WD-198). */
  @Override
  public int moveLevel(long fromLevelId, long toLevelId) {
    return mapper.moveLevel(fromLevelId, toLevelId);
  }

  /** 실행 행 → 전체 응답. 요약 문장의 행은 result 열이 NULL 이라 result 가 null 이다. */
  private ProactiveJobExecutionResponse toResponse(ExecutionRow r) {
    return new ProactiveJobExecutionResponse(
        r.id(),
        r.jobId(),
        r.status(),
        r.startedAt(),
        r.completedAt(),
        r.errorMessage(),
        PlatformJson.toMap(objectMapper, r.resultJson(), "result"),
        parseChannels(r.deliveredChannels()),
        r.createdAt(),
        levelOf(r.level()));
  }

  /** 실행 행 → 목록용 요약 응답. 공통 필드 변환(전달 채널·등급)은 {@link #toResponse} 한 곳에서 하고 본문만 뺀다. */
  private ProactiveJobExecutionSummaryResponse toSummaryResponse(ExecutionRow r) {
    ProactiveJobExecutionResponse full = toResponse(r);
    return new ProactiveJobExecutionSummaryResponse(
        full.id(),
        full.jobId(),
        full.status(),
        full.startedAt(),
        full.completedAt(),
        full.errorMessage(),
        full.deliveredChannels(),
        full.createdAt(),
        full.securityLevel());
  }

  /** 조인된 등급(SecurityLevelMapper.levelPolicy 결과 맵) → 응답 요약. 등급이 없으면 null. */
  private static SecurityLevelSummary levelOf(LevelPolicy level) {
    return level == null ? null : SecurityLevelSummary.of(level);
  }

  /** deliveredChannels: 쉼표로 구분해 저장한 문자열을 리스트로 변환한다(비었으면 null). */
  private static List<String> parseChannels(String channelsStr) {
    return (channelsStr != null && !channelsStr.isBlank()) ? List.of(channelsStr.split(",")) : null;
  }
}
