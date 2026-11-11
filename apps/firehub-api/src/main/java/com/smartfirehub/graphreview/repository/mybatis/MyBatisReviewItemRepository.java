package com.smartfirehub.graphreview.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.graphreview.dto.ReviewItemRecord;
import com.smartfirehub.graphreview.repository.ReviewItemRepository;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * ReviewItemRepository 의 MyBatis 구현(WD-487 PoC) — {@code JooqReviewItemRepository} 와 같은 의미.
 *
 * <p>SQL 은 {@code mybatis/graphreview/ReviewItemMapper.xml} 에 있고, 이 클래스는 계약 의미(offset 기본값·0행
 * 예외·Optional 변환·가시성 값 펼치기)만 맡는다. 같은 인터페이스 계약 테스트를 PG·Tibero 양쪽에서 돌리기 위한 구현이다.
 * {@code @PlatformMyBatisRepository} 로 등록기가 빈으로 등록하며, Tibero 설치본에서만 주입 후보다(그 밖에는 서비스가 jOOQ 구현을
 * 받는다).
 */
// RLS GUC 는 트랜잭션 시작 시점에만 주입되는데 호출자 ReviewItemService(evidence 제외)와 MCP 추출
// 툴은 트랜잭션 경계를 만들지 않는다. 여기서 열지 않으면 세 갈래로 깨진다 — insertPending*: tenant_id
// NOT NULL 위반으로 추출 중단, lookup*: 0행이라 승인 항목이 중복 재생성, updateStatus: 0행인데
// 검수자에게는 성공으로 보고. 지우지 말 것.
@PlatformMyBatisRepository
@Transactional
public class MyBatisReviewItemRepository implements ReviewItemRepository {

  private final ReviewItemMapper mapper;

  /**
   * @param mapper graph_review_item 매퍼
   */
  public MyBatisReviewItemRepository(ReviewItemMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void upsertPending(
      String itemType,
      String dedupeKey,
      Long datasetId,
      String signalType,
      Double signalScore,
      String reason,
      String payloadJson) {
    // tenant_id 는 컬럼 DEFAULT(GUC)가 채운다 — ON CONFLICT 대상은 V134 인덱스와 같다(XML 참고).
    mapper.insertPendingIgnoreDuplicate(
        itemType, dedupeKey, datasetId, signalType, signalScore, reason, payloadJson);
  }

  @Override
  public Optional<String> findDecisionStatus(
      String itemType, String dedupeKey, DatasetVisibility visibility) {
    return Optional.ofNullable(
        mapper.findDecisionStatus(
            itemType, dedupeKey, MyBatisDatasetVisibility.params(visibility)));
  }

  @Override
  public List<ReviewItemRecord> findByStatus(
      String status, String itemType, Integer offset, Integer limit, DatasetVisibility visibility) {
    // offset 은 limit 이 있을 때만 의미가 있고 null 이면 0 — jOOQ 구현과 같다. 가시성은 SQL 에서 걸러 페이지 경계를 맞춘다.
    return mapper.findByStatus(
        status,
        itemType,
        offset == null ? 0 : offset,
        limit,
        MyBatisDatasetVisibility.params(visibility));
  }

  @Override
  public Optional<ReviewItemRecord> findById(long id) {
    return Optional.ofNullable(mapper.findById(id));
  }

  @Override
  public void updateStatus(long id, String status, long decidedBy) {
    // pending 일 때만 바꾼다 — 조건 없이 쓰면 동시에 들어온 승인·거부가 서로의 결정을 덮어썼다(WD-428).
    // 정상 경로는 lockForDecision 으로 이미 직렬화돼 1행이다. 0행이면 다른 요청이 먼저 처리한 것이다.
    int updated = mapper.updateStatusIfPending(id, status, decidedBy, LocalDateTime.now());
    if (updated == 0) {
      throw new IllegalStateException("이미 다른 사용자가 처리한 항목입니다: " + id);
    }
  }

  @Override
  public void lockForDecision(long id) {
    mapper.lockForDecision(id);
  }
}
