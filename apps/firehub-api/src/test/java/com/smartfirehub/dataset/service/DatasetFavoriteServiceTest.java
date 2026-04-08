package com.smartfirehub.dataset.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.dto.FavoriteToggleResponse;
import com.smartfirehub.dataset.exception.DatasetNotFoundException;
import com.smartfirehub.support.IntegrationTestBase;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetFavoriteService 통합 테스트.
 *
 * <p>toggleFavorite 핵심 메서드 전체 커버.
 * 즐겨찾기 추가/해제 토글 로직, 멱등성, 예외 케이스를 실제 DB에서 검증한다.
 */
@Transactional
class DatasetFavoriteServiceTest extends IntegrationTestBase {

  @Autowired private DatasetFavoriteService datasetFavoriteService;
  @Autowired private DatasetService datasetService;
  @Autowired private DSLContext dsl;

  /** 첫 번째 테스트 사용자 ID */
  private Long userId1;

  /** 두 번째 테스트 사용자 ID */
  private Long userId2;

  /** 테스트용 데이터셋 ID */
  private Long datasetId;

  // =========================================================================
  // Setup
  // =========================================================================

  /**
   * 각 테스트 전 사용자 2명과 데이터셋 1개를 생성한다.
   * DatasetService.createDataset()을 사용하여 data 스키마 테이블도 함께 생성한다.
   */
  @BeforeEach
  void setUp() {
    // 첫 번째 테스트 사용자 생성
    userId1 =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "fav_user1")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Fav User 1")
            .set(USER.EMAIL, "fav_user1@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // 두 번째 테스트 사용자 생성
    userId2 =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "fav_user2")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Fav User 2")
            .set(USER.EMAIL, "fav_user2@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // 테스트용 데이터셋 생성
    DatasetDetailResponse ds =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Favorite Test Dataset",
                "fav_test_ds",
                null,
                null,
                "SOURCE",
                List.of(
                    new DatasetColumnRequest("name", "Name", "TEXT", null, true, false, null)),
                null),
            userId1);
    datasetId = ds.id();
  }

  // =========================================================================
  // toggleFavorite — 추가
  // =========================================================================

  /** 정상: 즐겨찾기가 없는 상태에서 toggleFavorite 호출 시 favorited=true를 반환해야 한다. */
  @Test
  void toggleFavorite_notFavorited_returnsFavoritedTrue() {
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(datasetId, userId1);

    assertThat(response.favorited()).isTrue();
  }

  /** 정상: 즐겨찾기 추가 후 다시 toggleFavorite 호출 시 favorited=false를 반환해야 한다 (토글). */
  @Test
  void toggleFavorite_alreadyFavorited_returnsFavoritedFalse() {
    // 먼저 추가
    datasetFavoriteService.toggleFavorite(datasetId, userId1);

    // 다시 호출하면 해제
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(datasetId, userId1);

    assertThat(response.favorited()).isFalse();
  }

  /** 정상: 토글을 짝수 번 반복하면 즐겨찾기가 해제 상태여야 한다. */
  @Test
  void toggleFavorite_evenNumberOfToggles_notFavorited() {
    datasetFavoriteService.toggleFavorite(datasetId, userId1); // 추가 (true)
    datasetFavoriteService.toggleFavorite(datasetId, userId1); // 해제 (false)
    datasetFavoriteService.toggleFavorite(datasetId, userId1); // 추가 (true)
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(datasetId, userId1); // 해제 (false)

    assertThat(response.favorited()).isFalse();
  }

  /** 정상: 토글을 홀수 번 반복하면 즐겨찾기가 추가 상태여야 한다. */
  @Test
  void toggleFavorite_oddNumberOfToggles_favorited() {
    datasetFavoriteService.toggleFavorite(datasetId, userId1); // 추가 (true)
    datasetFavoriteService.toggleFavorite(datasetId, userId1); // 해제 (false)
    FavoriteToggleResponse response = datasetFavoriteService.toggleFavorite(datasetId, userId1); // 추가 (true)

    assertThat(response.favorited()).isTrue();
  }

  // =========================================================================
  // toggleFavorite — 사용자 격리
  // =========================================================================

  /**
   * 정상: 한 사용자의 즐겨찾기가 다른 사용자에게 영향을 주지 않아야 한다.
   * userId1이 즐겨찾기를 추가해도 userId2의 즐겨찾기 상태는 독립적이다.
   */
  @Test
  void toggleFavorite_differentUsers_independentStates() {
    // userId1은 즐겨찾기 추가
    FavoriteToggleResponse user1Response = datasetFavoriteService.toggleFavorite(datasetId, userId1);
    assertThat(user1Response.favorited()).isTrue();

    // userId2는 아직 즐겨찾기 추가 안 했으므로 true 반환
    FavoriteToggleResponse user2Response = datasetFavoriteService.toggleFavorite(datasetId, userId2);
    assertThat(user2Response.favorited()).isTrue();
  }

  /**
   * 정상: userId1이 즐겨찾기를 해제해도 userId2의 즐겨찾기는 유지되어야 한다.
   */
  @Test
  void toggleFavorite_user1Removes_user2Unaffected() {
    // 두 사용자 모두 즐겨찾기 추가
    datasetFavoriteService.toggleFavorite(datasetId, userId1);
    datasetFavoriteService.toggleFavorite(datasetId, userId2);

    // userId1 해제
    FavoriteToggleResponse user1Remove = datasetFavoriteService.toggleFavorite(datasetId, userId1);
    assertThat(user1Remove.favorited()).isFalse();

    // userId2는 여전히 즐겨찾기 상태 → 토글 시 false 반환 (즐겨찾기가 있어서 해제)
    FavoriteToggleResponse user2Toggle = datasetFavoriteService.toggleFavorite(datasetId, userId2);
    assertThat(user2Toggle.favorited()).isFalse();
  }

  // =========================================================================
  // toggleFavorite — 예외
  // =========================================================================

  /** 예외: 존재하지 않는 데이터셋 ID로 toggleFavorite 호출 시 DatasetNotFoundException이 발생해야 한다. */
  @Test
  void toggleFavorite_nonExistentDataset_throwsDatasetNotFoundException() {
    assertThatThrownBy(() -> datasetFavoriteService.toggleFavorite(-999L, userId1))
        .isInstanceOf(DatasetNotFoundException.class);
  }
}
