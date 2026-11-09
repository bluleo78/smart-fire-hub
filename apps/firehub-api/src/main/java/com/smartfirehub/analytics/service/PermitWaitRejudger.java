package com.smartfirehub.analytics.service;

import com.smartfirehub.pipeline.service.PipelineDataSchema;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.ClearanceResolver;
import com.smartfirehub.securitylevel.sql.GuardedSqlExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 화면 쿼리 허가를 기다린 뒤의 재판정 규칙(WD-684) 한 벌 — 차트 단건({@link ChartService#getChartData})과 대시보드 일괄({@link
 * AnalyticsDashboardService})이 같이 쓴다(WD-689). 예전에는 두 서비스가 같은 규칙을 따로 들고 있어 한쪽만 고칠 위험이 있었다.
 *
 * <p><b>왜.</b> 앞 판정은 허가 대기(최대 wait-ms) 전에 끝난다. 기다리는 동안 관리자가 열람자 역할의 최대 등급을 낮추거나 데이터셋 등급을 올리면 앞 판정
 * 토큰은 낡아, 그대로 실행·전달하면 이제는 볼 수 없는 데이터가 나간다. 데이터셋 /query 의 실행 직전 재판정(WD-638·WD-682)과 같은 규칙이다. 자격은
 * {@link ClearanceResolver#resolve(long)} 로 DB 에서 새로 읽는다 — 요청 캐시를 쓰지 않으므로 {@code readFresh()} 와 같은
 * 효과이고, principal 이 아니라 인자로 받은 조회자 id 를 쓰는 차트·대시보드 경로와 맞는다.
 *
 * <p><b>Tibero.</b> 화면 쿼리 게이트는 Tibero 테넌트에 허가를 걸지 않아 기다림이 없다({@link
 * InteractiveQueryConcurrencyGate#acquireUnlessTibero}) — 앞 판정과 실행 사이에 낡을 틈이 없어 앞 판정을 그대로 쓴다.
 */
@Component
@RequiredArgsConstructor
public class PermitWaitRejudger {

  private final PipelineDataSchema pipelineDataSchema;
  private final ClearanceResolver clearanceResolver;
  private final GuardedSqlExecutor guardedSqlExecutor;

  /** 판정에 쓴 자격과 그 판정 토큰 — 대시보드는 새 자격을 뒤 위젯 판정에 이어 쓴다. */
  public record Judged(Clearance clearance, GuardedSqlExecutor.AnalyticsJudgment judgment) {}

  /** 허가를 얻은 직후(허가 구간 안) 재판정 — Tibero 는 기다림이 없어 앞 판정({@code prior})을 그대로 돌려준다. */
  public Judged afterPermit(long userId, String sqlText, Judged prior) {
    return pipelineDataSchema.isTibero() ? prior : fresh(userId, sqlText);
  }

  /** 자격을 DB 에서 새로 읽어 판정한다 — 남의 동시 로드를 기다려 받은 결과처럼 저장소와 무관하게 기다림이 있었을 때 쓴다. */
  public Judged fresh(long userId, String sqlText) {
    Clearance clearance = clearanceResolver.resolve(userId);
    return new Judged(clearance, guardedSqlExecutor.judgeAnalytics(clearance, sqlText));
  }
}
