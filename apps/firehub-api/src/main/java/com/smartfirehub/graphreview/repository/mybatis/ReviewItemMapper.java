package com.smartfirehub.graphreview.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * graph_review_item MyBatis 매퍼(WD-487 PoC). SQL 은 {@code mybatis/graphreview/ReviewItemMapper.xml}.
 *
 * <p>리포지토리 계약({@code ReviewItemRepository})의 의미 처리(offset 기본값·0행 예외 등)는 {@link
 * MyBatisReviewItemRepository} 가 맡고, 이 인터페이스는 문장 1:1 바인딩만 한다. 가시성 파라미터는 공유 조각 규약대로 {@code vis} 이름으로
 * 받는다.
 */
@PlatformMapper
public interface ReviewItemMapper {

  /** pending 등록 — 같은 (tenant, item_type, dataset, dedupe_key) 가 있으면 아무것도 하지 않는다. */
  int insertPendingIgnoreDuplicate(
      @Param("itemType") String itemType,
      @Param("dedupeKey") String dedupeKey,
      @Param("datasetId") Long datasetId,
      @Param("signalType") String signalType,
      @Param("signalScore") Double signalScore,
      @Param("reason") String reason,
      @Param("payloadJson") String payloadJson);

  /** 볼 수 있는 행 중 결정 상태 하나(결정 우선·최근 결정 우선). 없으면 null. */
  String findDecisionStatus(
      @Param("itemType") String itemType,
      @Param("dedupeKey") String dedupeKey,
      @Param("vis") VisibilityParams vis);

  /** status(+itemType) 목록, 등록순. limit 이 null 이면 전체. */
  List<ReviewItemRecord> findByStatus(
      @Param("status") String status,
      @Param("itemType") String itemType,
      @Param("offset") int offset,
      @Param("limit") Integer limit,
      @Param("vis") VisibilityParams vis);

  /** id 단건. 없으면 null. */
  ReviewItemRecord findById(@Param("id") long id);

  /** pending 일 때만 결정을 기록한다. 바뀐 행 수를 돌려준다. */
  int updateStatusIfPending(
      @Param("id") long id,
      @Param("status") String status,
      @Param("decidedBy") long decidedBy,
      @Param("decidedAt") LocalDateTime decidedAt);

  /** 행 잠금(SELECT … FOR UPDATE). 잠긴 id 목록(0 또는 1건). */
  List<Long> lockForDecision(@Param("id") long id);
}
