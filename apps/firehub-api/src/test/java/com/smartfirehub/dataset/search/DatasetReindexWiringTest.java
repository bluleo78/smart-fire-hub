package com.smartfirehub.dataset.search;

import static com.smartfirehub.jooq.Tables.USER;
import static org.assertj.core.api.Assertions.assertThat;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.dataset.service.DatasetTagService;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쓰기 경로 → 검색 재인덱싱 이벤트 배선 검증.
 *
 * <p>{@code @RecordApplicationEvents} 로 createDataset/addTag 가 {@link DatasetChangedEvent} 를 발행하는지
 * 결정적으로 확인한다(@Transactional 롤백 환경이라 AFTER_COMMIT 비동기 리스너는 발화하지 않지만, 이벤트 발행 자체는 트랜잭션과 무관하게 기록된다).
 * 리스너가 이벤트를 받아 reindexEmbedding 으로 위임하는 부분은 {@link DatasetReindexListenerTest} 가 단위로 검증한다.
 */
@Transactional
@RecordApplicationEvents
class DatasetReindexWiringTest extends IntegrationTestBase {

  @Autowired private DatasetService datasetService;
  @Autowired private DatasetTagService datasetTagService;
  @Autowired private DSLContext dsl;
  @Autowired private ApplicationEvents events;

  private Long userId;

  @BeforeEach
  void setUp() {
    // 데이터셋 created_by FK 충족용 테스트 사용자
    userId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "reindex-user")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Reindex User")
            .set(USER.EMAIL, "reindex@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();
  }

  @Test
  void 생성과_태그추가가_각각_DatasetChangedEvent_를_발행한다() {
    // Given: 단순 컬럼 1개 데이터셋
    CreateDatasetRequest request =
        new CreateDatasetRequest(
            "Reindex DS",
            "reindex_ds",
            "desc",
            null,
            "TABLE",
            "SOURCE",
            List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", null, false, true, "name col")),
            null);

    // When: 메타 생성 → 태그 추가 (둘 다 reindexSearch 를 호출)
    DatasetDetailResponse created = datasetService.createDataset(request, userId);
    datasetTagService.addTag(created.id(), "fire", userId);

    // Then: 두 쓰기 경로 모두 해당 datasetId 로 변경 이벤트를 발행했다.
    long createdId = created.id();
    List<Long> changedIds =
        events.stream(DatasetChangedEvent.class).map(DatasetChangedEvent::datasetId).toList();
    assertThat(changedIds)
        .filteredOn(id -> id == createdId)
        .as("createDataset + addTag 가 각각 변경 이벤트를 발행해야 한다")
        .hasSizeGreaterThanOrEqualTo(2);
  }
}
