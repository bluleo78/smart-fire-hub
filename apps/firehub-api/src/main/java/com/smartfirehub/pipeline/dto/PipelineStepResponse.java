package com.smartfirehub.pipeline.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 파이프라인 스텝 응답. {@code lastRunAt}/{@code fullRebuildPending}/{@code warnings}/{@code
 * fullRebuildMode} 는 증분 처리(Task 6~7) 노출용이다.
 *
 * <ul>
 *   <li>{@code lastRunAt} — 마지막 성공 실행의 책갈피(없으면 전체 읽기)
 *   <li>{@code fullRebuildPending} — 다음 실행이 전체를 읽을지(예약 여부)
 *   <li>{@code warnings} — 저장을 막지 않는 어드바이저리 경고(예: 증분 SQL 의 부분 집계 위험, APPEND+증분
 *       중복)
 *   <li>{@code fullRebuildMode} — "전체 재생성 예약"이 실제로 무엇을 하는지 구분하는 값. {@code null}(증분
 *       스텝이 아님 — 예약 자체가 불가), {@code "REBUILD_OUTPUT"}(SELECT 자동 적재 스텝 — 실행기가 출력을
 *       비우고 전체를 다시 채움), {@code "READ_ALL"}(사용자 INSERT/UPDATE/DELETE 스텝 — {@code
 *       {{last_run_at}}} 이 {@code -infinity} 로 바뀌어 전체 행을 다시 읽을 뿐, 출력 재생성은 그 SQL 자체의
 *       로직에 달려 있음). 웹 UI(Task 8)가 이 구분을 문자열 경고가 아니라 이 필드로 판별해 "전체 재생성"
 *       /"전체 재읽기" 라벨을 정확히 갈라 쓰도록 별도 필드로 노출한다.
 * </ul>
 */
public record PipelineStepResponse(
    Long id,
    String name,
    String description,
    String scriptType,
    String scriptContent,
    Long outputDatasetId,
    String outputDatasetName,
    List<Long> inputDatasetIds,
    List<String> dependsOnStepNames,
    int stepOrder,
    String loadStrategy,
    Map<String, Object> apiConfig,
    Map<String, Object> aiConfig,
    Map<String, Object> pythonConfig,
    Long apiConnectionId,
    OffsetDateTime lastRunAt,
    boolean fullRebuildPending,
    List<String> warnings,
    String fullRebuildMode) {

  /** {@link #fullRebuildMode}: SELECT 자동 적재 스텝 — 예약 시 출력을 비우고 전체를 다시 채운다. */
  public static final String FULL_REBUILD_MODE_REBUILD_OUTPUT = "REBUILD_OUTPUT";

  /** {@link #fullRebuildMode}: 사용자 DML 스텝 — 예약해도 출력은 그대로고 전체 행만 다시 읽는다. */
  public static final String FULL_REBUILD_MODE_READ_ALL = "READ_ALL";

  /**
   * 증분 필드 도입(Task 6~7) 이전의 15-필드 생성자 호환 오버로드 — 증분 상태를 신경 쓰지 않는 기존 호출부
   * (실행기 내부 스텝 조립, 다수의 기존 테스트)가 매번 네 인자를 더 채우지 않아도 되게 한다. 책갈피
   * 없음(null)·예약 안 됨(false)·경고 없음(빈 목록)·모드 없음(null)이 기본값이다.
   */
  public PipelineStepResponse(
      Long id,
      String name,
      String description,
      String scriptType,
      String scriptContent,
      Long outputDatasetId,
      String outputDatasetName,
      List<Long> inputDatasetIds,
      List<String> dependsOnStepNames,
      int stepOrder,
      String loadStrategy,
      Map<String, Object> apiConfig,
      Map<String, Object> aiConfig,
      Map<String, Object> pythonConfig,
      Long apiConnectionId) {
    this(
        id,
        name,
        description,
        scriptType,
        scriptContent,
        outputDatasetId,
        outputDatasetName,
        inputDatasetIds,
        dependsOnStepNames,
        stepOrder,
        loadStrategy,
        apiConfig,
        aiConfig,
        pythonConfig,
        apiConnectionId,
        null,
        false,
        List.of(),
        null);
  }

  /**
   * 경고 목록과 재생성 모드를 다시 계산해 붙인 사본을 만든다(다른 필드는 불변) — 상세 조회 조립 시
   * 사용한다. 두 값을 한 번의 호출로 같이 붙이는 이유는, 둘 다 같은 SQL 분석(플레이스홀더·SELECT 여부)에서
   * 파생되는 값이라 따로 부르면 호출부가 한쪽만 갱신하고 다른 쪽을 놓치는 결함이 생기기 쉽기 때문이다.
   */
  public PipelineStepResponse withIncrementalMeta(List<String> warnings, String fullRebuildMode) {
    return new PipelineStepResponse(
        id,
        name,
        description,
        scriptType,
        scriptContent,
        outputDatasetId,
        outputDatasetName,
        inputDatasetIds,
        dependsOnStepNames,
        stepOrder,
        loadStrategy,
        apiConfig,
        aiConfig,
        pythonConfig,
        apiConnectionId,
        lastRunAt,
        fullRebuildPending,
        warnings,
        fullRebuildMode);
  }
}
