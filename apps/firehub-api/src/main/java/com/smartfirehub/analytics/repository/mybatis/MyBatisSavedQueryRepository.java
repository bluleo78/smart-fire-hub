package com.smartfirehub.analytics.repository.mybatis;

import static com.smartfirehub.global.platformdb.mybatis.PlatformText.blankToNull;
import static com.smartfirehub.global.platformdb.mybatis.PlatformText.searchPattern;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.SharingCounts;
import com.smartfirehub.analytics.repository.mybatis.SavedQueryRows.SavedQueryInsert;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformSharing;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * SavedQueryRepository 의 MyBatis 구현(WD-710) — {@code JooqSavedQueryRepository} 와 같은 의미.
 *
 * <p>트랜잭션 속성은 jOOQ 구현 그대로 클래스 단위 트랜잭션이 없다(서비스 트랜잭션 안에서 불린다). 예외는 {@link #shareQueries} — jOOQ 의 한
 * 문장 {@code UPDATE … RETURNING} 을 「잠금 조회 + UPDATE」 두 문장으로 옮겼으므로 둘이 한 트랜잭션이어야 같은 원자성이 된다(호출자 트랜잭션이
 * 있으면 그대로 합류).
 *
 * <p>공유 가시성(소유 또는 공유)은 XML 의 {@code DashboardVisibility.ownedOrShared} 조각 하나, 목록·건수·폴더의 범위는 XML 의
 * {@code listScope} 조각 하나로 맞춘다(WD-328).
 */
@PlatformMyBatisRepository
public class MyBatisSavedQueryRepository implements SavedQueryRepository {

  private final SavedQueryMapper mapper;

  public MyBatisSavedQueryRepository(SavedQueryMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<SavedQueryListResponse> findAll(
      String search,
      String folder,
      Boolean sharedOnly,
      Boolean mineOnly,
      Long userId,
      int page,
      int size,
      DatasetVisibility nameVisibility) {
    // jOOQ 판 offset(page * size)과 같은 값 — int 곱 오버플로를 피하려 long 으로 곱한다.
    return mapper.selectPage(
        Boolean.TRUE.equals(sharedOnly),
        Boolean.TRUE.equals(mineOnly),
        userId,
        searchPattern(search),
        blankToNull(folder),
        MyBatisDatasetVisibility.params(nameVisibility),
        size,
        (long) page * size);
  }

  @Override
  public long countAll(
      String search, String folder, Boolean sharedOnly, Boolean mineOnly, Long userId) {
    return mapper.count(
        Boolean.TRUE.equals(sharedOnly),
        Boolean.TRUE.equals(mineOnly),
        userId,
        searchPattern(search),
        blankToNull(folder));
  }

  @Override
  public Optional<SavedQueryResponse> findById(
      Long id, Long userId, DatasetVisibility nameVisibility) {
    return Optional.ofNullable(
        mapper.selectDetail(id, userId, false, MyBatisDatasetVisibility.params(nameVisibility)));
  }

  @Override
  public Optional<SavedQueryResponse> findByIdForOwner(
      Long id, Long userId, DatasetVisibility nameVisibility) {
    return Optional.ofNullable(
        mapper.selectDetail(id, userId, true, MyBatisDatasetVisibility.params(nameVisibility)));
  }

  @Override
  public Optional<RawSavedQuery> findRawByIdUnrestricted(Long id) {
    return Optional.ofNullable(mapper.selectRaw(id));
  }

  /**
   * 내가 만든 비공유 쿼리만 공유로 바꾸고 바뀐 것의 id → 이름을 돌려준다(WD-464 감사용, Review Focus 4). 대상을 먼저 FOR UPDATE 로 잠가
   * 읽고 그 id 만 바꾸므로, 이미 공유된 것·남의 것·다른 테넌트 것은 돌려주는 맵에도 UPDATE 에도 들지 않는다.
   */
  @Override
  @Transactional
  public Map<Long, String> shareQueries(List<Long> queryIds, Long ownerId) {
    // 잠근 수와 바꾼 수가 다르면 헬퍼가 예외로 알린다(Task 7 리뷰 M2).
    return PlatformSharing.flip(
        queryIds,
        chunks -> mapper.selectShareTargetsForUpdate(chunks, ownerId),
        mapper::markShared,
        "저장 쿼리");
  }

  @Override
  public int[] countOwnedBySharing(List<Long> queryIds, Long ownerId) {
    if (queryIds.isEmpty()) return new int[] {0, 0};
    SharingCounts c = mapper.countOwnedBySharing(InChunks.of(queryIds), ownerId);
    return new int[] {(int) c.privateCount(), (int) c.sharedCount()};
  }

  @Override
  public Long insert(CreateSavedQueryRequest req, Long userId) {
    SavedQueryInsert row =
        new SavedQueryInsert(
            req.name(),
            req.description(),
            req.sqlText(),
            req.datasetId(),
            req.folder(),
            req.isShared(),
            userId);
    mapper.insert(row);
    return row.getId();
  }

  @Override
  public Optional<LockedRowVersion> lockUpdatedAtById(Long id) {
    return Optional.ofNullable(mapper.lockVersionById(id))
        .map(row -> new LockedRowVersion(row.updatedAt()));
  }

  /**
   * 넘긴 필드만 고친다 — null 은 「변경 없음」, 설명·폴더의 빈 문자열(공백만 포함)은 「비움」(NULL, WD-197). 수정 시각은 jOOQ 판과 같은 자바 시각을
   * 바인딩한다.
   */
  @Override
  public void update(Long id, UpdateSavedQueryRequest req, Long userId) {
    mapper.update(
        id,
        req.name(),
        req.description() != null,
        blankToNull(req.description()),
        req.sqlText(),
        req.datasetId(),
        req.folder() != null,
        blankToNull(req.folder()),
        req.isShared(),
        userId,
        LocalDateTime.now());
  }

  @Override
  public boolean deleteById(Long id, Long userId) {
    return mapper.deleteByIdAndOwner(id, userId) > 0;
  }

  @Override
  public List<String> findDistinctFolders(Boolean sharedOnly, Boolean mineOnly, Long userId) {
    return mapper.selectDistinctFolders(
        Boolean.TRUE.equals(sharedOnly), Boolean.TRUE.equals(mineOnly), userId);
  }

  /**
   * 주인이 아닌 사용자가 만든 차트 수. ownerId 가 null 이면 0 — jOOQ 3.19 의 {@code ne(null)} 은 {@code created_by <>
   * NULL} 을 그려 비교가 UNKNOWN 이 되므로 한 행도 세지 않는다. 그 의미를 그대로 따른다(WD-710 C1).
   */
  @Override
  public long countOtherUserCharts(Long savedQueryId, Long ownerId) {
    if (ownerId == null) return 0;
    return mapper.countOtherUserCharts(savedQueryId, ownerId);
  }
}
