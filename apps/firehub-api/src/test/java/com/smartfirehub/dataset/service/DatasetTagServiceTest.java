package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetTagService 통합 테스트.
 *
 * <p>addTag, deleteTag, getAllDistinctTags 핵심 메서드 전체 커버.
 * 실제 DB에 데이터셋을 생성하고 태그 CRUD 및 예외 케이스를 검증한다.
 */
@Transactional
class DatasetTagServiceTest extends IntegrationTestBase {

  @Autowired private DatasetTagService datasetTagService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 테스트용 사용자 ID */
  private Long testUserId;

  /** 테스트용 데이터셋 ID */
  private Long datasetId;

  /** 두 번째 테스트용 데이터셋 ID */
  private Long datasetId2;

  // =========================================================================
  // Setup
  // =========================================================================

  /**
   * 각 테스트 전 사용자와 데이터셋을 생성한다.
   * DatasetService.createDataset()을 사용하여 data 스키마 테이블도 함께 생성한다.
   */
  @BeforeEach
  void setUp() {
    // 테스트 사용자 생성
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "tag_test_user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Tag Test User")
            .set(USER.EMAIL, "tag_test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // 첫 번째 테스트용 데이터셋 생성
    DatasetDetailResponse ds1 =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Tag Test Dataset 1",
                "tag_test_ds1",
                null,
                null,
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)),
                null),
            testUserId);
    datasetId = ds1.id();

    // 두 번째 테스트용 데이터셋 생성 (getAllDistinctTags 검증용)
    DatasetDetailResponse ds2 =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Tag Test Dataset 2",
                "tag_test_ds2",
                null,
                null,
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)),
                null),
            testUserId);
    datasetId2 = ds2.id();
  }

  // =========================================================================
  // addTag
  // =========================================================================

  /** 정상: 태그를 추가하면 해당 데이터셋의 태그 목록에 반영되어야 한다. */
  @Test
  void addTag_newTag_appearsInTagList() {
    datasetTagService.addTag(datasetId, "finance", testUserId);

    // DatasetTagRepository.findByDatasetId()를 통해 서비스 레이어에서 검증
    datasetTagService.addTag(datasetId, "etl", testUserId);
    List<String> allTags = datasetTagService.getAllDistinctTags();

    assertThat(allTags).contains("finance", "etl");
  }

  /** 예외: 동일 데이터셋에 같은 태그를 중복 추가하면 IllegalStateException이 발생해야 한다. */
  @Test
  void addTag_duplicateTag_throwsIllegalStateException() {
    datasetTagService.addTag(datasetId, "duplicate", testUserId);

    assertThatThrownBy(() -> datasetTagService.addTag(datasetId, "duplicate", testUserId))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("duplicate");
  }

  /** 예외: 존재하지 않는 데이터셋 ID로 태그 추가 시 DatasetNotFoundException이 발생해야 한다. */
  @Test
  void addTag_nonExistentDataset_throwsDatasetNotFoundException() {
    assertThatThrownBy(() -> datasetTagService.addTag(-999L, "tag", testUserId))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  /** 정상: 같은 태그명이라도 서로 다른 데이터셋에는 추가할 수 있어야 한다. */
  @Test
  void addTag_sameTagOnDifferentDatasets_succeeds() {
    datasetTagService.addTag(datasetId, "shared-tag", testUserId);
    datasetTagService.addTag(datasetId2, "shared-tag", testUserId);

    List<String> allTags = datasetTagService.getAllDistinctTags();
    assertThat(allTags).contains("shared-tag");
  }

  // =========================================================================
  // deleteTag
  // =========================================================================

  /** 정상: 태그를 삭제하면 getAllDistinctTags 결과에서 사라져야 한다 (해당 데이터셋에서만 사용된 경우). */
  @Test
  void deleteTag_existingTag_removedFromDistinctTags() {
    datasetTagService.addTag(datasetId, "to-delete", testUserId);

    datasetTagService.deleteTag(datasetId, "to-delete");

    List<String> allTags = datasetTagService.getAllDistinctTags();
    assertThat(allTags).doesNotContain("to-delete");
  }

  /** 정상: 존재하지 않는 태그 삭제 시 예외 없이 조용히 처리되어야 한다 (idempotent). */
  @Test
  void deleteTag_nonExistentTag_noException() {
    // 존재하지 않는 태그를 삭제해도 예외가 발생하지 않아야 함
    datasetTagService.deleteTag(datasetId, "nonexistent-tag");
  }

  /** 예외: 존재하지 않는 데이터셋 ID로 태그 삭제 시 DatasetNotFoundException이 발생해야 한다. */
  @Test
  void deleteTag_nonExistentDataset_throwsDatasetNotFoundException() {
    assertThatThrownBy(() -> datasetTagService.deleteTag(-999L, "tag"))
        .isInstanceOf(DatasetNotFoundException.class);
  }

  /** 정상: 태그 삭제 후 동일 태그를 다시 추가할 수 있어야 한다 (재추가 가능). */
  @Test
  void deleteTag_thenReAdd_succeeds() {
    datasetTagService.addTag(datasetId, "readdable", testUserId);
    datasetTagService.deleteTag(datasetId, "readdable");

    // 예외 없이 재추가 가능해야 함
    datasetTagService.addTag(datasetId, "readdable", testUserId);
    List<String> allTags = datasetTagService.getAllDistinctTags();
    assertThat(allTags).contains("readdable");
  }

  // =========================================================================
  // getAllDistinctTags
  // =========================================================================

  /** 정상: 태그가 없으면 빈 목록을 반환해야 한다. */
  @Test
  void getAllDistinctTags_noTags_returnsEmpty() {
    List<String> result = datasetTagService.getAllDistinctTags();

    assertThat(result).isEmpty();
  }

  /** 정상: 여러 데이터셋에 걸쳐 중복된 태그는 하나만 반환되어야 한다 (DISTINCT). */
  @Test
  void getAllDistinctTags_duplicateAcrossDatasets_returnsDistinct() {
    datasetTagService.addTag(datasetId, "common", testUserId);
    datasetTagService.addTag(datasetId2, "common", testUserId);
    datasetTagService.addTag(datasetId, "unique1", testUserId);
    datasetTagService.addTag(datasetId2, "unique2", testUserId);

    List<String> result = datasetTagService.getAllDistinctTags();

    // "common"이 중복 없이 1개만 포함되어야 함
    assertThat(result).containsExactlyInAnyOrder("common", "unique1", "unique2");
  }

  /** 정상: 반환된 태그 목록은 알파벳 오름차순으로 정렬되어야 한다. */
  @Test
  void getAllDistinctTags_orderedAlphabetically() {
    datasetTagService.addTag(datasetId, "zebra", testUserId);
    datasetTagService.addTag(datasetId, "apple", testUserId);
    datasetTagService.addTag(datasetId, "mango", testUserId);

    List<String> result = datasetTagService.getAllDistinctTags();

    assertThat(result).containsExactly("apple", "mango", "zebra");
  }
}
