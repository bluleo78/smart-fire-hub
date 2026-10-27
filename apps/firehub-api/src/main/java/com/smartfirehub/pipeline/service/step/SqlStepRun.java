package com.smartfirehub.pipeline.service.step;

import com.smartfirehub.pipeline.service.executor.DbExecutorClient;
import java.util.List;

/**
 * SQL 단계 실행 입력(WD-231) — 러너가 검증·보안 게이트·출력 결정·행 트리거 보장을 끝낸 뒤 만든다. Tibero 방언은 이것으로 실행 본체 전체를
 * 돌리고({@link TiberoStepDialect#executeSql}), PG 방언은 래핑 SQL 조립에만 쓴다({@link
 * PgStepDialect#wrapSelect}) — PG 싱크 호출은 보안 불변식 때문에 러너 {@code executeStep} 에 남는다.
 *
 * @param sql 검증·게이트를 통과한 사용자 SQL(참조·증분 플레이스홀더 치환 후 — 판정한 바로 그 문자열)
 * @param preStatements 본 문장과 같은 트랜잭션으로 보낼 선행문(REPLACE 비우기·증분 전체 재구축)
 * @param isSelect 검증기가 판정한 조회문인지(아니면 사용자 DML)
 * @param outputTableName 출력 테이블 메타데이터 이름(없으면 null)
 * @param outputDatasetId 출력 데이터셋 id(없으면 null)
 * @param isMerge MERGE 적재인지
 * @param tempDatasetAutoCreated 출력이 러너 자동 생성 TEMP 인지 — 그렇다면 TEMP 컬럼이 예약어 별칭({@code id} → {@code
 *     id_1})으로 만들어졌으므로 적재 대상 이름에도 같은 별칭을 쓴다(#645)
 * @param probeReuse Tibero TEMP 생성 때 이미 탐지한 결과(없으면 null — 실행 본체가 탐지한다). PG 는 늘 null
 */
public record SqlStepRun(
    String sql,
    List<String> preStatements,
    boolean isSelect,
    String outputTableName,
    Long outputDatasetId,
    boolean isMerge,
    boolean tempDatasetAutoCreated,
    DbExecutorClient.ProbeResult probeReuse) {}
