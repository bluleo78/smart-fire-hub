package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.PipelineStepRequest;
import com.smartfirehub.pipeline.dto.PipelineStepResponse;
import com.smartfirehub.pipeline.dto.StepCursor;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 파이프라인 스텝(pipeline_step)과 입력 데이터셋·의존 관계 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqPipelineStepRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface PipelineStepRepository {

  /** 실행·내부 경로용 — 출력 데이터셋 이름을 가리지 않는다(러너·트리거 스레드에는 요청 사용자가 없어 가시성 조건을 만들 수 없다). */
  List<PipelineStepResponse> findByPipelineId(Long pipelineId);

  /**
   * 화면 응답용 — {@code outputNameVisibility} 를 출력 데이터셋 LEFT JOIN 의 ON 에 더해, 조회자가 볼 수 없는 출력 데이터셋은 이름만
   * null 이 된다(보안 등급, 스펙 §2.5). <b>출력·입력 데이터셋 id 는 그대로 둔다</b> — 웹 편집기가 PUT 으로 그대로 되돌려 보내므로 id 를 지우면
   * 저장 한 번에 참조가 사라진다.
   *
   * @param outputNameVisibility {@code "dataset"} 이름 관례로 렌더하는 가시성
   *     조건(JooqDatasetVisibility#condition(DatasetVisibility))
   */
  List<PipelineStepResponse> findByPipelineId(
      Long pipelineId, DatasetVisibility outputNameVisibility);

  Long saveStep(Long pipelineId, PipelineStepRequest request, int stepOrder);

  /**
   * 기존 스텝을 제자리 갱신한다(WD-108) — id 를 유지해야 과거 실행 기록(pipeline_step_execution.step_id)과 러너 TEMP
   * (dataset.source_pipeline_step_id)가 같은 스텝에 계속 이어진다. 저장 요청이 담는 정의 필드만 바꾸고, 책갈피({@code
   * last_run_at}·{@code full_rebuild_pending})와 {@code created_at} 은 건드리지 않는다 — 책갈피는 호출부가 이월 규칙으로
   * 따로 정한다({@link #setCursor}).
   *
   * <p>{@code pipelineId} 를 조건에 함께 거는 이유: 호출부가 이 파이프라인의 스텝 id 만 넘기도록 검증하지만, 리포지토리 단에서도 다른 파이프라인의 행을
   * 고칠 수 없게 이중으로 막는다.
   */
  void updateStep(Long pipelineId, Long stepId, PipelineStepRequest request, int stepOrder);

  /**
   * 이름을 바꿀 스텝에 잠시 겹치지 않는 임시 이름을 준다(WD-108).
   *
   * <p>{@code UNIQUE (pipeline_id, name)}(V3:24)은 문장마다 즉시 검사된다. 두 스텝의 이름을 맞바꾸는 저장(A→B, B→A)을 차례로
   * UPDATE 하면 첫 문장에서 아직 B 인 행과 충돌한다. 이름이 바뀌는 스텝을 먼저 전부 임시 이름으로 비켜 두면 이후 최종 이름 UPDATE 가 서로 부딪히지 않는다.
   * 임시 이름은 같은 트랜잭션 안에서 곧바로 최종 이름으로 덮인다(실패하면 롤백).
   */
  void renameStepTemporarily(Long pipelineId, Long stepId);

  void saveStepInput(Long stepId, Long datasetId);

  void saveStepDependency(Long stepId, Long dependsOnStepId);

  void deleteByPipelineId(Long pipelineId);

  /**
   * 지정한 스텝들을 입력·의존과 함께 지운다(WD-108 — 저장 요청에서 빠진 스텝만 지울 때 쓴다).
   *
   * <p>이 스텝들의 과거 실행 기록은 지워지지 않는다 — V139 가 {@code pipeline_step_execution.step_id} FK 를 {@code ON
   * DELETE SET NULL} 로 바꿨으므로 기록은 {@code step_name} 스냅샷과 함께 남고 스텝 연결만 끊긴다. 의존은 양방향({@code
   * step_id}·{@code depends_on_step_id})을 모두 지운다 — 남은 스텝이 지워진 스텝을 가리키는 엣지는 FK CASCADE 로도 지워지지만,
   * 명시적으로 지워 두면 FK 정의가 바뀌어도 의미가 흔들리지 않는다.
   */
  void deleteSteps(Long pipelineId, Collection<Long> stepIds);

  /**
   * 제자리 갱신할 스텝들의 입력·의존을 비운다(WD-108). 저장 요청이 입력·의존 전체를 다시 보내므로(전체 교체 계약) 남길 행을 골라내지 않고 비운 뒤 다시 넣는다 —
   * 스텝 행(id)은 그대로라 실행 기록은 영향이 없다.
   */
  void clearInputsAndDependencies(Collection<Long> stepIds);

  /**
   * 스텝별 러너 TEMP(실행기가 출력 미지정 스텝용으로 만든 임시 데이터셋) id 를 한 번에 읽는다(스텝 id → TEMP id, TEMP 없는 스텝은 맵에 없음).
   * {@link #resolvedOutputDatasetId} 의 폴백과 같은 규칙({@code source_pipeline_step_id} = 스텝 id)이며, 한 스텝에
   * TEMP 가 여럿이면 그 식의 {@code LIMIT 1} 처럼 하나만 쓴다.
   */
  Map<Long, Long> findTempDatasetIdsByStepIds(Collection<Long> stepIds);

  /**
   * 주어진 데이터셋 중 소유 스텝이 있는 러너 TEMP({@code origin_type='TEMP'} 이고 {@code source_pipeline_step_id} 가
   * 있음)의 데이터셋 id → 소유 스텝 id 를 돌려준다(WD-224 — 스텝 출력 저장 검증). TEMP 가 아니거나 소유 스텝이 끊긴(SET NULL) 고아 TEMP 는
   * 맵에 없다.
   */
  Map<Long, Long> findTempOwnerStepIdsByDatasetIds(Collection<Long> datasetIds);

  /**
   * 주어진 스텝들이 만든(러너 자동 생성) 임시 데이터셋 id 를 모두 읽는다(WD-170 — 스텝·파이프라인 삭제 때 함께 지울 후보).
   *
   * <p>{@link #findTempDatasetIdsByStepIds} 와 달리 한 스텝에 여럿이어도 전부 돌려준다 — 정리 대상이므로 하나도 빠뜨리면 안 된다.
   * {@code origin_type='TEMP'} 로 한정해, 출처가 TEMP 가 아닌 데이터셋에 스텝 연결이 남아 있더라도(정상 경로로는 생기지 않음) 사용자가 만든
   * 데이터셋을 지우지 않게 한다. 스텝을 지우면 {@code source_pipeline_step_id} 가 SET NULL(V36)이 되어 더는 찾을 수 없으므로 반드시
   * 스텝 삭제 <b>전에</b> 불러야 한다.
   */
  List<Long> findAutoTempDatasetIdsByStepIds(Collection<Long> stepIds);

  /**
   * 주어진 데이터셋들 중 {@code excludedStepIds} 밖의 스텝이 <b>출력</b>({@code output_dataset_id})으로 쓰고 있는 것의 id 를
   * 돌려준다 (WD-170 — 다른 스텝이 출력으로 쓰는 임시 데이터셋은 지우지 않는다. {@code DatasetService.deleteDataset} 의 출력 참조
   * 거부와 같은 규칙이며, 그 거부가 트랜잭션을 롤백 전용으로 만들기 전에 미리 걸러 내는 데 쓴다).
   *
   * <p>제외 스텝은 "곧 지워질 스텝" — 스텝이 자기 임시 데이터셋을 출력으로 되돌려 저장해 둔 경우(웹이 해석된 출력을 왕복 저장)를 참조로 세지 않기 위해서다. 이미
   * 지운 뒤에 부르면 제외 목록은 비어 있어도 된다.
   */
  Set<Long> findDatasetIdsUsedAsOutputExcludingSteps(
      Collection<Long> datasetIds, Collection<Long> excludedStepIds);

  /** 파이프라인의 스텝 id 목록(WD-170 — 파이프라인 삭제·삭제 영향 조회에서 함께 지울 임시 데이터셋을 찾는 데 쓴다). */
  List<Long> findStepIdsByPipelineId(Long pipelineId);

  /** 스텝 증분 책갈피 조회. 스텝이 없으면 빈 Optional. */
  Optional<StepCursor> findCursor(Long stepId);

  /**
   * 출력 커밋 성공 <b>후에만</b> 호출 — 책갈피를 전진시키고 전체 재생성 예약을 해제한다.
   *
   * <p>실패·부분 적용 경로에서 호출하면 그 실행이 읽지 못한 행을 영원히 건너뛴다. 출력과 이 갱신은 서로 다른 커넥션이라 한 트랜잭션으로 묶을 수 없다 — 그래서 "출력
   * 커밋 → 책갈피 전진" 순서가 계약이고, 그 사이에 죽으면 다음 실행이 같은 구간을 다시 읽는다(MERGE 멱등성이 흡수).
   *
   * <p><b>예약 해제는 {@code wasFullRebuild} 일 때만 한다.</b> 무조건 false 로 쓰면, 이번 실행이 {@code findCursor} 로
   * 예약을 읽은 뒤(=이번 실행은 증분으로 돌기로 확정된 뒤) 운영자가 켠 예약이 전체 재생성을 한 번도 하지 않은 채 사라진다. 반대로 "이번 실행이 실제로 전체
   * 재생성이었을 때" 해제하면, 그 사이에 켜진 예약을 함께 해제하더라도 이미 전체를 다시 만든 뒤이므로 무해하다.
   *
   * <p><b>스텝의 현재 출력이 이번 실행이 실제로 쓴 출력과 같을 때만 전진한다(WD-108).</b> 저장이 스텝을 제자리 갱신(id 유지)하게 되면서, 실행 도중
   * 사용자가 그 스텝의 출력 데이터셋을 바꿔 저장하면 끝난 실행이 <b>새 출력</b>의 책갈피를 전진시킬 수 있게 됐다 — 새 출력은 그 구간 데이터를 받은 적이 없으므로
   * 다음 실행부터 그 구간이 영원히 빈다(조용한 데이터 누락). 예전에는 저장이 스텝 id 를 바꿨기 때문에 이 갱신이 사라진 id 에 맞아 아무 일도 없었다. 출력 비교는
   * {@link #resolvedOutputDatasetId}(지정 출력, 없으면 러너 TEMP)로 한다. 스텝이 그 사이 지워졌으면 0행 갱신이다.
   *
   * @param wasFullRebuild 이번 실행이 전체 재생성이었는지(예약을 소비했는지)
   * @param writtenOutputDatasetId 이번 실행이 실제로 적재한 출력 데이터셋 id(러너가 만든 TEMP 포함, 출력 없는 사용자 DML 은 null)
   */
  void advanceCursor(
      Long stepId, OffsetDateTime candidate, boolean wasFullRebuild, Long writtenOutputDatasetId);

  /** 전체 재생성 예약(해제). 예약은 다음 성공 실행에서만 해제된다 — 실패하면 유지된다. */
  void setFullRebuildPending(Long stepId, boolean pending);

  /**
   * 책갈피를 지정 값으로 맞춘다(WD-108 — 제자리 갱신 저장의 이월 규칙 적용). 이월하지 않을 때는 {@code (null, false)} 로 "한 번도 실행되지 않은
   * 스텝"과 같은 상태로 되돌린다.
   */
  void setCursor(Long stepId, OffsetDateTime lastRunAt, boolean pending);

  Optional<Long> findStepIdByPipelineAndName(Long pipelineId, String name);
}
