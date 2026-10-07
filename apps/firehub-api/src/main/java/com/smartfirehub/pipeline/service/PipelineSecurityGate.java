package com.smartfirehub.pipeline.service;

import static com.smartfirehub.jooq.Tables.DATASET;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.access.DatasetAccessGuard;
import com.smartfirehub.securitylevel.access.DatasetAction;
import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.access.SqlAccessMode;
import com.smartfirehub.securitylevel.access.SqlAccessResult;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import com.smartfirehub.securitylevel.service.DatasetSecurityService;
import java.util.Collection;
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
   * 실행 시점 — 실행 주체 기준 판정. DML 쓰기 대상의 하향은 거부한다(S2 임시 — S4 가 자동 상향으로 대체).
   *
   * @param resolvedSql 스텝 참조·증분 플레이스홀더를 치환한 뒤 실행기에 넘길 <b>바로 그 문자열</b>(가드 계약 — 다른 정규화본을 넘기면 판정한 테이블과
   *     실행되는 테이블이 갈라진다)
   * @return 판정 결과 — {@link #enforceOutputLevel} 이 입력 최대 등급({@link
   *     SqlAccessResult#effectiveLevel()})을 쓴다
   */
  public SqlAccessResult checkStepSqlForRun(Long runAsUserId, String resolvedSql) {
    return guard.requireSql(clearance(runAsUserId), resolvedSql, SqlAccessMode.PIPELINE_RUN);
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
  public SqlAccessResult checkStepInputsForRun(Long runAsUserId, Collection<Long> inputDatasetIds) {
    return guard.requireDatasetReads(clearance(runAsUserId), inputDatasetIds);
  }

  /**
   * SELECT 스텝의 출력 등급 처리(판단 사항 5). 반드시 출력 비우기(DELETE)·적재(INSERT) 실행 <b>전에</b> 부른다.
   *
   * <ul>
   *   <li>러너가 이번 실행에서 <b>새로 만든</b>(빈) TEMP: 등급을 정확히 입력 최대 등급으로 맞춘다(스펙 §4.5 — 기본 등급보다 낮아도). 비어 있으므로
   *       낮춰도 기존 데이터가 노출되지 않는다.
   *   <li>러너가 <b>재사용</b>하는 TEMP: 입력보다 낮으면 상향만 한다 — 이전 실행 데이터가 남아 있을 수 있어 절대 낮추지 않는다. 상향·시드 후 실행 주체가
   *       현재 등급·허용 목록으로 볼 수 없으면 쓰기 전에 거부한다(fail-closed).
   *   <li>러너 소유 TEMP 공통: 입력 최대 등급이 허용 목록 필요면 실행 주체를 허용 목록에 (멱등) 넣는다 — 상향이 일어나지 않아도. 다른 실행 주체(수동 실행자
   *       vs 트리거 생성자)가 같은 TEMP 를 재사용할 때 다음 스텝({@code {{#N}}})이 거부되지 않게 하고, 실행 주체가 아직 볼 수 없는 TEMP 에
   *       쓰는 일이 없게 한다. 실행 주체는 이 스텝의 입력을 모두 볼 수 있음이 이미 판정됐으므로 새 열람자를 넓히지 않는다.
   *   <li>사용자가 지정한 출력: 실행 주체가 볼 수 있어야 하고, 입력보다 낮으면 실패({@code SQL_WRITE_DOWNGRADE}) — 지정 출력의 자동 상향은
   *       S4.
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
   */
  @Transactional
  public void enforceOutputLevel(
      SqlAccessResult access,
      long outputDatasetId,
      long stepId,
      boolean freshTemp,
      Long runAsUserId) {
    boolean runnerOwnedTemp = isStepTemp(outputDatasetId, stepId);
    if (!runnerOwnedTemp) {
      requireOutputVisible(outputDatasetId, runAsUserId);
    }
    LevelPolicy effective = access.effectiveLevel();
    // effective == null: 테이블을 읽지 않는 SELECT(상수 등) — 전파할 등급이 없다.
    if (effective != null) {
      Long outLevelId =
          dsl.select(DATASET.SECURITY_LEVEL_ID)
              .from(DATASET)
              .where(DATASET.ID.eq(outputDatasetId))
              .fetchSingle(DATASET.SECURITY_LEVEL_ID);
      LevelPolicy out = levelRepository.findById(outLevelId).orElseThrow();
      if (!runnerOwnedTemp) {
        if (out.rank() < effective.rank()) {
          // VIEW 를 통과한 뒤에만 오는 분기라 등급 이름은 실행 주체가 이미 볼 수 있는 정보다(가드의 쓰기 하향 메시지와 같은 문구).
          throw new CodedApiException(
              HttpStatus.FORBIDDEN,
              DatasetAccessGuard.SQL_WRITE_DOWNGRADE_CODE,
              "'" + effective.name() + "' 데이터를 더 낮은 등급 데이터셋에 쓸 수 없습니다");
        }
        return;
      }
      if (out.rank() < effective.rank()) {
        datasetSecurityService.raiseForPipelineOutput(outputDatasetId, effective, runAsUserId);
      } else if (freshTemp && out.rank() > effective.rank()) {
        datasetSecurityService.assignNewPipelineTempLevel(outputDatasetId, effective, runAsUserId);
      }
      // 상향 여부와 무관하게 — 재사용 TEMP 를 다른 실행 주체가 쓸 때도 시드한다(위 Javadoc). 상향 경로에서 이미 넣었으면 멱등으로 건너뛴다.
      if (effective.allowlistRequired()) {
        datasetSecurityService.seedPipelineOutputRunAs(outputDatasetId, runAsUserId);
      }
    }
    // 재사용 TEMP 는 상향·시드를 마친 <b>현재</b> 등급·허용 목록으로 실행 주체가 볼 수 있어야 쓴다(fail-closed, 쓰기 전). 예: 이전 실행이
    // 기밀로 올려 둔 TEMP 에 지금 입력은 민감뿐인 실행 주체 B — 상향도 시드도 일어나지 않으므로, 이 검사가 없으면 B 가 볼 수 없는 TEMP 를
    // 비우고(REPLACE) 덮어써 이전 실행 주체의 결과를 지운다. 새로 만든 TEMP 는 이번 실행 주체가 방금 만든 빈 테이블이라 제외한다(입력 없는
    // 상수 SELECT 의 새 TEMP 는 기본 등급이라 낮은 자격 실행 주체가 못 볼 수 있다). 거부 시 이 트랜잭션의 상향·시드도 롤백된다.
    if (runnerOwnedTemp && !freshTemp) {
      requireOutputVisible(outputDatasetId, runAsUserId);
    }
  }

  /**
   * 사용자가 지정한 출력 데이터셋을 실행 주체가 볼 수 있어야 한다. DML 스텝도 REPLACE 면 러너가 출력 비우기(DELETE) 선행 문장을 붙이므로, 이 판정이
   * 없으면 볼 수 없는 데이터셋을 비울 수 있다. 없는 데이터셋·숨김 데이터셋은 같은 거부(존재 은닉).
   */
  public void requireOutputVisible(long outputDatasetId, Long runAsUserId) {
    if (!guard.check(clearance(runAsUserId), outputDatasetId, DatasetAction.VIEW, null).allowed()) {
      throw new CodedApiException(
          HttpStatus.FORBIDDEN,
          DatasetAccessGuard.SQL_ACCESS_DENIED_CODE,
          DatasetAccessGuard.SQL_ACCESS_DENIED_MESSAGE);
    }
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
