package com.smartfirehub.analytics.repository.mybatis;

import static com.smartfirehub.global.platformdb.mybatis.PlatformText.searchPattern;
import static com.smartfirehub.global.validation.TextRules.textOrNull;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.analytics.repository.mybatis.AnalyticsRows.SharingCounts;
import com.smartfirehub.analytics.repository.mybatis.SavedQueryRows.SavedQueryInsert;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.naming.NameComparison;
import com.smartfirehub.global.naming.ResourceNameGuard;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.MyBatisPlatformConfig;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import com.smartfirehub.global.platformdb.mybatis.PlatformSharing;
import com.smartfirehub.global.validation.TextRules;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.ibatis.session.SqlSessionFactory;
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

  /**
   * postgresql databaseId 면 SQL 이름 비교, 그 밖(Tibero)은 이름 목록 + Java 비교(WD-1181). SqlSessionFactory 의
   * databaseId 에서 한 번 정한다.
   */
  private final boolean postgres;

  public MyBatisSavedQueryRepository(SavedQueryMapper mapper, SqlSessionFactory sqlSessionFactory) {
    this.mapper = mapper;
    this.postgres =
        MyBatisPlatformConfig.POSTGRESQL.equals(
            sqlSessionFactory.getConfiguration().getDatabaseId());
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
        textOrNull(folder),
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
        textOrNull(folder));
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
            // 설명도 폴더·update 와 같은 판정으로 공백·Cf 만이면 NULL 로 저장한다 (WD-1144)
            textOrNull(req.description()),
            req.sqlText(),
            req.datasetId(),
            // WD-889: 빈 폴더("")는 NULL 로 저장 — 폴더 목록에 빈 항목이 생겨 화면이 깨지던 문제 방지
            textOrNull(req.folder()),
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
   * 넘긴 필드만 고친다 — null 은 「변경 없음」, 설명·폴더의 빈 문자열(공백·NBSP·전각 공백·Cf 만 포함)은 「비움」(NULL, WD-197·WD-1103).
   * 수정 시각은 jOOQ 판과 같은 자바 시각을 바인딩한다.
   */
  @Override
  public void update(Long id, UpdateSavedQueryRequest req, Long userId) {
    mapper.update(
        id,
        req.name(),
        req.description() != null,
        textOrNull(req.description()),
        req.sqlText(),
        req.datasetId(),
        req.folder() != null,
        textOrNull(req.folder()),
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

  /**
   * 같은 소유자의 같은 이름 존재 여부(WD-824) — PG 는 유니코드 공백 변환 뒤 SQL 로 비교하고(WD-1094), 그 밖(Tibero)은 이름 목록을 읽어 PG
   * 와 같은 UNICODE_TRIM 비교값으로 Java 에서 비교한다(WD-1181).
   */
  @Override
  public boolean existsByOwnerAndName(long ownerId, String name, Long excludeId) {
    if (postgres) {
      return mapper.countByOwnerAndName(
              ownerId, name, excludeId, TextRules.EDGE_SPACE_CHARS, TextRules.EDGE_SPACE_AS_ASCII)
          > 0;
    }
    // 비 PG 는 소유자의 이름을 전부 읽는다(O(n)) — 소유자별 개수가 적어 감수한다(WD-1181).
    // name 이 null 이면 서비스가 먼저 검증하므로 조회 없이 false
    return name != null
        && ResourceNameGuard.containsName(mapper.selectNamesByOwner(ownerId, excludeId), name);
  }

  /**
   * 개명 판정 비교 기준(WD-1094) — PG SQL 도, 비 PG 의 Java 비교도 UNICODE_TRIM 이라 databaseId 와 무관하게
   * 하나다(WD-1181).
   */
  @Override
  public NameComparison nameComparison() {
    return NameComparison.UNICODE_TRIM;
  }
}
