package com.smartfirehub.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.analytics.dto.CreateSavedQueryRequest;
import com.smartfirehub.analytics.dto.SavedQueryListResponse;
import com.smartfirehub.analytics.dto.SavedQueryResponse;
import com.smartfirehub.analytics.dto.UpdateSavedQueryRequest;
import com.smartfirehub.analytics.exception.SavedQueryNotFoundException;
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
        new CreateDatasetRequest(name, tableName, null, null, "SOURCE", columns), ownerUserId);
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
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            ex ->
                assertThat(((ResponseStatusException) ex).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND));
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

  @Test
  void update_nonOwnerCannotUpdate_throwsNotFound() {
    SavedQueryResponse created = createQuery("Query", "SELECT 1", null, true, ownerUserId);

    assertThatThrownBy(
            () ->
                savedQueryService.update(
                    created.id(),
                    new UpdateSavedQueryRequest("Hacked", null, null, null, null, null),
                    otherUserId))
        .isInstanceOf(SavedQueryNotFoundException.class);
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
        .isInstanceOf(SavedQueryNotFoundException.class);
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
        savedQueryService.list("Alpha", null, null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).name()).isEqualTo("Alpha Query");
  }

  @Test
  void list_folderFilter_returnsOnlyQueriesInFolder() {
    createQuery("Folder A Query", "SELECT 1", "folderA", false, ownerUserId);
    createQuery("Folder B Query", "SELECT 2", "folderB", false, ownerUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, "folderA", null, ownerUserId, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).folder()).isEqualTo("folderA");
  }

  @Test
  void list_showsOwnQueriesAndSharedQueries() {
    createQuery("My Private", "SELECT 1", null, false, ownerUserId);
    createQuery("Other Shared", "SELECT 2", null, true, otherUserId);
    createQuery("Other Private", "SELECT 3", null, false, otherUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, null, null, ownerUserId, 0, 10);

    List<String> names = result.content().stream().map(SavedQueryListResponse::name).toList();
    assertThat(names).contains("My Private", "Other Shared");
    assertThat(names).doesNotContain("Other Private");
  }

  @Test
  void list_sharedOnlyFilter_returnsOnlySharedQueries() {
    createQuery("My Private", "SELECT 1", null, false, ownerUserId);
    createQuery("My Shared", "SELECT 2", null, true, ownerUserId);
    createQuery("Other Shared", "SELECT 3", null, true, otherUserId);

    PageResponse<SavedQueryListResponse> result =
        savedQueryService.list(null, null, true, ownerUserId, 0, 10);

    assertThat(result.content()).allMatch(SavedQueryListResponse::isShared);
    List<String> names = result.content().stream().map(SavedQueryListResponse::name).toList();
    assertThat(names).contains("My Shared", "Other Shared");
    assertThat(names).doesNotContain("My Private");
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

    List<String> folders = savedQueryService.getFolders(ownerUserId);

    assertThat(folders).containsExactlyInAnyOrder("reports", "dashboards");
    assertThat(folders).doesNotContainNull();
  }
}
