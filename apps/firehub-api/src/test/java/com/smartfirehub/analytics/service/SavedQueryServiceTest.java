package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
import com.smartfirehub.analytics.repository.SavedQueryRepository;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.global.dto.PageResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Transactional
class SavedQueryServiceTest extends IntegrationTestBase {

  @Autowired private SavedQueryService savedQueryService;
  @Autowired private SavedQueryRepository savedQueryRepository;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  private Long ownerUserId;
  private Long otherUserId;

  @BeforeEach
  void setUp() {
    ownerUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "owner")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Owner User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "owner@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    otherUserId =
        dsl.insertInto(DSL.table(DSL.name("user")))
            .set(DSL.field(DSL.name("user", "username"), String.class), "other")
            .set(DSL.field(DSL.name("user", "password"), String.class), "password")
            .set(DSL.field(DSL.name("user", "name"), String.class), "Other User")
            .set(DSL.field(DSL.name("user", "email"), String.class), "other@example.com")
            .returning(DSL.field(DSL.name("user", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("user", "id"), Long.class));

    // 보안 등급(Task 3 C1/C2): 연결 데이터셋 이름·지정은 사용자 자격으로 판정한다 — 두 사용자를 기본 테넌트 USER(기본=내부) 멤버로
    // 만든다. 자격이 없으면 이름이 null 이 되고 datasetId 지정이 404 로 거부된다(그 자체가 이 태스크의 동작).
    for (Long uid : List.of(ownerUserId, otherUserId)) {
      com.smartfirehub.support.TenantRlsTestSupport.insertActiveMembership(
          dsl, uid, DEFAULT_TEST_TENANT_ID);
      dsl.execute(
          "insert into user_role (user_id, role_id) select ?, id from role where name = 'USER'",
          uid);
    }
  }

  // =========================================================================
  // Helper
  // =========================================================================

  private SavedQueryResponse createQuery(
      String name, String sql, String folder, boolean isShared, Long userId) {
    return savedQueryService.create(
        new CreateSavedQueryRequest(name, null, sql, null, folder, isShared), userId);
  }

  private SavedQueryResponse createQueryWithDataset(
      String name, String sql, Long datasetId, Long userId) {
    return savedQueryService.create(
        new CreateSavedQueryRequest(name, null, sql, datasetId, null, false), userId);
  }

  private DatasetDetailResponse createDataset(String name, String tableName) {
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    return datasetService.createDataset(
        new CreateDatasetRequest(name, tableName, null, null, "TABLE", "SOURCE", columns, null),
        ownerUserId);
  }

  // =========================================================================
  // Create
  // =========================================================================

  @Test
  void create_withoutDatasetId_success() {
    SavedQueryResponse response =
        savedQueryService.create(
            new CreateSavedQueryRequest(
                "My Query", "description", "SELECT 1", null, "reports", false),
            ownerUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.name()).isEqualTo("My Query");
    assertThat(response.sqlText()).isEqualTo("SELECT 1");
    assertThat(response.datasetId()).isNull();
    assertThat(response.folder()).isEqualTo("reports");
    assertThat(response.isShared()).isFalse();
  }

  @Test
  void create_withValidDatasetId_success() {
    DatasetDetailResponse dataset = createDataset("DS for Query", "ds_for_query");

    SavedQueryResponse response =
        createQueryWithDataset("Linked Query", "SELECT 1", dataset.id(), ownerUserId);

    assertThat(response.id()).isNotNull();
    assertThat(response.datasetId()).isEqualTo(dataset.id());
    assertThat(response.datasetName()).isEqualTo("DS for Query");
  }

  @Test
  void create_withNonExistentDatasetId_throwsNotFound() {
    assertThatThrownBy(
            () ->
                savedQueryService.create(
                    new CreateSavedQueryRequest("Q", null, "SELECT 1", 999999L, null, false),
                    ownerUserId))
        // 없는 id 는 숨김 id 와 같은 DatasetNotFoundException(404, 같은 메시지)이다(존재 은닉 — Task 3 C2).
        .isInstanceOf(com.smartfirehub.dataset.exception.DatasetNotFoundException.class)
        .hasMessage("데이터셋을 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-240: 한국어·번호 없음
  }

  // =========================================================================
  // GetById
  // =========================================================================

  @Test
  void getById_ownerCanAccessOwnQuery() {
    SavedQueryResponse created = createQuery("Owner Query", "SELECT 1", null, false, ownerUserId);

    SavedQueryResponse found = savedQueryService.getById(created.id(), ownerUserId);

    assertThat(found.id()).isEqualTo(created.id());
    assertThat(found.name()).isEqualTo("Owner Query");
  }

  @Test
  void getById_sharedQueryAccessibleByOtherUser() {
    SavedQueryResponse sharedQuery =
        createQuery("Shared Query", "SELECT 1", null, true, ownerUserId);

    SavedQueryResponse found = savedQueryService.getById(sharedQuery.id(), otherUserId);

    assertThat(found.id()).isEqualTo(sharedQuery.id());
  }

  @Test
  void getById_privateQueryNotAccessibleByOtherUser_throwsNotFound() {
    SavedQueryResponse privateQuery =
        createQuery("Private Query", "SELECT 1", null, false, ownerUserId);

    assertThatThrownBy(() -> savedQueryService.getById(privateQuery.id(), otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  // =========================================================================
  // Update
  // =========================================================================

  @Test
  void update_ownerCanUpdateQuery_success() {
    SavedQueryResponse created = createQuery("Original", "SELECT 1", null, false, ownerUserId);

    SavedQueryResponse updated =
        savedQueryService.update(
            created.id(),
            new UpdateSavedQueryRequest("Updated Name", "new desc", "SELECT 2", null, null, null),
            ownerUserId);

    assertThat(updated.name()).isEqualTo("Updated Name");
    assertThat(updated.description()).isEqualTo("new desc");
    assertThat(updated.sqlText()).isEqualTo("SELECT 2");
  }

  /** 설명·폴더를 빈 문자열로 보내면 지워지고(NULL), 생략(null)하면 기존 값이 남는다(WD-197). */
  @Test
  void update_blankDescriptionAndFolderClear_nullKeeps() {
    SavedQueryResponse created = createQuery("Desc Query", "SELECT 1", "보고서", false, ownerUserId);
    savedQueryService.update(
        created.id(),
        new UpdateSavedQueryRequest(null, "옛 설명", null, null, null, null),
        ownerUserId);

    // 생략(null) → 설명·폴더 모두 변경 없음
    savedQueryService.update(
        created.id(),
        new UpdateSavedQueryRequest("이름만 바꿈", null, null, null, null, null),
        ownerUserId);
    SavedQueryResponse kept = savedQueryService.getById(created.id(), ownerUserId);
    assertThat(kept.description()).isEqualTo("옛 설명");
    assertThat(kept.folder()).isEqualTo("보고서");

    // 빈 문자열 → 설명 지움, "폴더 없음"으로 이동
    SavedQueryResponse cleared =
        savedQueryService.update(
            created.id(), new UpdateSavedQueryRequest(null, "", null, null, "", null), ownerUserId);
    assertThat(cleared.description()).isNull();
    assertThat(cleared.folder()).isNull();
    SavedQueryResponse reloaded = savedQueryService.getById(created.id(), ownerUserId);
    assertThat(reloaded.description()).isNull();
    assertThat(reloaded.folder()).isNull();
  }

  @Test
  void update_nonOwnerCannotUpdate_throwsNotFound() {
    SavedQueryResponse created = createQuery("Query", "SELECT 1", null, true, ownerUserId);

    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    created.id(),
                    new UpdateSavedQueryRequest("Hacked", null, null, null, null, null),
                    otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class)
        // WD-196: 공유 쿼리를 남이 저장하면 한국어 문구(예전 「Saved query not found: id」)
        .hasMessage("저장 쿼리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-240: 번호 없음
  }

  @Test
  void update_sharedQuerySqlChangeWithOtherUserCharts_throwsConflict() {
    // Create a shared query owned by ownerUserId
    SavedQueryResponse sharedQuery = createQuery("Shared SQL", "SELECT 1", null, true, ownerUserId);

    // otherUserId creates a chart referencing this query
    dsl.insertInto(DSL.table(DSL.name("chart")))
        .set(DSL.field(DSL.name("chart", "name"), String.class), "Other Chart")
        .set(DSL.field(DSL.name("chart", "saved_query_id"), Long.class), sharedQuery.id())
        .set(DSL.field(DSL.name("chart", "chart_type"), String.class), "BAR")
        .set(DSL.field(DSL.name("chart", "is_shared"), Boolean.class), false)
        .set(DSL.field(DSL.name("chart", "created_by"), Long.class), otherUserId)
        .execute();

    // Owner tries to change SQL text — should fail with 409
    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    sharedQuery.id(),
                    new UpdateSavedQueryRequest(null, null, "SELECT 2", null, null, null),
                    ownerUserId))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.CONFLICT));
  }

  @Test
  void update_sharedQueryMetadataChangeWithOtherUserCharts_allowed() {
    // Create a shared query owned by ownerUserId
    SavedQueryResponse sharedQuery =
        createQuery("Shared Meta", "SELECT 1", null, true, ownerUserId);

    // otherUserId creates a chart referencing this query
    dsl.insertInto(DSL.table(DSL.name("chart")))
        .set(DSL.field(DSL.name("chart", "name"), String.class), "Other Chart 2")
        .set(DSL.field(DSL.name("chart", "saved_query_id"), Long.class), sharedQuery.id())
        .set(DSL.field(DSL.name("chart", "chart_type"), String.class), "LINE")
        .set(DSL.field(DSL.name("chart", "is_shared"), Boolean.class), false)
        .set(DSL.field(DSL.name("chart", "created_by"), Long.class), otherUserId)
        .execute();

    // Owner changes only name/description — should succeed
    SavedQueryResponse updated =
        savedQueryService.update(
            sharedQuery.id(),
            new UpdateSavedQueryRequest("Renamed", "new description", null, null, null, null),
            ownerUserId);

    assertThat(updated.name()).isEqualTo("Renamed");
    assertThat(updated.description()).isEqualTo("new description");
    assertThat(updated.sqlText()).isEqualTo("SELECT 1"); // unchanged
  }

  // =========================================================================
  // Delete
  // =========================================================================

  @Test
  void delete_ownerCanDeleteQuery_success() {
    SavedQueryResponse created = createQuery("To Delete", "SELECT 1", null, false, ownerUserId);
    Long id = created.id();

    savedQueryService.delete(id, ownerUserId);

    assertThatThrownBy(() -> savedQueryService.getById(id, ownerUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  @Test
  void delete_nonOwnerCannotDelete_throwsNotFound() {
    SavedQueryResponse created = createQuery("Not Mine", "SELECT 1", null, true, ownerUserId);

    assertThatThrownBy(() -> savedQueryService.delete(created.id(), otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class)
        .hasMessage("저장 쿼리를 찾을 수 없습니다. 이미 삭제되었을 수 있습니다."); // WD-240: 번호 없음
  }

  @Test
  void delete_cascadesLinkedCharts() {
    // Create query owned by ownerUserId
    SavedQueryResponse query = createQuery("Cascade Query", "SELECT 1", null, false, ownerUserId);

    // Insert a chart referencing it
    Long chartId =
        dsl.insertInto(DSL.table(DSL.name("chart")))
            .set(DSL.field(DSL.name("chart", "name"), String.class), "Cascade Chart")
            .set(DSL.field(DSL.name("chart", "saved_query_id"), Long.class), query.id())
            .set(DSL.field(DSL.name("chart", "chart_type"), String.class), "TABLE")
            .set(DSL.field(DSL.name("chart", "is_shared"), Boolean.class), false)
            .set(DSL.field(DSL.name("chart", "created_by"), Long.class), ownerUserId)
            .returning(DSL.field(DSL.name("chart", "id"), Long.class))
            .fetchOne()
            .get(DSL.field(DSL.name("chart", "id"), Long.class));

    // Delete the query
    savedQueryService.delete(query.id(), ownerUserId);

    // Chart should be gone (CASCADE DELETE)
    Long chartCount =
        dsl.selectCount()
            .from(DSL.table(DSL.name("chart")))
            .where(DSL.field(DSL.name("chart", "id"), Long.class).eq(chartId))
            .fetchOne(0, Long.class);
    assertThat(chartCount).isEqualTo(0);
  }

  // =========================================================================
  // Clone
  // =========================================================================

  @Test
  void clone_createsPrivateCopyWithSuffix() {
    SavedQueryResponse original =
        createQuery("Original Query", "SELECT 42", "my-folder", false, ownerUserId);

    SavedQueryResponse cloned = savedQueryService.clone(original.id(), ownerUserId);

    assertThat(cloned.id()).isNotEqualTo(original.id());
    assertThat(cloned.name()).isEqualTo("Original Query (복사본)");
    assertThat(cloned.isShared()).isFalse();
    assertThat(cloned.sqlText()).isEqualTo("SELECT 42");
    assertThat(cloned.folder()).isEqualTo("my-folder");
  }

  @Test
  void clone_otherUserCanCloneSharedQuery() {
    SavedQueryResponse shared =
        createQuery("Shared For Clone", "SELECT 99", null, true, ownerUserId);

    SavedQueryResponse cloned = savedQueryService.clone(shared.id(), otherUserId);

    assertThat(cloned.name()).isEqualTo("Shared For Clone (복사본)");
    assertThat(cloned.isShared()).isFalse();
    assertThat(cloned.createdBy()).isEqualTo(otherUserId);
  }

  @Test
  void clone_privateQueryNotCloneableByOtherUser_throwsNotFound() {
    SavedQueryResponse privateQuery = createQuery("Private", "SELECT 1", null, false, ownerUserId);

    assertThatThrownBy(() -> savedQueryService.clone(privateQuery.id(), otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
  }

  // =========================================================================
  // List
  // =========================================================================

  @Test
  void list_searchByNameFilter_returnsMatchingQueries() {
    createQuery("Alpha Query", "SELECT 1", null, false, ownerUserId);
    createQuery("Beta Query", "SELECT 2", null, false, ownerUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list("Alpha", null, null, null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("Alpha Query");
  }

  @Test
  void list_folderFilter_returnsOnlyQueriesInFolder() {
    createQuery("Folder A Query", "SELECT 1", "folderA", false, ownerUserId);
    createQuery("Folder B Query", "SELECT 2", "folderB", false, ownerUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, "folderA", null, null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).folder()).isEqualTo("folderA");
  }

  @Test
  void list_showsOwnQueriesAndSharedQueries() {
    createQuery("My Private", "SELECT 1", null, false, ownerUserId);
    createQuery("Other Shared", "SELECT 2", null, true, otherUserId);
    createQuery("Other Private", "SELECT 3", null, false, otherUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, null, null, null, ownerUserId, 0, 10);

    List<String> names = result.content().stream().map(SavedQueryListResponse::name).toList();
    assertThat(names).contains("My Private", "Other Shared");
    assertThat(names).doesNotContain("Other Private");
    // WD-196: 목록 항목에 작성자 id 가 실린다 — 웹이 남의 공유 쿼리 줄에서 삭제·편집 버튼을 숨기는 근거
    assertThat(result.content())
        .filteredOn(q -> q.name().equals("Other Shared"))
        .extracting(SavedQueryListResponse::createdBy)
        .containsExactly(otherUserId);
    assertThat(result.content())
        .filteredOn(q -> q.name().equals("My Private"))
        .extracting(SavedQueryListResponse::createdBy)
        .containsExactly(ownerUserId);
  }

  @Test
  void list_sharedOnlyFilter_returnsOnlySharedQueries() {
    createQuery("My Private", "SELECT 1", null, false, ownerUserId);
    createQuery("My Shared", "SELECT 2", null, true, ownerUserId);
    createQuery("Other Shared", "SELECT 3", null, true, otherUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, null, true, null, ownerUserId, 0, 10);

    assertThat(result.content()).allMatch(SavedQueryListResponse::isShared);
    List<String> names = result.content().stream().map(SavedQueryListResponse::name).toList();
    assertThat(names).contains("My Shared", "Other Shared");
    assertThat(names).doesNotContain("My Private");
  }

  /** WD-328: mineOnly — 「내 쿼리」 탭은 내 비공유·내 공유 쿼리만, 남의 공유 쿼리는 빠지고 건수도 같다. */
  @Test
  void list_mineOnlyFilter_excludesOthersSharedQueries() {
    createQuery("My Private", "SELECT 1", null, false, ownerUserId);
    createQuery("My Shared", "SELECT 2", null, true, ownerUserId);
    createQuery("Other Shared", "SELECT 3", null, true, otherUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, null, null, true, ownerUserId, 0, 10);

    assertThat(result.content()).allMatch(q -> ownerUserId.equals(q.createdBy()));
    List<String> names = result.content().stream().map(SavedQueryListResponse::name).toList();
    assertThat(names).contains("My Private", "My Shared");
    assertThat(names).doesNotContain("Other Shared");
    // 건수(countAll)도 같은 범위 조건을 써서 목록과 어긋나지 않는다
    assertThat(result.totalElements()).isEqualTo(result.content().size());
  }

  // =========================================================================
  // getFolders
  // =========================================================================

  @Test
  void getFolders_returnsDedupedFolderList() {
    createQuery("Q1", "SELECT 1", "reports", false, ownerUserId);
    createQuery("Q2", "SELECT 2", "reports", false, ownerUserId);
    createQuery("Q3", "SELECT 3", "dashboards", false, ownerUserId);
    createQuery("Q4", "SELECT 4", null, false, ownerUserId); // no folder

    List<String> folders = savedQueryService.getFolders(null, null, ownerUserId);

    assertThat(folders).containsExactlyInAnyOrder("reports", "dashboards");
    assertThat(folders).doesNotContainNull();
  }

  /** WD-328: mineOnly — 「내 쿼리」 탭의 폴더 목록은 내 쿼리의 폴더만, 남의 공유 쿼리에만 있는 폴더는 빠진다. */
  @Test
  void getFolders_mineOnly_excludesOthersSharedFolders() {
    createQuery("My Private", "SELECT 1", "myPrivateFolder", false, ownerUserId);
    createQuery("My Shared", "SELECT 2", "mySharedFolder", true, ownerUserId);
    createQuery("Other Shared", "SELECT 3", "otherSharedFolder", true, otherUserId);

    List<String> folders = savedQueryService.getFolders(null, true, ownerUserId);

    assertThat(folders).contains("myPrivateFolder", "mySharedFolder");
    assertThat(folders).doesNotContain("otherSharedFolder");
  }

  /** WD-328: sharedOnly — 「공유됨」 탭의 폴더 목록은 공유 쿼리(내 것 포함)의 폴더만, 내 비공유 쿼리의 폴더는 빠진다. */
  @Test
  void getFolders_sharedOnly_returnsOnlySharedQueryFolders() {
    createQuery("My Private", "SELECT 1", "myPrivateFolder", false, ownerUserId);
    createQuery("My Shared", "SELECT 2", "mySharedFolder", true, ownerUserId);
    createQuery("Other Shared", "SELECT 3", "otherSharedFolder", true, otherUserId);

    List<String> folders = savedQueryService.getFolders(true, null, ownerUserId);

    assertThat(folders).contains("mySharedFolder", "otherSharedFolder");
    assertThat(folders).doesNotContain("myPrivateFolder");
  }

  // =========================================================================
  // findRawByIdUnrestricted — 소유권 무관 조회 (tautology 버그 회귀 테스트, GitHub #151)
  // =========================================================================

  /**
   * findRawByIdUnrestricted 는 소유권(created_by)에 관계없이 id로 레코드를 반환해야 한다. 이전에 존재하던 findRawById() 메서드는
   * WHERE 절에 SQ_CREATED_BY.eq(SQ_CREATED_BY) (tautology) 조건을 포함한 dead code였으며 제거되었다. 이 테스트는 올바른 조회
   * 메서드가 다른 사용자의 private 쿼리도 반환함을 검증한다.
   */
  @Test
  void findRawByIdUnrestricted_returnsRecordRegardlessOfOwnership() {
    // ownerUserId 의 private 쿼리 생성
    SavedQueryResponse privateQuery =
        createQuery("Private Raw", "SELECT 999", null, false, ownerUserId);

    // otherUserId 기준에서도 raw 조회가 성공해야 한다
    var rawOpt = savedQueryRepository.findRawByIdUnrestricted(privateQuery.id());

    assertThat(rawOpt).isPresent();
    assertThat(rawOpt.get().get("name", String.class)).isEqualTo("Private Raw");
    assertThat(rawOpt.get().get("sql_text", String.class)).isEqualTo("SELECT 999");
  }

  @Test
  void findRawByIdUnrestricted_nonExistentId_returnsEmpty() {
    var rawOpt = savedQueryRepository.findRawByIdUnrestricted(Long.MAX_VALUE);
    assertThat(rawOpt).isEmpty();
  }

  // =========================================================================
  // 저장 시점 SQL 열람 판정 (WD-409)
  // =========================================================================

  /** 데이터셋을 만들고 보안 등급을 '민감'으로 올려 USER 기본 자격(내부)으로는 볼 수 없게 한다. */
  private DatasetDetailResponse createHiddenDataset(String name, String tableName) {
    DatasetDetailResponse ds = createDataset(name, tableName);
    dsl.execute(
        "update dataset set security_level_id = (select id from security_level where name = '민감')"
            + " where id = ?",
        ds.id());
    return ds;
  }

  private long countSavedQueries() {
    return dsl.fetchOne("select count(*) from saved_query").get(0, Long.class);
  }

  @Test
  void create_referencingHiddenTable_forbiddenAndNotSaved() {
    DatasetDetailResponse ds = createHiddenDataset("Hidden DS", "wd409_hidden");
    long before = countSavedQueries();

    assertThatThrownBy(
            () ->
                createQuery("Hidden", "SELECT * FROM " + ds.tableName(), null, false, ownerUserId))
        .isInstanceOfSatisfying(
            com.smartfirehub.global.exception.CodedApiException.class,
            e -> {
              assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN);
              assertThat(e.code()).isEqualTo("DATASET_SQL_ACCESS_DENIED");
            });
    assertThat(countSavedQueries()).isEqualTo(before);
  }

  @Test
  void create_referencingVisibleDatasetTable_success() {
    DatasetDetailResponse ds = createDataset("Visible DS", "wd409_visible");

    SavedQueryResponse r =
        createQuery("Visible", "SELECT * FROM " + ds.tableName(), null, false, ownerUserId);

    assertThat(r.id()).isNotNull();
  }

  @Test
  void create_unparseableSql_stillSaved() {
    SavedQueryResponse r = createQuery("Broken", "SELEC broken", null, false, ownerUserId);

    assertThat(r.sqlText()).isEqualTo("SELEC broken");
  }

  @Test
  void update_sqlChangedToHiddenTable_forbiddenAndUnchanged() {
    DatasetDetailResponse ds = createHiddenDataset("Hidden DS2", "wd409_hidden2");
    SavedQueryResponse created = createQuery("Q", "SELECT 1", null, false, ownerUserId);

    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    created.id(),
                    new UpdateSavedQueryRequest(
                        null, null, "SELECT * FROM " + ds.tableName(), null, null, null),
                    ownerUserId))
        .isInstanceOfSatisfying(
            com.smartfirehub.global.exception.CodedApiException.class,
            e -> assertThat(e.status()).isEqualTo(HttpStatus.FORBIDDEN));
    assertThat(savedQueryService.getById(created.id(), ownerUserId).sqlText())
        .isEqualTo("SELECT 1");
  }

  @Test
  void update_metadataOnly_withLegacyDeniedSql_allowed() {
    DatasetDetailResponse ds = createHiddenDataset("Hidden DS3", "wd409_hidden3");
    String legacySql = "SELECT * FROM " + ds.tableName();
    // 옛 데이터 — 리포지토리로 직접 넣어 저장 시점 판정을 우회한다.
    Long id =
        savedQueryRepository.insert(
            new CreateSavedQueryRequest("Legacy", null, legacySql, null, null, false), ownerUserId);

    SavedQueryResponse renamed =
        savedQueryService.update(
            id, new UpdateSavedQueryRequest("Renamed", null, null, null, null, null), ownerUserId);
    SavedQueryResponse sameSql =
        savedQueryService.update(
            id,
            new UpdateSavedQueryRequest("Renamed2", null, legacySql, null, null, null),
            ownerUserId);

    assertThat(renamed.name()).isEqualTo("Renamed");
    assertThat(sameSql.name()).isEqualTo("Renamed2");
  }
}
