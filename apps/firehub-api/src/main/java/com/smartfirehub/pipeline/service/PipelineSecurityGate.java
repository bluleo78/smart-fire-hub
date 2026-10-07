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
   * SELECT 스텝의 출력 등급 처리(판단 사항 5). 러너가 자동 생성한 TEMP 는 입력 최대 등급으로 상향하고, 사용자가 지정한 출력은 실행 주체가 볼 수 있어야 하며
   * 입력보다 낮으면 실패({@code SQL_WRITE_DOWNGRADE})한다 — 지정 출력의 자동 상향은 S4.
   *
   * <p>SELECT 자동 적재는 래퍼({@code INSERT INTO 출력 ...})를 러너가 붙이므로 출력 테이블이 판정 문자열에 없다 — 그래서 여기서 따로 본다.
   */
  @Transactional
  public void enforceOutputLevel(
      SqlAccessResult access, long outputDatasetId, boolean runnerOwnedTemp, Long runAsUserId) {
    if (!runnerOwnedTemp) {
      requireOutputVisible(outputDatasetId, runAsUserId);
    }
    LevelPolicy effective = access.effectiveLevel();
    if (effective == null) {
      // 테이블을 읽지 않는 SELECT(상수 등) — 전파할 등급이 없다.
      return;
    }
    Long outLevelId =
        dsl.select(DATASET.SECURITY_LEVEL_ID)
            .from(DATASET)
            .where(DATASET.ID.eq(outputDatasetId))
            .fetchSingle(DATASET.SECURITY_LEVEL_ID);
    LevelPolicy out = levelRepository.findById(outLevelId).orElseThrow();
    if (out.rank() >= effective.rank()) {
      return;
    }
    if (runnerOwnedTemp) {
      datasetSecurityService.raiseForPipelineOutput(outputDatasetId, effective, runAsUserId);
      return;
    }
    // VIEW 를 통과한 뒤에만 오는 분기라 등급 이름은 실행 주체가 이미 볼 수 있는 정보다(가드의 쓰기 하향 메시지와 같은 문구).
    throw new CodedApiException(
        HttpStatus.FORBIDDEN,
        DatasetAccessGuard.SQL_WRITE_DOWNGRADE_CODE,
        "'" + effective.name() + "' 데이터를 더 낮은 등급 데이터셋에 쓸 수 없습니다");
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

  /** 실행 주체 미상(삭제된 트리거 생성자 등)은 아무것도 못 보는 자격 — fail-closed. */
  private Clearance clearance(Long userId) {
    return userId == null
        ? Clearance.none(-1L, TenantContext.require("파이프라인 보안 판정"))
        : clearanceResolver.resolve(userId);
  }
}
