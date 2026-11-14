package com.smartfirehub.analytics.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.repository.SavedQueryRepository.RawSavedQuery;
import com.smartfirehub.global.dto.LockedRowVersion;
import com.smartfirehub.global.platformdb.mybatis.LongTextAssertions;
import com.smartfirehub.securitylevel.access.Clearance;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import com.smartfirehub.user.repository.TenantPairContractBase;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SavedQueryRepository 계약 테스트 — 구현마다 하위 클래스로 실행한다(WD-710).
 *
 * <p>saved_query 는 테넌트 정책(RLS) 아래 있어 호출은 늘 테넌트 컨텍스트로 감싼다. 공유 가시성(Review Focus 4): 남의 비공유 쿼리는 목록·건수·
 * 단건·폴더에 안 나오고 공유된 것은 나온다, {@code shareQueries} 는 이번에 실제로 공유로 바뀐 내 쿼리만 돌려준다. 연결 데이터셋 이름은 조회자가 볼 수
 * 없는 등급이면 null 로 가리고 id 는 남긴다. 빈 문자열은 DB 가 다르게 저장하므로({@code emptyStringIsNull}) 기대값을 훅으로 가른다.
 */
public abstract class SavedQueryRepositoryContractTest extends TenantPairContractBase {

  protected abstract SavedQueryRepository repository();

  /** 조회자 겸 주인. */
  private long me;

  /** 다른 사용자(같은 테넌트). */
  private long other;

  @BeforeEach
  void createUsers() {
    me = fx.createUser(prefix + "-me", null);
    fx.setUserName(me, prefix + "-me-name");
    other = fx.createUser(prefix + "-other", null);
    fx.setUserName(other, prefix + "-other-name");
  }

  private DatasetVisibility rank(int rank) {
    return new DatasetVisibility(new Clearance(me, tenantA, rank, Set.of(), false, Set.of()), null);
  }

  private static final DatasetVisibility ALL = DatasetVisibility.UNRESTRICTED;

  private Long insertA(long userId, CreateSavedQueryRequest req) {
    return inA(() -> repository().insert(req, userId));
  }

  private static CreateSavedQueryRequest req(String name, String folder, boolean shared) {
    return new CreateSavedQueryRequest(name, null, "SELECT 1", null, folder, shared);
  }

  private List<Long> listIds(
      String search, String folder, Boolean sharedOnly, Boolean mineOnly, int page, int size) {
    return inA(
            () -> repository().findAll(search, folder, sharedOnly, mineOnly, me, page, size, ALL))
        .stream()
        .map(SavedQueryListResponse::id)
        .toList();
  }

  private long count(String search, String folder, Boolean sharedOnly, Boolean mineOnly) {
    return inA(() -> repository().countAll(search, folder, sharedOnly, mineOnly, me));
  }

  // ---- insert·단건 ----

  @Test
  @DisplayName(
      "insert·findById·findByIdForOwner·findRawByIdUnrestricted — 모든 필드가 왕복하고 만든이 이름·DB 시계 시각·차트 0 이 실린다")
  void insert_roundTripsAllFields() {
    long ds = fx.createDataset(tenantA, prefix + "-ds");
    String sql = "SELECT * FROM t WHERE v = '한글' -- " + "가".repeat(2000);
    Long id =
        insertA(me, new CreateSavedQueryRequest(prefix + "-q1", "설명 한글", sql, ds, "reports", true));

    SavedQueryResponse r = inA(() -> repository().findById(id, me, ALL)).orElseThrow();
    assertThat(r.id()).isEqualTo(id);
    assertThat(r.name()).isEqualTo(prefix + "-q1");
    assertThat(r.description()).isEqualTo("설명 한글");
    assertThat(r.sqlText()).isEqualTo(sql);
    assertThat(r.datasetId()).isEqualTo(ds);
    assertThat(r.datasetName()).isEqualTo(prefix + "-ds");
    assertThat(r.folder()).isEqualTo("reports");
    assertThat(r.isShared()).isTrue();
    assertThat(r.createdByName()).isEqualTo(prefix + "-me-name");
    assertThat(r.createdBy()).isEqualTo(me);
    assertThat(Duration.between(r.createdAt(), fx.dbWallClockNow()).abs())
        .isLessThan(Duration.ofMinutes(2));
    assertThat(r.updatedAt()).isNotNull();
    assertThat(r.chartCount()).isZero();

    assertThat(inA(() -> repository().findByIdForOwner(id, me, ALL))).contains(r);
    assertThat(inA(() -> repository().findRawByIdUnrestricted(id)))
        .contains(new RawSavedQuery(id, prefix + "-q1", "설명 한글", sql, ds, "reports", true, me));
  }

  @Test
  @DisplayName("insert — 설명·폴더·데이터셋이 null 이면 null, 빈 설명은 DB 규칙대로 저장된다")
  void insert_nullsAndEmptyDescription() {
    Long id = insertA(me, req(prefix + "-n", null, false));
    RawSavedQuery raw = inA(() -> repository().findRawByIdUnrestricted(id)).orElseThrow();
    assertThat(raw.description()).isNull();
    assertThat(raw.folder()).isNull();
    assertThat(raw.datasetId()).isNull();
    assertThat(raw.isShared()).isFalse();
    SavedQueryResponse r = inA(() -> repository().findById(id, me, ALL)).orElseThrow();
    assertThat(r.datasetName()).isNull();

    Long empty =
        insertA(me, new CreateSavedQueryRequest(prefix + "-e", "", "SELECT 1", null, null, false));
    String stored =
        inA(() -> repository().findRawByIdUnrestricted(empty)).orElseThrow().description();
    if (emptyStringIsNull()) {
      assertThat(stored).isNull();
    } else {
      assertThat(stored).isEmpty();
    }
  }

  @Test
  @DisplayName("findById — 주인과, 공유된 쿼리는 다른 사용자도 본다 / findByIdForOwner 는 주인만 / 다른 테넌트는 empty")
  void findById_visibility() {
    Long mine = insertA(me, req(prefix + "-mine", null, false));
    Long othersPrivate = insertA(other, req(prefix + "-op", null, false));
    Long othersShared = insertA(other, req(prefix + "-os", null, true));

    assertThat(inA(() -> repository().findById(mine, me, ALL))).isPresent();
    assertThat(inA(() -> repository().findById(othersPrivate, me, ALL)))
        .as("남의 비공유 쿼리는 안 보인다")
        .isEmpty();
    assertThat(inA(() -> repository().findById(othersShared, me, ALL))).isPresent();
    assertThat(inA(() -> repository().findById(-1L, me, ALL))).isEmpty();

    assertThat(inA(() -> repository().findByIdForOwner(mine, me, ALL))).isPresent();
    assertThat(inA(() -> repository().findByIdForOwner(othersShared, me, ALL)))
        .as("공유돼도 주인이 아니면 empty")
        .isEmpty();

    assertThat(inB(() -> repository().findById(mine, me, ALL))).isEmpty();
    assertThat(inB(() -> repository().findByIdForOwner(mine, me, ALL))).isEmpty();
    assertThat(inB(() -> repository().findRawByIdUnrestricted(mine))).isEmpty();
  }

  @Test
  @DisplayName("findRawByIdUnrestricted — 소유·공유와 무관하게 남의 비공유 쿼리도 읽는다, 없으면 empty")
  void findRaw_ignoresOwnership() {
    Long othersPrivate = insertA(other, req(prefix + "-op", "f1", false));

    RawSavedQuery raw =
        inA(() -> repository().findRawByIdUnrestricted(othersPrivate)).orElseThrow();
    assertThat(raw.createdBy()).isEqualTo(other);
    assertThat(raw.isShared()).isFalse();
    assertThat(raw.folder()).isEqualTo("f1");
    assertThat(inA(() -> repository().findRawByIdUnrestricted(-1L))).isEmpty();
  }

  @Test
  @DisplayName("데이터셋 이름 가림 — 볼 수 없는 등급이면 이름만 null, id 는 남는다(목록·단건·주인 단건)")
  void datasetName_maskedByVisibility() {
    long secretLevel = fx.createSecurityLevel(tenantA, 5, prefix + "-secret");
    long open = fx.createDataset(tenantA, prefix + "-open");
    long secret = fx.createDataset(tenantA, prefix + "-secret");
    fx.setDatasetField(secret, "security_level_id", secretLevel);
    Long qOpen =
        insertA(
            me, new CreateSavedQueryRequest(prefix + "-qo", null, "SELECT 1", open, null, false));
    Long qSecret =
        insertA(
            me, new CreateSavedQueryRequest(prefix + "-qs", null, "SELECT 1", secret, null, false));

    SavedQueryResponse low = inA(() -> repository().findById(qSecret, me, rank(2))).orElseThrow();
    assertThat(low.datasetId()).isEqualTo(secret);
    assertThat(low.datasetName()).as("안 보이는 등급의 이름은 가린다").isNull();
    assertThat(inA(() -> repository().findByIdForOwner(qSecret, me, rank(2))).orElseThrow())
        .extracting(SavedQueryResponse::datasetId, SavedQueryResponse::datasetName)
        .containsExactly(secret, null);
    assertThat(inA(() -> repository().findById(qOpen, me, rank(2))).orElseThrow().datasetName())
        .isEqualTo(prefix + "-open");
    assertThat(inA(() -> repository().findById(qSecret, me, rank(5))).orElseThrow().datasetName())
        .isEqualTo(prefix + "-secret");

    List<SavedQueryListResponse> list =
        inA(() -> repository().findAll(null, null, null, null, me, 0, 10, rank(2)));
    assertThat(list).hasSize(2);
    SavedQueryListResponse lSecret =
        list.stream().filter(q -> q.id().equals(qSecret)).findFirst().orElseThrow();
    assertThat(lSecret.datasetId()).isEqualTo(secret);
    assertThat(lSecret.datasetName()).isNull();
    SavedQueryListResponse lOpen =
        list.stream().filter(q -> q.id().equals(qOpen)).findFirst().orElseThrow();
    assertThat(lOpen.datasetName()).isEqualTo(prefix + "-open");

    DatasetVisibility none = new DatasetVisibility(Clearance.none(me, tenantA), null);
    assertThat(inA(() -> repository().findAll(null, null, null, null, me, 0, 10, none)))
        .extracting(SavedQueryListResponse::datasetName)
        .containsOnlyNulls();
    assertThat(inA(() -> repository().findAll(null, null, null, null, me, 0, 10, ALL)))
        .extracting(SavedQueryListResponse::datasetName)
        .containsExactlyInAnyOrder(prefix + "-open", prefix + "-secret");
  }

  @Test
  @DisplayName("차트 수 — 쿼리를 쓰는 차트 수가 목록·단건에 실린다(만든 이와 무관)")
  void chartCount_counted() {
    Long q = insertA(me, req(prefix + "-q", null, false));
    Long q0 = insertA(me, req(prefix + "-q0", null, false));
    fx.createChart(tenantA, me, q, false);
    fx.createChart(tenantA, other, q, true);

    assertThat(inA(() -> repository().findById(q, me, ALL)).orElseThrow().chartCount())
        .isEqualTo(2);
    assertThat(inA(() -> repository().findByIdForOwner(q, me, ALL)).orElseThrow().chartCount())
        .isEqualTo(2);
    List<SavedQueryListResponse> list =
        inA(() -> repository().findAll(null, null, null, null, me, 0, 10, ALL));
    assertThat(list)
        .extracting(SavedQueryListResponse::id, SavedQueryListResponse::chartCount)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(q, 2L),
            org.assertj.core.groups.Tuple.tuple(q0, 0L));
  }

  // ---- 목록·건수·폴더 범위(WD-328) ----

  @Test
  @DisplayName("findAll·countAll — 기본(내 것+공유)·mineOnly·sharedOnly·둘 다 범위, 남의 비공유와 다른 테넌트는 빠진다")
  void list_scopes() {
    Long myPrivate = insertA(me, req(prefix + "-a", null, false));
    Long myShared = insertA(me, req(prefix + "-b", null, true));
    insertA(other, req(prefix + "-c", null, false));
    Long othersShared = insertA(other, req(prefix + "-d", null, true));
    inB(() -> repository().insert(req(prefix + "-z", null, true), me));

    assertThat(listIds(null, null, null, null, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, null, null, null)).isEqualTo(3);
    assertThat(listIds(null, null, false, false, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared, othersShared);
    assertThat(count(null, null, false, false)).isEqualTo(3);

    assertThat(listIds(null, null, null, true, 0, 50))
        .containsExactlyInAnyOrder(myPrivate, myShared);
    assertThat(count(null, null, null, true)).isEqualTo(2);

    assertThat(listIds(null, null, true, null, 0, 50))
        .containsExactlyInAnyOrder(myShared, othersShared);
    assertThat(count(null, null, true, null)).isEqualTo(2);

    assertThat(listIds(null, null, true, true, 0, 50)).containsExactly(myShared);
    assertThat(count(null, null, true, true)).isEqualTo(1);
  }

  @Test
  @DisplayName("findAll·countAll — 검색은 이름 또는 설명의 대소문자 무시 부분 일치(%·_ 는 글자 그대로), 폴더는 정확히 일치")
  void list_searchAndFolder() {
    Long byName = insertA(me, req(prefix + "-Sales Report", "f1", false));
    Long byDesc =
        insertA(
            me,
            new CreateSavedQueryRequest(
                prefix + "-x", "월간 SALES 분석", "SELECT 1", null, "f2", false));
    Long literal = insertA(me, req(prefix + "-100%_done", "f1", false));
    insertA(me, req(prefix + "-1000xdone", "f1", false));
    insertA(me, req(prefix + "-other", null, false));

    assertThat(listIds("sales", null, null, null, 0, 50)).containsExactlyInAnyOrder(byName, byDesc);
    assertThat(count("sales", null, null, null)).isEqualTo(2);
    assertThat(listIds("0%_d", null, null, null, 0, 50)).containsExactly(literal);
    assertThat(count("0%_d", null, null, null)).isEqualTo(1);
    assertThat(listIds("  ", null, null, null, 0, 50)).hasSize(5);
    assertThat(listIds("sales", "f1", null, null, 0, 50)).containsExactly(byName);
    assertThat(count("sales", "f1", null, null)).isEqualTo(1);
    assertThat(listIds(null, "f1", null, null, 0, 50)).hasSize(3);
    assertThat(count(null, " ", null, null)).as("빈 폴더는 거르지 않는다").isEqualTo(5);
  }

  @Test
  @DisplayName("findAll — 수정 시각 내림차순, 같으면 id 내림차순으로 정렬하고 page·size 로 자른다")
  void list_orderAndPaging() {
    Long q1 = insertA(me, req(prefix + "-1", null, false));
    Long q2 = insertA(me, req(prefix + "-2", null, false));
    Long q3 = insertA(me, req(prefix + "-3", null, false));
    Long q4 = insertA(me, req(prefix + "-4", null, false));
    LocalDateTime base = LocalDateTime.of(2026, 1, 1, 9, 0);
    fx.setSavedQueryUpdatedAt(q1, base.plusHours(1));
    fx.setSavedQueryUpdatedAt(q2, base);
    fx.setSavedQueryUpdatedAt(q3, base);
    fx.setSavedQueryUpdatedAt(q4, base.minusHours(1));

    assertThat(listIds(null, null, null, null, 0, 10)).containsExactly(q1, q3, q2, q4);
    assertThat(listIds(null, null, null, null, 0, 2)).containsExactly(q1, q3);
    assertThat(listIds(null, null, null, null, 1, 2)).containsExactly(q2, q4);
    assertThat(listIds(null, null, null, null, 2, 2)).isEmpty();
  }

  @Test
  @DisplayName("findDistinctFolders — 목록과 같은 범위의 폴더를 중복 없이 오름차순으로, null 폴더는 빼고 돌려준다")
  void distinctFolders_scoped() {
    insertA(me, req(prefix + "-a", "beta", false));
    insertA(me, req(prefix + "-b", "alpha", true));
    insertA(me, req(prefix + "-c", "beta", true));
    insertA(me, req(prefix + "-n", null, false));
    insertA(other, req(prefix + "-d", "gamma", true));
    insertA(other, req(prefix + "-e", "hidden", false));
    inB(() -> repository().insert(req(prefix + "-z", "zeta", true), me));

    assertThat(inA(() -> repository().findDistinctFolders(null, null, me)))
        .containsExactly("alpha", "beta", "gamma");
    assertThat(inA(() -> repository().findDistinctFolders(null, true, me)))
        .containsExactly("alpha", "beta");
    assertThat(inA(() -> repository().findDistinctFolders(true, null, me)))
        .containsExactly("alpha", "beta", "gamma");
    assertThat(inA(() -> repository().findDistinctFolders(true, true, me)))
        .containsExactly("alpha", "beta");
    assertThat(inA(() -> repository().findDistinctFolders(false, true, me)))
        .containsExactly("alpha", "beta");
  }

  // ---- 공유(Review Focus 4) ----

  @Test
  @DisplayName(
      "shareQueries — 내 비공유 쿼리만 공유로 바꾸고 바뀐 것만 id→이름(오름차순)으로 돌려준다(이미 공유·남의 것·다른 테넌트·없는 id 제외)")
  void shareQueries_returnsOnlyFlipped() {
    Long mine1 = insertA(me, req(prefix + "-m1", null, false));
    Long mine2 = insertA(me, req(prefix + "-m2", null, false));
    Long alreadyShared = insertA(me, req(prefix + "-s", null, true));
    Long others = insertA(other, req(prefix + "-o", null, false));
    Long tenantBMine = inB(() -> repository().insert(req(prefix + "-b", null, false), me));

    Map<Long, String> flipped =
        inA(
            () ->
                repository()
                    .shareQueries(
                        List.of(mine2, alreadyShared, others, tenantBMine, -1L, mine1), me));

    assertThat(flipped)
        .containsExactly(Map.entry(mine1, prefix + "-m1"), Map.entry(mine2, prefix + "-m2"));
    assertThat(raw(mine1).isShared()).isTrue();
    assertThat(raw(mine2).isShared()).isTrue();
    assertThat(raw(alreadyShared).isShared()).isTrue();
    assertThat(raw(others).isShared()).as("남의 쿼리는 그대로").isFalse();
    assertThat(
            inB(() -> repository().findRawByIdUnrestricted(tenantBMine)).orElseThrow().isShared())
        .as("다른 테넌트 쿼리는 그대로")
        .isFalse();

    assertThat(inA(() -> repository().shareQueries(List.of(mine1, mine2), me)))
        .as("두 번째 호출은 바뀐 것이 없다")
        .isEmpty();
    assertThat(inA(() -> repository().shareQueries(List.of(), me))).isEmpty();
  }

  @Test
  @DisplayName("countOwnedBySharing — 내가 만든 쿼리만 [비공개 수, 공유 수] 로 센다, 빈 목록은 [0, 0]")
  void countOwnedBySharing_counts() {
    Long p1 = insertA(me, req(prefix + "-p1", null, false));
    Long p2 = insertA(me, req(prefix + "-p2", null, false));
    Long s1 = insertA(me, req(prefix + "-s1", null, true));
    Long others = insertA(other, req(prefix + "-o", null, true));
    Long tenantBMine = inB(() -> repository().insert(req(prefix + "-b", null, true), me));

    assertThat(
            inA(
                () ->
                    repository()
                        .countOwnedBySharing(List.of(p1, p2, s1, others, tenantBMine, -1L), me)))
        .containsExactly(2, 1);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(s1), me))).containsExactly(0, 1);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(others), me)))
        .containsExactly(0, 0);
    assertThat(inA(() -> repository().countOwnedBySharing(List.of(), me))).containsExactly(0, 0);
  }

  // ---- 잠금·수정·삭제 ----

  @Test
  @DisplayName("lockUpdatedAtById — 저장된 수정 시각을 돌려주고, 없는 id·다른 테넌트는 empty")
  void lockUpdatedAtById_returnsStored() {
    Long id = insertA(me, req(prefix + "-l", null, false));
    LocalDateTime stored = LocalDateTime.of(2026, 3, 4, 5, 6, 7, 123456000);
    fx.setSavedQueryUpdatedAt(id, stored);

    assertThat(inA(() -> repository().lockUpdatedAtById(id)))
        .contains(new LockedRowVersion(stored));
    assertThat(inA(() -> repository().lockUpdatedAtById(-1L))).isEmpty();
    assertThat(inB(() -> repository().lockUpdatedAtById(id))).isEmpty();
  }

  @Test
  @DisplayName("update — 넘긴 필드만 바꾸고 빈 설명·폴더는 NULL 로 비우며 수정자·수정 시각을 남긴다")
  void update_partialAndBlankToNull() {
    long ds1 = fx.createDataset(tenantA, prefix + "-d1");
    long ds2 = fx.createDataset(tenantA, prefix + "-d2");
    Long id =
        insertA(me, new CreateSavedQueryRequest(prefix + "-u", "설명", "SELECT 1", ds1, "f1", false));
    LocalDateTime old = LocalDateTime.of(2026, 1, 1, 0, 0);
    fx.setSavedQueryUpdatedAt(id, old);

    runA(
        () ->
            repository()
                .update(
                    id,
                    new UpdateSavedQueryRequest(
                        prefix + "-u2", "  ", "SELECT 2", ds2, "", true, null),
                    other));

    RawSavedQuery raw = raw(id);
    assertThat(raw)
        .isEqualTo(new RawSavedQuery(id, prefix + "-u2", null, "SELECT 2", ds2, null, true, me));
    assertThat(fx.savedQueryUpdatedBy(id)).isEqualTo(other);
    LocalDateTime touched = inA(() -> repository().lockUpdatedAtById(id)).orElseThrow().updatedAt();
    assertThat(touched).isAfter(old);

    // null 은 「변경 없음」 — 이름·SQL·데이터셋·공유는 그대로, 설명·폴더는 새 값.
    runA(
        () ->
            repository()
                .update(
                    id,
                    new UpdateSavedQueryRequest(null, "새 설명", null, null, "f9", null, null),
                    me));
    assertThat(raw(id))
        .isEqualTo(new RawSavedQuery(id, prefix + "-u2", "새 설명", "SELECT 2", ds2, "f9", true, me));
    assertThat(fx.savedQueryUpdatedBy(id)).isEqualTo(me);

    runA(
        () ->
            repository()
                .update(
                    id,
                    new UpdateSavedQueryRequest(null, null, null, null, null, false, null),
                    me));
    assertThat(raw(id).isShared()).isFalse();
    assertThat(raw(id).description()).isEqualTo("새 설명");
  }

  @Test
  @DisplayName("update — 다른 테넌트의 행은 바뀌지 않는다")
  void update_otherTenantUntouched() {
    Long id = insertA(me, req(prefix + "-t", null, false));

    inB(
        () -> {
          repository()
              .update(
                  id,
                  new UpdateSavedQueryRequest(
                      prefix + "-hacked", null, null, null, null, null, null),
                  me);
          return null;
        });

    assertThat(raw(id).name()).isEqualTo(prefix + "-t");
  }

  @Test
  @DisplayName("deleteById — 주인만 지우고(차트도 함께 사라진다) 지웠는지 돌려준다, 남의 것·다른 테넌트·없는 id 는 false")
  void deleteById_ownerOnly() {
    Long id = insertA(me, req(prefix + "-del", null, true));
    fx.createChart(tenantA, me, id, false);

    assertThat(inA(() -> repository().deleteById(id, other))).isFalse();
    assertThat(inB(() -> repository().deleteById(id, me))).isFalse();
    assertThat(inA(() -> repository().findRawByIdUnrestricted(id))).isPresent();

    assertThat(inA(() -> repository().deleteById(id, me))).isTrue();
    assertThat(inA(() -> repository().findRawByIdUnrestricted(id))).isEmpty();
    assertThat(inA(() -> repository().deleteById(id, me))).isFalse();
  }

  @Test
  @DisplayName("countOtherUserCharts — 주인이 아닌 사용자가 만든 차트만 센다")
  void countOtherUserCharts_countsOthers() {
    Long q = insertA(me, req(prefix + "-q", null, true));
    Long q2 = insertA(me, req(prefix + "-q2", null, true));
    fx.createChart(tenantA, me, q, false);
    fx.createChart(tenantA, other, q, false);
    fx.createChart(tenantA, other, q, true);
    fx.createChart(tenantA, other, q2, false);

    assertThat(inA(() -> repository().countOtherUserCharts(q, me))).isEqualTo(2);
    assertThat(inA(() -> repository().countOtherUserCharts(q, other))).isEqualTo(1);
    assertThat(inA(() -> repository().countOtherUserCharts(-1L, me))).isZero();
    assertThat(inB(() -> repository().countOtherUserCharts(q, me))).isZero();
    // 주인이 null 이면 jOOQ 는 created_by <> NULL 을 그려 UNKNOWN → 0행이다 — MyBatis 도 같은 0 이어야 한다(WD-710 C1).
    assertThat(inA(() -> repository().countOtherUserCharts(q, null))).isZero();
  }

  /** SQL 본문은 잘리면 안 된다 — Tibero 도 sql_text 가 CLOB 라 65532 바이트를 넘어도 원문 그대로여야 한다(WD-740). */
  @Test
  @DisplayName("sql_text_가_65532바이트를_넘어도_저장되고_그대로_읽힌다")
  void sql_text_가_65532바이트를_넘어도_저장되고_그대로_읽힌다() {
    String sql = LongTextAssertions.koreanSql70Kb("-- 끝");
    Long id =
        insertA(me, new CreateSavedQueryRequest(prefix + "-long", null, sql, null, null, true));

    assertThat(inA(() -> repository().findById(id, me, ALL)).orElseThrow().sqlText())
        .isEqualTo(sql);
    assertThat(inA(() -> repository().findByIdForOwner(id, me, ALL)).orElseThrow().sqlText())
        .isEqualTo(sql);
    assertThat(raw(id).sqlText()).isEqualTo(sql);
    // 목록 응답은 본문을 싣지 않지만, 긴 본문 행이 목록·건수 경로를 깨뜨리지 않아야 한다.
    assertThat(listIds(null, null, null, null, 0, 50)).contains(id);

    // UPDATE 경로 — 짧은 값으로 바꿨다가 다른 긴 값으로 바꾼다.
    runA(
        () ->
            repository()
                .update(
                    id,
                    new UpdateSavedQueryRequest(null, null, "SELECT 2", null, null, null, null),
                    me));
    assertThat(raw(id).sqlText()).isEqualTo("SELECT 2");
    String sql2 = LongTextAssertions.koreanSql70Kb("-- 수정");
    runA(
        () ->
            repository()
                .update(
                    id, new UpdateSavedQueryRequest(null, null, sql2, null, null, null, null), me));
    assertThat(raw(id).sqlText()).isEqualTo(sql2);
    assertThat(inA(() -> repository().findById(id, me, ALL)).orElseThrow().sqlText())
        .isEqualTo(sql2);
  }

  private RawSavedQuery raw(Long id) {
    Optional<RawSavedQuery> r = inA(() -> repository().findRawByIdUnrestricted(id));
    return r.orElseThrow();
  }
}
