package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.pipeline.repository.PipelineExecutionRepository;
import com.smartfirehub.securitylevel.access.AccessDenialAction;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.securitylevel.access.Decision;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository.GrantSubject;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import com.smartfirehub.securitylevel.service.SecurityAuditRecorder;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 파이프라인 SQL 스텝의 보안 등급 관문(스펙 §4.2 5행). 러너·서비스 diff 를 작게 유지하려고 판정·출력 등급 처리를 이 클래스에 모았다.
 *
 * <p>실행 주체: 러너의 userId 가 곧 실행 주체다 — 수동은 요청자, 트리거는 TriggerService.fireTrigger 가 trigger.createdBy()
 * 를 넘긴다(판단 사항 6). 러너는 {@code @Async} 라 SecurityContext 가 없으므로 자격은 userId 로 직접 계산한다(판단 사항 2).
 *
 * <p>거부 메시지는 실행 이력(다른 사용자도 본다)의 스텝 오류로 저장된다 — VIEW 계열 거부는 언제나 구분 불가 메시지({@link
 * DatasetAccessGuard#SQL_ACCESS_DENIED_MESSAGE})만 쓰고 데이터셋 이름·사유를 싣지 않는다.
 */
@Component
@RequiredArgsConstructor
public class PipelineSecurityGate {

  private final ClearanceResolver clearanceResolver;
  private final DatasetAccessGuard guard;
  private final DatasetSecurityService datasetSecurityService;
  private final SecurityLevelRepository levelRepository;
  private final DSLContext dsl;

  /** 실행 주체의 감사 등급 접근 기록(스펙 §4.6). 거부 감사는 가드(requireSql·requireDatasetReads·auditDenial)가 한다. */
  private final SecurityAuditRecorder auditRecorder;

  /** 쓰기 후 확정 직전 "같은 스텝의 다른 실행과 겹쳤는가" 판정용(코드리뷰 CR1). */
  private final PipelineExecutionRepository executionRepository;

  /**
   * 한 스텝의 실행 주체 — userId 와 그 자격을 함께 들고 다닌다. 러너가 스텝마다 {@link #runAs} 로 한 번 만들어 그 스텝의 판정에 넘긴다(스텝 안
   * 판정마다 자격을 다시 계산하지 않게). 실행 단위로 캐시하지 않는다 — 다음 스텝은 그 시점의 자격으로 다시 판정한다.
   *
   * @param userId 실행 주체(미상이면 null — 등급 상향·시드의 행위자로도 쓴다)
   * @param clearance 판정 자격(userId 가 null 이면 아무것도 못 보는 자격)
   */
  public record RunAs(Long userId, Clearance clearance) {}

  /** 실행 주체의 현재 자격을 계산한다(스텝 시작 판정 직전에 한 번). 실행 주체 미상은 fail-closed. */
  public RunAs runAs(Long runAsUserId) {
    return new RunAs(runAsUserId, clearance(runAsUserId));
  }

  /**
   * 저장 시점 — 편집자 기준 VIEW 판정. {@code {{#N}}} 더미({@code step_ref_N})는 실행 전엔 실제 테이블을 알 수 없어 제외한다(판단 사항
   * 4) — 실행 시점에 실제 ptmp 테이블로 치환된 SQL 로 전부 판정된다.
   *
   * @param substitutedSql SqlValidator 가 검증한 바로 그 문자열(더미 치환본)
   */
  public void checkStepSqlForSave(Long editorUserId, String substitutedSql) {
    guard.requireSql(clearance(editorUserId), substitutedSql, SqlAccessMode.PIPELINE_SAVE);
  }

  /**
   * 실행 시점 — 실행 주체 기준 판정(VIEW). DML 쓰기 대상의 하향은 여기서 거부하지 않는다 — 러너가 {@link #propagateToWriteTargets} 로
   * 자동 상향한다(S4, 스펙 §4.5).
   *
   * @param resolvedSql 스텝 참조·증분 플레이스홀더를 치환한 뒤 실행기에 넘길 <b>바로 그 문자열</b>(가드 계약 — 다른 정규화본을 넘기면 판정한 테이블과
   *     실행되는 테이블이 갈라진다)
   * @return 판정 결과 — {@link #enforceOutputLevel} 이 입력 최대 등급({@link
   *     SqlAccessResult#effectiveLevel()})을 쓴다
   */
  public SqlAccessResult checkStepSqlForRun(RunAs runAs, String resolvedSql) {
    SqlAccessResult r =
        guard.requireSql(runAs.clearance(), resolvedSql, SqlAccessMode.PIPELINE_RUN);
    // 통과한 실행은 실행 주체를 행위자로 감사 등급 데이터셋 접근을 남긴다(읽기 ∪ 쓰기 대상). 실행 주체 미상이면 행위자가 없어 건너뛴다.
    if (runAs.userId() != null) {
      Set<Long> ids = new LinkedHashSet<>(r.readDatasetIds());
      ids.addAll(r.writeDatasetIds());
      auditRecorder.recordAccess(runAs.userId(), SecurityAuditRecorder.AccessKind.PIPELINE, ids);
    }
    return r;
  }

  /**
   * AI_CLASSIFY 스텝 저장 시점 — 편집자가 명시 입력 데이터셋(inputDatasetIds)을 모두 볼 수 있어야 한다(최종 리뷰 C3). AI_CLASSIFY 는
   * SQL 이 아니라 입력 데이터셋 테이블을 페이지 단위로 전부 읽어 LLM 으로 보내므로, SQL 스텝의 {@link #checkStepSqlForSave} 와 같은 의미를
   * id 목록으로 적용한다.
   */
  public void checkStepInputsForSave(Long editorUserId, Collection<Long> inputDatasetIds) {
    guard.requireDatasetReads(clearance(editorUserId), inputDatasetIds);
  }

  /**
   * AI_CLASSIFY 스텝 실행 시점 — 실행 주체가 <b>해석된</b> 입력(명시 입력 또는 의존 스텝 출력 자동 해석)을 모두 볼 수 있어야 한다. 반드시 입력을 읽기
   * 전, 출력 TEMP 생성·삭제 전에 부른다. 거부 메시지는 SQL 스텝과 같은 구분 불가 문구라 실행 이력에 숨김 데이터셋 정보가 남지 않는다.
   *
   * @return 판정 결과 — {@link #enforceOutputLevel} 이 입력 최대 등급을 쓴다(SQL SELECT 스텝과 같은 출력 규칙)
   */
  public SqlAccessResult checkStepInputsForRun(RunAs runAs, Collection<Long> inputDatasetIds) {
    SqlAccessResult r = guard.requireDatasetReads(runAs.clearance(), inputDatasetIds);
    // 통과한 입력 읽기는 감사 등급 접근으로 남긴다(실행 주체 미상이면 건너뜀).
    if (runAs.userId() != null) {
      auditRecorder.recordAccess(
          runAs.userId(), SecurityAuditRecorder.AccessKind.PIPELINE, r.readDatasetIds());
    }
    return r;
  }

  /**
   * SELECT 스텝의 출력 등급 처리(판단 사항 5). 반드시 출력 비우기(DELETE)·적재(INSERT) 실행 <b>전에</b> 부른다.
   *
   * <ul>
   *   <li>러너가 이번 실행에서 <b>새로 만든</b>(빈) TEMP: 등급을 정확히 입력 최대 등급으로 맞춘다(스펙 §4.5 — 기본 등급보다 낮아도). 비어 있으므로
   *       낮춰도 기존 데이터가 노출되지 않는다.
   *   <li>러너가 <b>재사용</b>하는 TEMP: 입력보다 낮으면 상향만 한다 — 이전 실행 데이터가 남아 있을 수 있어 절대 낮추지 않는다. 상향·시드 후 실행 주체가
   *       현재 등급·허용 목록으로 볼 수 없으면 쓰기 전에 거부한다(fail-closed).
   *   <li>러너 소유 TEMP 공통: 입력 최대 등급이 허용 목록 필요면 허용 목록을 시드(입력 교집합 ∪ {실행 주체})로 좁힌다(실행 주체는 언제나 포함) — 상향이
   *       일어나지 않아도. 출력이 이번 실행으로 <b>전부 교체</b>될 때(새 TEMP·REPLACE·전체 재구축)만 쓰기 성공 뒤 시드로 확정(넓힘 포함)할 계획을
   *       돌려준다(WD-30). APPEND/MERGE 는 이전 실행 행이 남아 넓히면 그 행이 새 열람자에게 보이므로 좁히기만 한다. 다른 실행 주체(수동 실행자 vs
   *       트리거 생성자)가 같은 TEMP 를 재사용할 때 다음 스텝({@code {{#N}}})이 거부되지 않게 하고, 실행 주체가 아직 볼 수 없는 TEMP 에 쓰는
   *       일이 없게 한다. 실행 주체는 이 스텝의 입력을 모두 볼 수 있음이 이미 판정됐으므로 새 열람자를 넓히지 않는다.
   *   <li>사용자가 지정한 출력: 실행 주체가 볼 수 있어야 하고, 입력보다 낮으면 자동 상향(S4, 스펙 §4.5 — 출력은 입력보다 낮아질 수 없다). 상향 등급이
   *       허용 목록 필요면 목록을 시드로 좁힌다(넓히지 않음). 낮추지는 않는다.
   * </ul>
   *
   * <p>SELECT 자동 적재는 래퍼({@code INSERT INTO 출력 ...})를 러너가 붙이므로 출력 테이블이 판정 문자열에 없다 — 그래서 여기서 따로 본다.
   *
   * <p><b>러너 소유 여부는 DB 로 판정한다</b>(origin_type='TEMP' 이고 source_pipeline_step_id = 이 스텝). 러너의 "출력
   * 미지정 → TEMP 생성" 분기는 첫 실행에서만 탄다 — 두 번째 실행부터는 {@code PipelineStepRepository.findByPipelineId} 가
   * 출력이 null 인 스텝의 출력을 그 스텝의 TEMP 로 채워(coalesce) 넘기므로, 러너 지역 변수로 판단하면 재사용 TEMP 가 "사용자 지정 출력"으로 오인돼
   * 다른 실행 주체는 VIEW 거부, 입력 등급 상승은 하향 실패가 된다(fix round 1 에서 실측).
   *
   * @param stepId 이 스텝의 id — 출력이 이 스텝의 TEMP 인지 판정한다
   * @param freshTemp 이번 실행에서 새로 만든(빈) TEMP 인가 — 러너 소유 TEMP 일 때만 의미가 있다
   * @param outputFullyReplaced 이번 실행이 출력을 통째로 비우고 다시 채우는가(REPLACE 비우기·맞바꿈, 증분 전체 재구축) — 이전 실행 행이 남지
   *     않아야만 쓰기 후 넓힘이 안전하다
   * @return 러너 소유 TEMP 의 쓰기 후 허용 목록 확정 계획 — 허용 목록 필요 입력이 아니거나, 지정 출력이거나, 이전 실행 행이 남는
   *     출력(APPEND/MERGE 재사용 TEMP)이면 null
   */
  @Transactional
  public OutputAllowlistPlan enforceOutputLevel(
      SqlAccessResult access,
      long outputDatasetId,
      long stepId,
      boolean freshTemp,
      boolean outputFullyReplaced,
      RunAs runAs) {
    Long runAsUserId = runAs.userId();
    boolean runnerOwnedTemp = isStepTemp(outputDatasetId, stepId);
    if (!runnerOwnedTemp) {
      requireOutputVisible(outputDatasetId, runAs);
    }
    LevelPolicy effective = access.effectiveLevel();
    OutputAllowlistPlan plan = null;
    // effective == null: 테이블을 읽지 않는 SELECT(상수 등) — 전파할 등급이 없다.
    if (effective != null) {
      LevelPolicy out = levelOf(outputDatasetId);
      boolean raised = out.rank() < effective.rank();
      if (raised) {
        // 스펙 §4.5: 출력은 입력보다 낮아질 수 없다 — 지정 출력이든 러너 TEMP 든 실패시키지 않고 자동 상향 + 상향 시각 + 감사(+이벤트 1건).
        datasetSecurityService.raiseForPipelineOutput(outputDatasetId, effective, runAsUserId);
      } else if (runnerOwnedTemp && freshTemp && out.rank() > effective.rank()) {
        // 새로 만든 빈 TEMP 만 입력 등급으로 정확히 맞춘다(낮추기 포함). 지정 출력·재사용 TEMP 는 절대 낮추지 않는다.
        datasetSecurityService.assignNewPipelineTempLevel(outputDatasetId, effective, runAsUserId);
      }
      // 허용 목록(WD-30): 시드 = 허용 목록 필요 입력들의 항목 교집합 ∪ {실행 주체}. 쓰기 전에는 좁히기만 한다(기존 ∩ 시드 ∪ {실행 주체}).
      // 러너 TEMP 는 상향 여부와 무관하게 매 실행 — 재사용 TEMP 를 다른 실행 주체가 쓸 때도 그 실행 주체가 들어가고(위 Javadoc), 쓰기 성공 뒤
      // completeOutputAllowlist 가 시드로 정확히 맞춘다(넓힘 포함) — 단 출력이 이번 실행으로 전부 교체될 때만.
      // 리뷰 I1: APPEND/MERGE 재사용 TEMP 는 이전 실행 행이 남아, 넓히면 그 행이 이번 입력 목록에만 있는 새 열람자에게 보인다.
      // 지정 출력은 사용자가 관리하는 목록이라 이번에 상향됐을 때만 좁히고 넓히지 않는다(계획 결정 13).
      if (effective.allowlistRequired() && (runnerOwnedTemp || raised)) {
        Set<GrantSubject> seed =
            datasetSecurityService.pipelineOutputSeed(access.readDatasetIds(), runAsUserId);
        datasetSecurityService.narrowPipelineOutputAllowlist(outputDatasetId, seed, runAsUserId);
        if (runnerOwnedTemp && (freshTemp || outputFullyReplaced)) {
          plan = new OutputAllowlistPlan(outputDatasetId, seed, runAsUserId);
        }
      }
    }
    // 재사용 TEMP 는 상향·시드를 마친 <b>현재</b> 등급·허용 목록으로 실행 주체가 볼 수 있어야 쓴다(fail-closed, 쓰기 전). 예: 이전 실행이
    // 기밀로 올려 둔 TEMP 에 지금 입력은 민감뿐인 실행 주체 B — 상향도 시드도 일어나지 않으므로, 이 검사가 없으면 B 가 볼 수 없는 TEMP 를
    // 비우고(REPLACE) 덮어써 이전 실행 주체의 결과를 지운다. 새로 만든 TEMP 는 이번 실행 주체가 방금 만든 빈 테이블이라 제외한다(입력 없는
    // 상수 SELECT 의 새 TEMP 는 기본 등급이라 낮은 자격 실행 주체가 못 볼 수 있다). 거부 시 이 트랜잭션의 상향·시드도 롤백된다.
    if (runnerOwnedTemp && !freshTemp) {
      requireOutputVisible(outputDatasetId, runAs);
    }
    return plan;
  }

  /**
   * 러너 소유 TEMP 허용 목록의 쓰기 후 확정 계획(WD-30). 출력 적재는 실행기 커넥션이라 허용 목록 변경과 한 트랜잭션으로 묶을 수 없다 — 그래서 쓰기 전에는
   * 좁히기만 하고, 쓰기가 성공한 뒤 {@link #completeOutputAllowlist} 가 이 시드로 정확히 맞춘다.
   *
   * @param seed 이번 실행의 시드(허용 목록 필요 입력들의 항목 교집합 ∪ {실행 주체})
   */
  public record OutputAllowlistPlan(long datasetId, Set<GrantSubject> seed, long runAsUserId) {}

  /**
   * 러너 소유 TEMP 쓰기 성공 뒤 허용 목록을 시드로 확정한다(WD-30 — 입력 목록에서 빠진 사람은 빠지고, 새로 들어온 사람은 들어온다). 반드시 출력이 커밋된
   * 뒤에만 부른다 — 실패 경로에서는 부르지 않아 쓰기 전 좁히기만 남는다. plan 이 null 이면(허용 목록 필요 입력 없음·지정 출력) 할 일이 없다.
   *
   * <p><b>코드리뷰 CR1 — 마지막으로 쓴 실행만 넓힌다.</b> 출력 적재(실행기 커넥션)와 이 확정(앱 커넥션)은 한 트랜잭션이 아니어서, 같은 TEMP 에 실행 두
   * 개가 겹치면 "A 쓰기 → B 쓰기 → B 확정 → A 확정" 처럼 쓰기 순서와 확정 순서가 어긋나 A 의 시드가 B 의 데이터 위에 덮일 수 있다(넓힘 누출). 그래서
   * 데이터셋 행을 잠근 뒤({@link DatasetSecurityService#narrowPipelineOutputAllowlist} 의 쓰기 전 좁히기와 같은 잠금) 같은
   * 스텝의 다른 실행이 이 실행과 겹쳤는지 본다. 겹쳤거나 판정할 수 없으면 <b>아무것도 하지 않는다</b> — 두 실행의 쓰기 전 좁히기가 이미 목록을 두 시드의 교집합
   * 쪽으로 좁혀 두었으므로 그대로 두는 것이 안전하다(가용성 비용: 넓힘은 겹치지 않은 다음 실행까지 미뤄진다). 여기서 좁히기를 다시 부르면 안 된다 — 좁히기는 실행
   * 주체를 무조건 넣으므로, 다른 실행이 이미 이 실행 주체를 뺀 목록(그 실행의 데이터)에 되돌려 넣는 넓힘이 된다.
   *
   * <p>잠금 순서가 원자성을 준다: 이 판정·확정이 커밋되기 전에는 겹친 실행의 쓰기 전 좁히기가 행 잠금에서 기다리고, 그 실행은 RUNNING 표시를 좁히기보다 먼저
   * 커밋하므로 이 판정 시점에 이미 RUNNING 이면 여기서 보이고, 아니면 그 실행의 좁히기·쓰기는 이 확정 뒤에 온다.
   *
   * @param stepExecId 이 확정을 하는 스텝 실행 id(겹침 판정 기준)
   */
  @Transactional
  public void completeOutputAllowlist(OutputAllowlistPlan plan, long stepExecId) {
    if (plan == null) {
      return;
    }
    if (!datasetSecurityService.lockDatasetRow(plan.datasetId())) {
      // TEMP 가 사라졌다(겹친 실행이 스키마 변경으로 지우고 다시 만듦) — 확정할 대상이 없다.
      return;
    }
    if (executionRepository.hasOverlappingStepExecution(stepExecId)) {
      return;
    }
    datasetSecurityService.resetPipelineOutputAllowlist(
        plan.datasetId(), plan.seed(), plan.runAsUserId());
  }

  /**
   * 사용자가 지정한 출력 데이터셋을 실행 주체가 볼 수 있어야 한다. DML 스텝도 REPLACE 면 러너가 출력 비우기(DELETE) 선행 문장을 붙이므로, 이 판정이
   * 없으면 볼 수 없는 데이터셋을 비울 수 있다. 없는 데이터셋·숨김 데이터셋은 같은 거부(존재 은닉). 러너는 재사용·삭제할 TEMP 와 API_CALL 스텝에 들어온
   * 출력(지정 출력·재사용 TEMP 모두 — 후속 F1)에도 같은 판정을 쓴다. PYTHON 스텝에 들어온 출력의 쓰기 전 판정은 {@link
   * #enforcePythonOutputLevel} 이 한다(지정 출력은 등급 처리 전, 재사용 TEMP 는 등급 처리 뒤 — 코드리뷰 8).
   */
  public void requireOutputVisible(long outputDatasetId, RunAs runAs) {
    Decision d = guard.check(runAs.clearance(), outputDatasetId, DatasetAction.VIEW, null);
    if (!d.allowed()) {
      // 실행이 거부로 끝나는 지점 — 실제 사유를 감사에만 남긴다(없는 데이터셋 LEVEL_UNKNOWN 은 auditDenial 이 거른다).
      guard.auditDenial(
          runAs.clearance(),
          AccessDenialAction.PIPELINE,
          new DatasetAccessGuard.DenialDetail(d.reasonCode(), outputDatasetId, null));
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          DatasetAccessGuard.SQL_ACCESS_DENIED_CODE,
          DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    }
  }

  /**
   * API_CALL·PYTHON 스텝 저장 시점 — 편집자가 사용자 지정 출력 데이터셋을 볼 수 있어야 한다(코드리뷰 CR2). 두 스텝은 SQL 관문을 거치지 않고 출력을
   * 비우거나(REPLACE) 덮어쓰므로, 판정이 없으면 볼 수 없는 데이터셋을 출력으로 지정해 내용을 지울 수 있다. 러너 TEMP({@code
   * origin_type='TEMP'})는 건너뛴다 — 편집 화면이 GET 의 출력 폴백(스텝 TEMP id)을 그대로 되돌려 보내므로, 이를 판정하면 TEMP 등급이 오른
   * 파이프라인을 다른 편집자가 재저장하지 못한다. 그 TEMP 쓰기는 실행 시점에 실행 주체 기준으로 판정된다. 거부는 SQL 스텝 저장과 같은 403 {@code
   * DATASET_SQL_ACCESS_DENIED}(구분 불가 메시지).
   *
   * <p>TEMP 예외는 <b>이 파이프라인에 이미 있던</b> 출력(되돌아온 자기 TEMP 폴백)에만 적용한다(Task 3 리뷰 M1). 예전처럼 모든 TEMP 를
   * 통과시키면 다른 파이프라인의 숨김 TEMP id 는 저장 성공·없는 id 는 403 으로 갈려 존재 오라클이 됐다.
   *
   * @param alreadyInPipeline 이 id 가 저장 전 이 파이프라인의 (해석된) 출력 집합에 있었는가
   */
  public void checkStepOutputForSave(
      Long editorUserId, Long outputDatasetId, boolean alreadyInPipeline) {
    if (outputDatasetId == null
        || (alreadyInPipeline
            && dsl.fetchExists(
                DATASET, DATASET.ID.eq(outputDatasetId).and(DATASET.ORIGIN_TYPE.eq("TEMP"))))) {
      return;
    }
    guard.requireDatasetReads(clearance(editorUserId), List.of(outputDatasetId));
  }

  /**
   * 저장 시점 — 스텝이 <b>새로</b> 참조하는 데이터셋(SQL·AI_CLASSIFY 출력, SQL·PYTHON·API_CALL 입력)을 편집자가 볼 수 있어야
   * 한다(Task 3 B2·WD-21). {@link #checkStepOutputForSave} 와 달리 TEMP 도 예외 없이 판정한다 — 호출자가 이 파이프라인에 이미
   * 있던 id(자기 TEMP 폴백 포함)를 미리 걸러 넘기므로 예외가 필요 없고, 예외를 두면 다른 파이프라인의 숨김 TEMP id 는 통과·없는 id 는 거부로 갈려 존재
   * 오라클이 된다. 숨김·없음·null 은 같은 403 {@code DATASET_SQL_ACCESS_DENIED}. 반드시 FK 가 걸린
   * insert(saveStep·saveStepInput)와 MERGE PK 조회 <b>전에</b> 부른다 — 그래야 "없음"이 FK 오류로 따로 드러나지 않는다.
   */
  public void checkNewStepReferencesForSave(Long editorUserId, Collection<Long> newDatasetIds) {
    if (newDatasetIds.isEmpty()) {
      return;
    }
    guard.requireDatasetReads(clearance(editorUserId), newDatasetIds);
  }

  /**
   * SQL 스텝의 선언 입력(inputDatasetIds)을 판정 결과에 합친다(스펙 §4.5 "referencedTables ∪ 선언 입력", 계획 결정 10). 선언
   * 입력도 실행 주체가 VIEW 할 수 있어야 한다(AI_CLASSIFY 입력과 같은 규칙, 구분 불가 403) — 선언만 하고 SQL 에서 읽지 않는 숨김 입력이 있어도
   * 실패한다(fail-closed). 실효 등급은 둘의 최대, 내보내기 허용은 둘 다. 쓰기 대상은 SQL 판정의 것 그대로다.
   *
   * <p>선언 입력은 SQL 이 실제로 읽는다는 보장이 없어 감사 등급 접근 기록은 남기지 않는다(실제로 읽는 테이블은 {@link #checkStepSqlForRun} 이
   * 이미 남겼다).
   */
  public SqlAccessResult mergeDeclaredInputs(
      RunAs runAs, SqlAccessResult sqlAccess, Collection<Long> declaredInputIds) {
    if (declaredInputIds == null || declaredInputIds.isEmpty()) {
      return sqlAccess;
    }
    SqlAccessResult declared = guard.requireDatasetReads(runAs.clearance(), declaredInputIds);
    LevelPolicy a = sqlAccess.effectiveLevel();
    LevelPolicy b = declared.effectiveLevel();
    LevelPolicy eff = a == null ? b : (b == null || a.rank() >= b.rank() ? a : b);
    Set<Long> reads = new LinkedHashSet<>(sqlAccess.readDatasetIds());
    reads.addAll(declared.readDatasetIds());
    return new SqlAccessResult(
        true,
        null,
        null,
        eff,
        reads,
        sqlAccess.writeDatasetIds(),
        sqlAccess.exportAllowed() && declared.exportAllowed());
  }

  /**
   * DML 스텝의 쓰기 대상 중 입력 최대 등급보다 낮은 것을 자동 상향한다(스펙 §4.5 — 쓰기 하향은 파이프라인에서 거부가 아니라 상향). 쓰기 대상의 VIEW 는
   * 가드(checkStepSqlForRun)가 이미 판정했다. 반드시 실행(출력 비우기 선행 문장 포함)보다 먼저 부른다 — 낮은 등급에 높은 등급 데이터가 잠깐이라도 쓰이지
   * 않게. 상향 등급이 허용 목록 필요면 그 대상의 허용 목록을 시드(입력 교집합 ∪ {실행 주체})로 좁힌다(WD-30 — 사용자가 관리하는 목록이라 넓히지 않고 쓰기 후
   * 확정도 하지 않는다).
   */
  @Transactional
  public void propagateToWriteTargets(SqlAccessResult access, RunAs runAs) {
    LevelPolicy effective = access.effectiveLevel();
    if (effective == null) {
      return;
    }
    Set<GrantSubject> seed = null;
    for (Long id : access.writeDatasetIds()) {
      if (levelOf(id).rank() < effective.rank()) {
        datasetSecurityService.raiseForPipelineOutput(id, effective, runAs.userId());
        if (effective.allowlistRequired()) {
          if (seed == null) {
            seed =
                datasetSecurityService.pipelineOutputSeed(access.readDatasetIds(), runAs.userId());
          }
          datasetSecurityService.narrowPipelineOutputAllowlist(id, seed, runAs.userId());
        }
      }
    }
  }

  /**
   * PYTHON 스텝 출력 등급(스펙 §4.5, 공통 결정 R4). 입력 읽기를 SQL 처럼 판정할 수 없으므로 "스크립트가 읽을 수 있던 최대 등급"을 입력 등급으로 본다
   * — 흐름 C 의 슬롯 롤은 실행 주체 자격 이하이면서 허용 목록 필요가 아닌 등급까지만 읽게 하므로 그 범위의 최고 등급이다({@link
   * #pythonReadableTopLevel}). 실행 주체 자격 등급 자체가 아니다(예: ADMIN 자격 '기밀'은 허용 목록 등급이라 못 읽으므로 출력 = '민감').
   * 그 범위에 허용 목록 등급이 없으므로 허용 목록 시드·좁히기·쓰기 후 확정도 없다(enforceOutputLevel 이 돌려주는 계획은 언제나 null — 그래서 반환하지
   * 않는다). 지정 출력이 이미 허용 목록 등급이어도 상향이 없으므로 사용자 관리 목록을 건드리지 않고, 실행 주체가 그 목록으로 볼 수 있어야만 쓴다. 이후 처리는 SQL
   * SELECT 출력과 같다({@link #enforceOutputLevel} — 지정 출력·재사용 TEMP 는 상향만, 새 TEMP 는 정확히 맞춤, 재사용 TEMP 는
   * 쓰기 전 VIEW). 반드시 출력 비우기·맞바꿈·적재 전에 부른다.
   *
   * <p>범위가 비면(자격 이하 등급이 모두 허용 목록 필요, 또는 역할 없음) 스크립트가 읽을 수 있는 데이터셋이 없다 — 전파할 입력 등급이 없는 것으로 보고(SQL 의
   * 상수 SELECT 와 같은 규칙) 출력 VIEW 만 본다. 기본 등급으로 맞추면 실행 주체 자격보다 높아 자기 출력을 못 볼 수 있고, 최하위 등급으로 맞추면 그 등급이
   * 허용 목록 등급이라 시드가 필요해진다 — 둘 다 R4 의 전제와 어긋난다.
   */
  @Transactional
  public void enforcePythonOutputLevel(
      long outputDatasetId, long stepId, boolean freshTemp, RunAs runAs) {
    var readable = pythonReadableTopLevel(levelRepository.findAll(), runAs.clearance().rank());
    if (readable.isEmpty()) {
      requireOutputVisible(outputDatasetId, runAs);
      return;
    }
    // fail-closed 단언(R4): readable 은 정의상 allowlist_required 가 아니다.
    // 이 전제가 깨지면(계산 규칙 변경 등) 아래 계획 반환값을 버리는 것이 쓰기 후 확정 누락이 되고,
    // {실행 주체}만의 시드가 허용 목록 등급 출력에 붙는다 — 조용히 진행하지 않고 스텝을 실패시킨다.
    if (readable.get().allowlistRequired()) {
      throw new IllegalStateException("PYTHON 읽기 가능 최고 등급이 허용 목록 필요 등급입니다(R4 위반)");
    }
    // 계획 반환값은 버린다 — 위 단언으로 effective 가 허용 목록 필요가 아니므로 언제나 null 이다. 출력 교체 여부도 그래서 의미가 없다(false).
    enforceOutputLevel(
        new SqlAccessResult(true, null, null, readable.get(), Set.of(), Set.of(), true),
        outputDatasetId,
        stepId,
        freshTemp,
        false,
        runAs);
  }

  /**
   * PYTHON 스크립트가 읽을 수 있는 최고 등급(공통 결정 R4): rank ≤ 실행 주체 자격 rank 이고 {@code allowlist_required} 가 아닌
   * 등급 중 rank 최대. 흐름 C 의 PYTHON 슬롯 위치 계산과 <b>같은 규칙</b>이어야 한다(C Task 10 의 일치 테스트) — 관리자
   * 우회(admin_bypass) 등 다른 조건을 넣으면 두 계산이 어긋난다. 범위가 비면 empty.
   *
   * @param levels 현재 테넌트의 등급 전부
   * @param clearanceRank 실행 주체 자격 rank({@link Clearance#NO_RANK} 면 언제나 empty)
   */
  public static java.util.Optional<LevelPolicy> pythonReadableTopLevel(
      Collection<LevelPolicy> levels, int clearanceRank) {
    return levels.stream()
        .filter(l -> l.rank() <= clearanceRank && !l.allowlistRequired())
        .max(java.util.Comparator.comparingInt(LevelPolicy::rank));
  }

  /** 데이터셋의 현재 등급(존재가 확인된 데이터셋에만). */
  private LevelPolicy levelOf(long datasetId) {
    return levelRepository.findById(levelRepository.findDatasetLevelId(datasetId)).orElseThrow();
  }

  /**
   * 출력 데이터셋이 이 스텝의 러너 소유 TEMP 인가 — TempDatasetService.createTempDataset 이
   * origin_type·source_pipeline_step_id 를 남긴다.
   */
  private boolean isStepTemp(long datasetId, long stepId) {
    return dsl.fetchExists(
        DATASET,
        DATASET
            .ID
            .eq(datasetId)
            .and(DATASET.ORIGIN_TYPE.eq("TEMP"))
            .and(DATASET.SOURCE_PIPELINE_STEP_ID.eq(stepId)));
  }

  /** 실행 주체 미상(삭제된 트리거 생성자 등)은 아무것도 못 보는 자격 — fail-closed. */
  private Clearance clearance(Long userId) {
    return userId == null
        ? Clearance.none(-1L, TenantContext.require("파이프라인 보안 판정"))
        : clearanceResolver.resolve(userId);
  }
}
