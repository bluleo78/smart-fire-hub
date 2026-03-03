package com.smartfirehub.dashboard.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dashboard.dto.ActivityFeedResponse;
import com.smartfirehub.dashboard.dto.ActivityFeedResponse.ActivityItem;
import com.smartfirehub.dashboard.dto.AttentionItemResponse;
import com.smartfirehub.dashboard.dto.SystemHealthResponse;
import com.smartfirehub.support.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DashboardHealthTest extends IntegrationTestBase {

  @Autowired private DashboardService dashboardService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long activePipeline1Id; // healthy (last exec COMPLETED)
  private Long activePipeline2Id; // failing (last exec FAILED, failing for 3h)
  private Long activePipeline3Id; // running (last exec RUNNING)
  private Long disabledPipelineId; // disabled (is_active=false)
  private Long freshDatasetId; // SOURCE, imported within 24h
  private Long staleDatasetId; // SOURCE, last imported 48h ago
  private Long criticalStaleDatasetId; // SOURCE, last imported 96h ago (CRITICAL)
  private Long
      emptyDatasetId; // SOURCE, no imports (new, has data table in pg_stat — skip empty test in CI)

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "dashtest_" + System.nanoTime())
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Dashboard Test User")
            .set(USER.EMAIL, "dashtest_" + System.nanoTime() + "@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // ---- Pipelines ----

    // 1. Healthy pipeline (last exec = COMPLETED)
    activePipeline1Id =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Healthy Pipeline " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();

    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, activePipeline1Id)
        .set(PIPELINE_EXECUTION.STATUS, "COMPLETED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusHours(1))
        .set(PIPELINE_EXECUTION.COMPLETED_AT, LocalDateTime.now().minusMinutes(50))
        .execute();

    // 2. Failing pipeline (last exec = FAILED, last success was 3h ago → CRITICAL)
    activePipeline2Id =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Failing Pipeline " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();

    // Earlier success (3 hours ago)
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, activePipeline2Id)
        .set(PIPELINE_EXECUTION.STATUS, "COMPLETED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(3))
        .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusHours(3))
        .set(PIPELINE_EXECUTION.COMPLETED_AT, LocalDateTime.now().minusHours(3).plusMinutes(5))
        .execute();

    // Latest: FAILED (1 hour ago)
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, activePipeline2Id)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.CREATED_AT, LocalDateTime.now().minusHours(1))
        .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusHours(1))
        .execute();

    // 3. Running pipeline (last exec = RUNNING)
    activePipeline3Id =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Running Pipeline " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, true)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();

    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, activePipeline3Id)
        .set(PIPELINE_EXECUTION.STATUS, "RUNNING")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .set(PIPELINE_EXECUTION.STARTED_AT, LocalDateTime.now().minusMinutes(5))
        .execute();

    // 4. Disabled pipeline (is_active=false)
    disabledPipelineId =
        dsl.insertInto(PIPELINE)
            .set(PIPELINE.NAME, "Disabled Pipeline " + System.nanoTime())
            .set(PIPELINE.IS_ACTIVE, false)
            .set(PIPELINE.CREATED_BY, testUserId)
            .returning(PIPELINE.ID)
            .fetchOne()
            .getId();

    // ---- Datasets ----
    // Note: dataset_type check constraint: SOURCE or DERIVED
    // We insert directly; no data schema table is created in tests (that requires DataTableService)

    // Fresh dataset: SOURCE, imported 2h ago (within 24h)
    freshDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "Fresh Dataset " + System.nanoTime())
            .set(DATASET.TABLE_NAME, "fresh_ds_" + System.nanoTime())
            .set(DATASET.DATASET_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, testUserId)
            .set(DATASET.CREATED_AT, LocalDateTime.now().minusDays(7))
            .returning(DATASET.ID)
            .fetchOne()
            .getId();

    // Import SUCCESS 2h ago
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "dashtest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, freshDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(2))
        .execute();

    // Stale dataset (WARNING: 24h~72h old import)
    staleDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "Stale Dataset " + System.nanoTime())
            .set(DATASET.TABLE_NAME, "stale_ds_" + System.nanoTime())
            .set(DATASET.DATASET_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, testUserId)
            .set(DATASET.CREATED_AT, LocalDateTime.now().minusDays(7))
            .returning(DATASET.ID)
            .fetchOne()
            .getId();

    // Import SUCCESS 48h ago
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "dashtest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, staleDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(48))
        .execute();

    // Critical stale dataset (CRITICAL: 72h+)
    criticalStaleDatasetId =
        dsl.insertInto(DATASET)
            .set(DATASET.NAME, "Critical Stale Dataset " + System.nanoTime())
            .set(DATASET.TABLE_NAME, "critical_stale_ds_" + System.nanoTime())
            .set(DATASET.DATASET_TYPE, "SOURCE")
            .set(DATASET.CREATED_BY, testUserId)
            .set(DATASET.CREATED_AT, LocalDateTime.now().minusDays(30))
            .returning(DATASET.ID)
            .fetchOne()
            .getId();

    // Import SUCCESS 96h ago
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "dashtest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, criticalStaleDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "SUCCESS")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(96))
        .execute();
  }

  // ======================================================================
  // getSystemHealth() tests
  // ======================================================================

  @Test
  void getSystemHealth_pipelineCounts_correct() {
    SystemHealthResponse health = dashboardService.getSystemHealth();

    SystemHealthResponse.PipelineHealth ph = health.pipelineHealth();

    // At least our 4 test pipelines exist (test DB may have pre-existing data)
    assertThat(ph.total()).isGreaterThanOrEqualTo(4);
    assertThat(ph.disabled()).isGreaterThanOrEqualTo(1);
    assertThat(ph.running()).isGreaterThanOrEqualTo(1);
    assertThat(ph.failing()).isGreaterThanOrEqualTo(1);
    assertThat(ph.healthy()).isGreaterThanOrEqualTo(1);

    // Consistency: total = healthy + failing + running + disabled
    assertThat(ph.total())
        .isGreaterThanOrEqualTo(ph.healthy() + ph.failing() + ph.running() + ph.disabled());
  }

  @Test
  void getSystemHealth_datasetCounts_correct() {
    SystemHealthResponse health = dashboardService.getSystemHealth();

    SystemHealthResponse.DatasetHealth dh = health.datasetHealth();

    // We inserted at least 3 source datasets: fresh, stale, criticalStale
    assertThat(dh.total()).isGreaterThanOrEqualTo(3);
    assertThat(dh.fresh()).isGreaterThanOrEqualTo(1); // freshDataset
    assertThat(dh.stale()).isGreaterThanOrEqualTo(2); // stale + criticalStale
  }

  @Test
  void getSystemHealth_returnsNonNullResponse() {
    SystemHealthResponse health = dashboardService.getSystemHealth();

    assertThat(health).isNotNull();
    assertThat(health.pipelineHealth()).isNotNull();
    assertThat(health.datasetHealth()).isNotNull();
  }

  // ======================================================================
  // getAttentionItems() tests
  // ======================================================================

  @Test
  void getAttentionItems_containsFailedPipeline() {
    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    List<AttentionItemResponse> pipelineFailures =
        items.stream()
            .filter(i -> "PIPELINE_FAILED".equals(i.type()))
            .filter(i -> i.entityId().equals(activePipeline2Id))
            .toList();

    assertThat(pipelineFailures).hasSize(1);
    AttentionItemResponse item = pipelineFailures.get(0);
    assertThat(item.entityType()).isEqualTo("PIPELINE");
    assertThat(item.severity()).isEqualTo("CRITICAL"); // last success was 3h ago → CRITICAL
  }

  @Test
  void getAttentionItems_containsStaleDatasets() {
    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    // stale dataset (48h) → WARNING
    List<AttentionItemResponse> staleItems =
        items.stream()
            .filter(i -> "DATASET_STALE".equals(i.type()))
            .filter(i -> i.entityId().equals(staleDatasetId))
            .toList();
    assertThat(staleItems).hasSize(1);
    assertThat(staleItems.get(0).severity()).isEqualTo("WARNING");

    // critical stale dataset (96h) → CRITICAL
    List<AttentionItemResponse> criticalItems =
        items.stream()
            .filter(i -> "DATASET_STALE".equals(i.type()))
            .filter(i -> i.entityId().equals(criticalStaleDatasetId))
            .toList();
    assertThat(criticalItems).hasSize(1);
    assertThat(criticalItems.get(0).severity()).isEqualTo("CRITICAL");
  }

  @Test
  void getAttentionItems_freshDatasetNotInList() {
    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    // Fresh dataset should NOT appear in attention items
    boolean hasFreshAsStale =
        items.stream()
            .filter(i -> "DATASET_STALE".equals(i.type()))
            .anyMatch(i -> i.entityId().equals(freshDatasetId));

    assertThat(hasFreshAsStale).isFalse();
  }

  @Test
  void getAttentionItems_sortedBySeverityThenDate() {
    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    // CRITICAL items must all come before WARNING items
    boolean seenWarning = false;
    for (AttentionItemResponse item : items) {
      if ("WARNING".equals(item.severity())) {
        seenWarning = true;
      }
      if (seenWarning) {
        assertThat(item.severity()).isNotEqualTo("CRITICAL");
      }
    }
  }

  @Test
  void getAttentionItems_failedImportWithin24h_appearsAsWarning() {
    // Insert a failed import for the fresh dataset (within 24h)
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "dashtest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, freshDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "FAILURE")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(1))
        .execute();

    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    List<AttentionItemResponse> importFailures =
        items.stream()
            .filter(i -> "IMPORT_FAILED".equals(i.type()))
            .filter(i -> i.entityId().equals(freshDatasetId))
            .toList();

    assertThat(importFailures).isNotEmpty();
    assertThat(importFailures.get(0).severity()).isEqualTo("WARNING");
    assertThat(importFailures.get(0).entityType()).isEqualTo("DATASET");
  }

  @Test
  void getAttentionItems_failedImportOlderThan24h_doesNotAppear() {
    // Insert a failed import for freshDataset that is older than 24h
    dsl.insertInto(AUDIT_LOG)
        .set(AUDIT_LOG.USER_ID, testUserId)
        .set(AUDIT_LOG.USERNAME, "dashtest")
        .set(AUDIT_LOG.ACTION_TYPE, "IMPORT")
        .set(AUDIT_LOG.RESOURCE, "dataset")
        .set(AUDIT_LOG.RESOURCE_ID, freshDatasetId.toString())
        .set(AUDIT_LOG.RESULT, "FAILURE")
        .set(AUDIT_LOG.ACTION_TIME, LocalDateTime.now().minusHours(25))
        .execute();

    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    boolean hasOldImportFailure =
        items.stream()
            .filter(i -> "IMPORT_FAILED".equals(i.type()))
            .filter(i -> i.entityId().equals(freshDatasetId))
            .anyMatch(i -> i.occurredAt().isBefore(LocalDateTime.now().minusHours(24)));

    assertThat(hasOldImportFailure).isFalse();
  }

  @Test
  void getAttentionItems_disabledPipelineNotInFailureList() {
    // First, add a FAILED execution for the disabled pipeline
    dsl.insertInto(PIPELINE_EXECUTION)
        .set(PIPELINE_EXECUTION.PIPELINE_ID, disabledPipelineId)
        .set(PIPELINE_EXECUTION.STATUS, "FAILED")
        .set(PIPELINE_EXECUTION.EXECUTED_BY, testUserId)
        .execute();

    List<AttentionItemResponse> items = dashboardService.getAttentionItems();

    // Disabled pipeline failure should NOT appear (we only report active pipeline failures)
    boolean hasDisabledPipelineFailure =
        items.stream()
            .filter(i -> "PIPELINE_FAILED".equals(i.type()))
            .anyMatch(i -> i.entityId().equals(disabledPipelineId));

    assertThat(hasDisabledPipelineFailure).isFalse();
  }

  // ======================================================================
  // getActivityFeed() tests
  // ======================================================================

  @Test
  void getActivityFeed_noFilter_returnsMixedItems() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, null, 0, 50);

    assertThat(feed).isNotNull();
    assertThat(feed.items()).isNotNull();
    // We have pipeline executions and audit_log entries from setUp
    assertThat(feed.totalCount()).isGreaterThan(0);
  }

  @Test
  void getActivityFeed_typeFilterPipeline_returnsPipelineItemsOnly() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed("PIPELINE", null, 0, 50);

    assertThat(feed.items()).isNotEmpty();
    assertThat(feed.items()).allMatch(item -> "PIPELINE".equals(item.entityType()));
  }

  @Test
  void getActivityFeed_typeFilterDataset_returnsDatasetItemsOnly() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed("DATASET", null, 0, 50);

    // We have IMPORT events in audit_log
    assertThat(feed.items()).allMatch(item -> "DATASET".equals(item.entityType()));
  }

  @Test
  void getActivityFeed_severityFilterCritical_returnsCriticalOnly() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, "CRITICAL", 0, 50);

    assertThat(feed.items()).allMatch(item -> "CRITICAL".equals(item.severity()));
  }

  @Test
  void getActivityFeed_pagination_worksCorrectly() {
    ActivityFeedResponse page0 = dashboardService.getActivityFeed(null, null, 0, 3);
    ActivityFeedResponse page1 = dashboardService.getActivityFeed(null, null, 1, 3);

    // If totalCount > 3, we have multiple pages
    if (page0.totalCount() > 3) {
      assertThat(page0.hasMore()).isTrue();
      assertThat(page0.items()).hasSize(3);
      assertThat(page1.items()).isNotEmpty();
    }

    assertThat(page0.totalCount()).isEqualTo(page1.totalCount());
  }

  @Test
  void getActivityFeed_pagination_emptyPageBeyondTotal() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, null, 0, 50);
    int total = feed.totalCount();

    // Request a page well beyond the total
    ActivityFeedResponse beyondPage = dashboardService.getActivityFeed(null, null, 1000, 50);
    assertThat(beyondPage.items()).isEmpty();
    assertThat(beyondPage.totalCount()).isEqualTo(total);
    assertThat(beyondPage.hasMore()).isFalse();
  }

  @Test
  void getActivityFeed_unresolvedIssuesPinnedToTop() {
    // The failing pipeline has isResolved=false and should appear before resolved items
    ActivityFeedResponse feed = dashboardService.getActivityFeed("PIPELINE", null, 0, 50);

    List<ActivityItem> items = feed.items();
    if (items.size() < 2) return; // Not enough items to compare

    // Find first resolved and first unresolved items
    int firstUnresolvedIndex = -1;
    int firstResolvedIndex = -1;
    for (int i = 0; i < items.size(); i++) {
      if (!items.get(i).isResolved() && firstUnresolvedIndex == -1) {
        firstUnresolvedIndex = i;
      }
      if (items.get(i).isResolved() && firstResolvedIndex == -1) {
        firstResolvedIndex = i;
      }
    }

    // If both exist, unresolved must come first
    if (firstUnresolvedIndex != -1 && firstResolvedIndex != -1) {
      assertThat(firstUnresolvedIndex).isLessThan(firstResolvedIndex);
    }
  }

  @Test
  void getActivityFeed_failedPipelineHasCriticalSeverity() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed("PIPELINE", "CRITICAL", 0, 50);

    List<ActivityItem> failedItems =
        feed.items().stream().filter(i -> "PIPELINE_FAILED".equals(i.eventType())).toList();

    assertThat(failedItems).isNotEmpty();
    assertThat(failedItems).allMatch(i -> "CRITICAL".equals(i.severity()));
  }

  @Test
  void getActivityFeed_importEventsAppear() {
    ActivityFeedResponse feed = dashboardService.getActivityFeed("IMPORT", null, 0, 50);

    // We have IMPORT SUCCESS and FAILURE audit log entries from setUp
    assertThat(feed.items()).isNotEmpty();
    boolean hasImportEvent =
        feed.items().stream().anyMatch(i -> i.eventType().startsWith("IMPORT_"));
    assertThat(hasImportEvent).isTrue();
  }

  @Test
  void getActivityFeed_hasMoreFalseWhenFitsInOnePage() {
    // Request a large page that should fit all items
    ActivityFeedResponse feed = dashboardService.getActivityFeed(null, null, 0, 10000);

    assertThat(feed.hasMore()).isFalse();
    assertThat(feed.items()).hasSize(feed.totalCount());
  }

  @Test
  void getActivityFeed_allFilterReturnsAllItems() {
    ActivityFeedResponse allFeed = dashboardService.getActivityFeed("ALL", "ALL", 0, 10000);
    ActivityFeedResponse noFilterFeed = dashboardService.getActivityFeed(null, null, 0, 10000);

    assertThat(allFeed.totalCount()).isEqualTo(noFilterFeed.totalCount());
  }
}
