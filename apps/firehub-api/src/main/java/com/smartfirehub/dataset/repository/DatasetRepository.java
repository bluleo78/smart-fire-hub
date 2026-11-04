package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetResponse;
import com.smartfirehub.dataset.dto.UpdateDatasetRequest;
import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 데이터셋 정의(dataset) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetRepository {

  /** 필터(카테고리·저장유형·출처·검색어·상태·즐겨찾기·보안등급)와 열람 가시성을 적용해 데이터셋 목록을 페이지 단위로 조회한다. */
  List<DatasetResponse> findAll(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      int page,
      int size,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId,
      DatasetVisibility access);

  /** {@link #findAll} 과 같은 필터·가시성을 적용한 전체 건수를 센다. */
  long count(
      Long categoryId,
      String storageType,
      String originType,
      String search,
      Long currentUserId,
      String status,
      boolean favoriteOnly,
      Long securityLevelId,
      DatasetVisibility access);

  /**
   * WD-186: 필드 이름 변경·타입 변경·삭제가 잡는 데이터셋 행 잠금. 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE 로
   * 잡고 나서 필드 목록을 읽는다)와 직렬화해, "옛 필드 목록으로 검증한 매핑이 필드 변경 뒤에 저장되는" 틈을 없앤다. NO KEY UPDATE 인 이유: FOR
   * SHARE 와는 충돌하면서도, 이 데이터셋을 FK 로 참조하는 단순 삽입(KEY SHARE)까지 막지는 않는다. 잠금 순서(교착 방지): 온톨로지 행 → 데이터셋 행 →
   * 매핑 행. MappingReferenceService.lockForColumnChange 참고.
   */
  void lockForColumnChange(Long id);

  /**
   * WD-184: 지식 모델 연결·재연결(DatasetOntologyService.bind)이 잡는 데이터셋 행 잠금. 재연결은 "이전 연결 읽기 → 연결 갱신 → 옛 모델
   * 기준 매핑 삭제"를 한 덩어리로 해야 하는데, 매핑 저장·활성화(MappingService.validate — 이 행을 FOR SHARE 로 잡는다)와 직렬화하지 않으면
   * 옛 모델로 검증한 매핑이 재연결 뒤에 저장돼 고아 매핑이 다시 생긴다. NO KEY UPDATE 인 이유는 lockForColumnChange 와 같다. 잠금 순서:
   * 데이터셋 행 → 매핑 행(온톨로지 행은 잡지 않는다) — 온톨로지 행 → 데이터셋 행 → 매핑 행 규약의 부분 순서다.
   */
  void lockForOntologyRebind(Long id);

  /**
   * WD-186: 매핑 저장·활성화가 필드 목록을 읽기 전에 잡는 공유 잠금 — 진행 중인 필드 변경(lockForColumnChange)이 끝난 뒤의 필드 목록으로 검증하게
   * 한다. 매핑 저장끼리는 서로 막지 않는다(SHARE 는 서로 호환).
   */
  void lockForShare(Long id);

  /** id 로 데이터셋 한 건을 조회한다(즐겨찾기 여부는 채우지 않는다). 없으면 빈 값. */
  Optional<DatasetResponse> findById(Long id);

  /** id 로 데이터셋 한 건을 조회하며 현재 사용자의 즐겨찾기 여부를 함께 채운다. 없으면 빈 값. */
  Optional<DatasetResponse> findById(Long id, Long currentUserId);

  /** 데이터셋 정의를 새로 저장하고 저장된 행을 돌려준다. */
  DatasetResponse save(CreateDatasetRequest request, Long createdBy);

  /** 파이프라인 스텝이 만든 데이터셋의 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findBySourcePipelineStepId(Long stepId);

  /** 데이터셋의 이름·설명·카테고리·태그 등 변경 가능한 항목을 갱신한다. */
  void update(Long id, UpdateDatasetRequest request, Long updatedBy);

  /** 데이터셋의 이름·설명·카테고리·태그 등 변경 가능한 항목을 갱신한다. */
  void updateStatus(Long id, String status, String statusNote, Long statusUpdatedBy);

  /** id 로 데이터셋 정의를 삭제한다. */
  void deleteById(Long id);

  /**
   * 조회자가 볼 수 있는 데이터셋 가운데 같은 이름이 있는가(WD-155). 이름 유일 범위는 「볼 수 있는 데이터셋」이다 — 볼 수 없는(상위 등급) 데이터셋과 겹치는지까지
   * 보면 이름을 맞혀 보는 것만으로 존재가 드러난다. DB 유일 제약도 없다(V142).
   *
   * @param visible 조회자 가시성({@code DatasetAccessGuard.visibility})
   * @param excludeId 이름 변경 시 자기 자신(없으면 null)
   */
  boolean existsVisibleByName(String name, DatasetVisibility visible, Long excludeId);

  /** 같은 물리 테이블 이름을 쓰는 데이터셋이 있는지 확인한다. */
  boolean existsByTableName(String tableName);

  /** 데이터셋의 물리 테이블 이름을 조회한다. 없으면 빈 값. */
  Optional<String> findTableNameById(Long id);

  /** 데이터셋 생성자 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findCreatedByById(Long id);

  /** 데이터셋의 마지막 수정 시각을 조회한다. 없으면 빈 값. */
  Optional<LocalDateTime> findUpdatedAtById(Long id);

  /** 데이터셋의 마지막 수정자 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findUpdatedByById(Long id);
}
