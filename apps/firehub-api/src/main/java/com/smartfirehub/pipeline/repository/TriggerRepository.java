package com.smartfirehub.pipeline.repository;

import com.smartfirehub.pipeline.dto.CreateTriggerRequest;
import com.smartfirehub.pipeline.dto.PipelineDeleteImpactResponse;
import com.smartfirehub.pipeline.dto.TriggerResponse;
import com.smartfirehub.pipeline.dto.UpdateTriggerRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 파이프라인 트리거(pipeline_trigger) 정의 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqTriggerRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface TriggerRepository {

  List<TriggerResponse> findByPipelineId(Long pipelineId);

  Optional<TriggerResponse> findById(Long id);

  List<TriggerResponse> findEnabledByType(String triggerType);

  List<TriggerResponse> findEnabledChainTriggersByUpstreamId(Long upstreamPipelineId);

  /**
   * 활성 PIPELINE_CHAIN 연쇄 간선 한 줄 — 트리거 id, 그 트리거가 실행하는(하류) 파이프라인 id, config 의 상류 id 원문.
   *
   * <p>상류 id 는 원문(text)으로 돌려준다. SQL 에서 {@code ::bigint} 로 바꾸면 숫자로 읽을 수 없는 값 하나가 조회 전체를 실패시키므로, 해석은
   * 저장 시 검사와 같은 규칙으로 서비스가 한다.
   */
  record ChainEdgeRow(long triggerId, long pipelineId, String upstreamPipelineId) {}

  /**
   * 이 테넌트의 연쇄 그래프 변경(새 활성 연쇄 간선을 더하는 생성·수정·켜기)을 직렬화하는 트랜잭션 단위 advisory 잠금(WD-239). 커밋·롤백 때 풀린다.
   *
   * <p>왜: 순환 검사({@link #findEnabledChainEdges} 로 읽어 판정)와 저장이 잠금 없이 분리돼 있으면, F←G 와 G←F 를 동시에 저장하는 두
   * 트랜잭션이 서로의 아직 커밋되지 않은 간선을 못 보고 둘 다 통과해 순환이 저장된다. 검사 직전에 이 잠금을 잡으면 둘째 트랜잭션은 첫째가 커밋할 때까지 기다리고, 그
   * 뒤의 간선 조회는 READ COMMITTED 의 문장 단위 스냅샷이라 첫째의 간선을 본다.
   *
   * <p>{@code MANDATORY}: 호출자의 저장 트랜잭션에 합류해야만 의미가 있다 — 트랜잭션 없이 부르면 문장이 끝나자마자 풀려 아무것도 막지 못하므로 그 실수를
   * 예외로 드러낸다. 키는 접두어를 붙인 문자열 해시다 — 다른 테넌트 단위 잠금(member-admin-guard:, security_level_defs_t 등)과 키
   * 공간을 나눈다.
   */
  void lockChainGraph(long tenantId);

  /**
   * 현재 테넌트(RLS)의 활성 PIPELINE_CHAIN 간선을 한 번에 읽는다(WD-106). 저장 시 순환·길이 검사가 노드마다 조회하지 않고(N+1) 메모리 그래프로
   * 판정하기 위한 것이다.
   */
  List<ChainEdgeRow> findEnabledChainEdges();

  Long create(Long pipelineId, CreateTriggerRequest req, Map<String, Object> config, Long userId);

  void update(Long id, UpdateTriggerRequest req, Long userId);

  void delete(Long id);

  void updateEnabled(Long id, boolean enabled);

  void updateTriggerState(Long id, Map<String, Object> state);

  /**
   * {@code trigger_state.nextFireTime} 을 읽기-수정-쓰기로 병합한다(#676).
   *
   * <p>{@code REQUIRES_NEW} 인 이유: 이 메서드는 {@code TriggerService.createTrigger}/{@code updateTrigger}
   * 의 {@code TransactionSynchronization.afterCommit()} 콜백(= {@code
   * TriggerSchedulerService.registerSchedule}) 안에서 호출된다. afterCommit 시점에는 방금 커밋된 트랜잭션의 리소스(커넥션)가 아직
   * 스레드에 바인딩된 채라, 클래스 레벨 기본값인 {@code REQUIRED} 로는 이미 완료된 그 트랜잭션에 "참여"하게 되어 이 update 가 예외 없이 조용히
   * 반영되지 않는다(#676 구현 중 실측: 로그·리턴값 모두 정상인데 DB에는 반영 안 됨). {@code REQUIRES_NEW} 로 강제로 새 물리 트랜잭션·커넥션을
   * 열어야 실제로 커밋된다.
   */
  void mergeNextFireTime(Long id, String nextFireTimeIso);

  /**
   * {@code trigger_state.nextFireTime} 을 지운다(WD-107).
   *
   * <p>스케줄을 걸 수 없거나(다음 발화가 없는 cron, 커밋 후 등록 실패) 스케줄이 없는 상태로 남을 때, 화면의 "다음 실행"에 옛 시각이 거짓으로 남지 않게 하기
   * 위함이다. {@link #mergeNextFireTime} 과 같은 이유로 afterCommit 콜백 안에서 불리므로 {@code REQUIRES_NEW} 로 연다.
   */
  void clearNextFireTime(Long id);

  /**
   * {@link #mergeNextFireTime} 의 같은 트랜잭션 참여판(WD-554). 호출자의 트랜잭션(MANDATORY)에 실려 커넥션을 새로 빌리지 않는다 — 커밋
   * 전에 쓰므로 {@code REQUIRES_NEW} 가 필요 없고, 요청당 커넥션 1개 원칙을 지킨다. afterCommit 콜백 안에서 부르면 이미 끝난 트랜잭션에 걸려
   * 예외가 난다 — 그 경우는 {@link #mergeNextFireTime} 을 쓴다.
   */
  void mergeNextFireTimeInTransaction(Long id, String nextFireTimeIso);

  /**
   * {@link #clearNextFireTime} 의 같은 트랜잭션 참여판(WD-554). 사용 규칙은 {@link
   * #mergeNextFireTimeInTransaction} 과 같다.
   */
  void clearNextFireTimeInTransaction(Long id);

  /**
   * 이 파이프라인을 선행으로 둔, 다른 파이프라인의 켜진 연쇄 트리거를 하류 파이프라인 이름과 함께 읽는다(WD-419 — 삭제 확인 창 안내용).
   *
   * <p>상류 매칭은 삭제 시 끄는 {@link #disableByUpstreamPipelineId} 와 같은 규칙(config 의 upstreamPipelineId)을
   * 쓴다. 이미 꺼진 트리거는 삭제로 「꺼지는」 것이 아니므로 세지 않고, 지워질 파이프라인 자신의 트리거는 함께 지워지므로 뺀다. 테넌트 범위는 RLS 가 맡는다.
   */
  List<PipelineDeleteImpactResponse.DownstreamChainTrigger> findEnabledDownstreamChainTriggers(
      Long upstreamPipelineId);

  int disableByUpstreamPipelineId(Long upstreamPipelineId);

  /**
   * 데이터 변경(DATASET_CHANGE) 트리거들의 감시 목록에서 데이터셋 하나를 뺀다(WD-221).
   *
   * <p>데이터셋이 삭제될 때와, 폴러가 이미 없는 감시 대상을 발견했을 때 부른다. 감시 목록({@code config.datasetIds})과 폴러의 행 수
   * 스냅숏({@code trigger_state.lastSnapshot.<id>}, 변경 카운터·테이블 실체 스냅숏 {@code
   * lastChangeSnapshot.<id>}·{@code lastRelationSnapshot.<id>} — WD-399)에서 함께 지운다 — 남겨 두면 폴러가 매 주기
   * 「행 수 0 ≠ 직전값」으로 보고 같은 삭제 데이터셋으로 파이프라인을 계속 실행했고, 편집 창에는 지워진 id 가 「열람 권한 없음」 칩으로 남아 권한 문제처럼 보였다.
   * 지운 뒤 감시 대상이 하나도 남지 않으면 같은 문장에서 트리거를 끈다(꺼진 이유는 조회 응답의 {@code watchedDatasetsMissing} 이 「감시 목록이
   * 비었음」으로 계산한다).
   *
   * <p>읽고-고쳐-쓰기 대신 SQL 한 문장으로 바꾸는 이유: 같은 시각에 폴러가 상태를 쓰거나 다른 감시 대상이 지워져도 서로의 변경을 덮지 않게 하기 위함이다.
   * {@code jsonb_agg} 는 남는 원소가 없으면 NULL 이라 빈 배열로 감싼다. UPDATE 의 SET 식은 모두 바뀌기 전 행 값을 읽는다.
   *
   * @return 바뀐 트리거 수
   */
  int removeWatchedDataset(Long datasetId);

  /** 파이프라인 스텝들이 쓰는 출력 데이터셋 id(WD-399). 데이터 변경 폴러가 자기 출력을 감시하는 트리거의 자기 발화를 막는 데 쓴다. */
  java.util.Set<Long> findOutputDatasetIds(Long pipelineId);

  /**
   * 폴러의 행 수·변경 카운터 스냅숏을 지운다(WD-221, WD-399) — 꺼져 있던 데이터 변경 트리거를 다시 켤 때 부른다.
   *
   * <p>꺼진 동안의 변경으로 켜자마자 발화하지 않게, 켠 뒤 첫 폴링이 기준선만 새로 잡도록 한다. {@code lastCheckedAt} 도 지워 그 기준선을 폴링 주기를
   * 기다리지 않고 다음 폴링에 바로 잡는다.
   */
  void clearPollSnapshot(Long id);

  /**
   * 토큰 해시로 API 트리거를 찾는다. 켜짐 여부와 무관하게 찾는다(WD-152) — 맞는 토큰으로 꺼진 트리거를 부른 호출자에게 "토큰이 틀렸다"(401) 대신 "트리거가
   * 꺼져 있다"(409)를 알려 주기 위해서다. 켜짐 판정은 {@code TriggerService#fireTrigger} 한 곳에서만 한다.
   */
  Optional<TriggerResponse> findByTokenHash(String tokenHash);

  /** Find webhook trigger by webhookId. */
  Optional<TriggerResponse> findByWebhookId(String webhookId);

  /** Check if there is a running execution for the given pipeline. */
  boolean hasRunningExecution(Long pipelineId);

  /** Check if pipeline is active. */
  boolean isPipelineActive(Long pipelineId);
}
