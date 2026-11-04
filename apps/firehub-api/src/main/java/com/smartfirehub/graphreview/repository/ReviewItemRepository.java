package com.smartfirehub.graphreview.repository;

import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;
import java.util.Optional;

/**
 * 그래프 검토 대기 항목(graph_review_item 테이블) 저장·조회 리포지토리. 데이터셋 이름은 dataset 과 조인해 읽는다.
 *
 * <p>구현은 {@code JooqReviewItemRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface ReviewItemRepository {

  /**
   * pending 항목을 upsert 등록한다. 같은 데이터셋에 (item_type, dedupe_key)가 이미 있으면 무시한다(재적재 중복 방지).
   *
   * <p>중복 판정이 데이터셋 단위인 이유(V134): 테넌트 단위였을 때는 볼 수 없는 데이터셋의 같은 키가 이 등록을 흡수해, 등록한 항목이 어느 목록에도 나타나지 않는
   * 것으로 숨김 데이터셋에 그 이름이 있다는 사실이 드러났다(존재 오라클).
   */
  void upsertPending(
      String itemType,
      String dedupeKey,
      Long datasetId,
      String signalType,
      Double signalScore,
      String reason,
      String payloadJson);

  /**
   * (item_type, dedupe_key)의 기존 결정 상태. 없으면 empty(ingest는 이를 LLM 재호출 신호로 해석).
   *
   * <p>보안 등급(후속 F2): 출처 데이터셋을 볼 수 없는 항목은 <b>없는 항목과 똑같이</b> empty 로 돌려준다 — 이름만 넣어 상태를 물을 수 있는 조회라,
   * 거르지 않으면 숨김 데이터셋에 그 이름이 있는지·어떻게 판정됐는지가 드러나는 존재 오라클이 된다.
   *
   * <p>V134 이후 같은 키가 데이터셋마다 한 건씩 있을 수 있다. 조회 요청에는 데이터셋이 없으므로(ai-agent 계약 유지) 볼 수 있는 행들 중 하나를 고른다 —
   * 사람이 내린 결정(approved/rejected)을 pending 보다 우선하고, 결정끼리는 가장 최근 결정을 따른다. 테넌트 단위 시절 "한 번 내린 결정을 이후
   * ingest 가 재사용한다"는 의미를 볼 수 있는 범위 안에서 유지하기 위함이다.
   */
  Optional<String> findDecisionStatus(
      String itemType, String dedupeKey, DatasetVisibility visibility);

  /**
   * 검수 항목 목록 — status로 필터하고, itemType이 주어지면 해당 타입만 추린다(null이면 전체 타입). 등록순.
   *
   * <p>status를 하드코딩했던 예전 구현은 컨트롤러가 받은 status 파라미터를 조용히 무시해, approved/rejected를 요청해도 pending 행을 돌려주는
   * 조용한 오답이었다(#318). 허용값 검증은 서비스가 담당한다.
   *
   * <p>limit이 null이면(호출자가 페이지 파라미터를 안 준 경우) 기존과 동일하게 전체를 반환한다 — opt-in 페이지네이션(#422). offset은 limit이
   * 있을 때만 의미가 있다(null이면 0으로 취급). CREATED_AT은 대량 인제스트마다 동시에 여러 행이 꽂혀 유일하지 않다 — ID를 2차 정렬키로 넣지 않으면
   * 페이지 경계에서 동시각 행이 누락되거나 중복될 수 있다.
   */
  List<ReviewItemRecord> findByStatus(
      String status, String itemType, Integer offset, Integer limit, DatasetVisibility visibility);

  Optional<ReviewItemRecord> findById(long id);

  /** 승인/거부 처리 — status·decided_by·decided_at 갱신. */
  void updateStatus(long id, String status, long decidedBy);

  /**
   * 검수 결정(승인·거부) 동안 항목 행을 잠근다(SELECT … FOR UPDATE, WD-428). 호출하는 쪽 트랜잭션이 끝날 때까지 같은 항목의 다른 결정은 이 줄에서
   * 기다렸다가, 앞 결정이 커밋된 뒤 상태를 다시 읽어 409 로 막힌다. 예전에는 pending 확인과 상태 갱신 사이에 잠금이 없어, 승인이 그래프를 바꾸는 수백 ms
   * 사이에 들어온 거부도 pending 으로 보고 「거부했습니다」를 돌려준 뒤 승인이 approved 로 덮어썼다.
   */
  void lockForDecision(long id);
}
