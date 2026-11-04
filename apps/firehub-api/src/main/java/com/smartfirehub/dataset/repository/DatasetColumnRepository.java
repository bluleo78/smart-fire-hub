package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetColumnSnapshot;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import java.util.List;
import java.util.Optional;

/**
 * 데이터셋 컬럼 정의(dataset_column) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetColumnRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetColumnRepository {

  /** 데이터셋의 컬럼 목록을 열 순서대로 조회한다. */
  List<DatasetColumnResponse> findByDatasetId(Long datasetId);

  /** 컬럼 한 건을 지정한 열 순서로 저장하고 저장된 행을 돌려준다. */
  DatasetColumnResponse save(Long datasetId, DatasetColumnRequest request, int columnOrder);

  /** 컬럼 여러 건을 요청 순서대로 한꺼번에 저장한다. */
  void saveBatch(Long datasetId, List<DatasetColumnRequest> columns);

  /** id 로 컬럼 한 건을 조회한다. 없으면 빈 값. */
  Optional<DatasetColumnResponse> findById(Long id);

  /** 컬럼 한 건의 변경 가능한 항목을 갱신한다. */
  void update(Long id, UpdateColumnRequest request);

  /** 데이터셋 컬럼의 현재 최대 열 순서(컬럼이 없으면 -1)를 돌려준다. */
  int getMaxOrder(Long datasetId);

  /** 데이터셋의 컬럼을 모두 삭제한다. */
  void deleteByDatasetId(Long datasetId);

  /**
   * 데이터셋 컬럼 메타 행 전체(모든 열 — id·is_searchable·tenant_id 포함)를 그대로 읽는다(WD-238). 응답 DTO 는 일부 열만 담아, 실패한
   * TEMP 재생성을 되돌릴 때 검색 대상 설정·컬럼 id 를 잃지 않도록 행 그대로 보관한다.
   *
   * <p>조회문은 예전 그대로({@code SELECT *}) 두고, 서비스가 jOOQ Record 를 다루지 않도록 {@link DatasetColumnSnapshot}
   * 으로 옮겨 돌려준다(WD-485). 이름으로 꺼내며 타입을 맞춘다 — DB 가 숫자를 다른 자바 타입(예: BigDecimal)으로 줘도 같은 값이 된다.
   */
  List<DatasetColumnSnapshot> snapshotByDatasetId(Long datasetId);

  /**
   * {@link #snapshotByDatasetId} 로 보관한 행으로 데이터셋 컬럼 메타를 되돌린다 — 현재 행을 지우고 보관 행을 id 까지 그대로 다시
   * 넣는다(WD-238). 열 순서는 테이블 정의 순서(예전 {@code SELECT *} 행의 열 순서)와 같다.
   */
  void restoreSnapshot(Long datasetId, List<DatasetColumnSnapshot> snapshot);

  /**
   * 같은 데이터셋에 이 이름의 필드가 있는지 — 필드명 중복 사전 검사(WD-99). {@code excludeColumnId} 는 비교에서 뺄 필드(이름 변경 시 자기
   * 자신)이며 null 이면 모두 비교한다. 목록 전체를 읽지 않도록 EXISTS 한 번으로 묻는다.
   */
  boolean existsByDatasetIdAndColumnName(Long datasetId, String columnName, Long excludeColumnId);

  /** 컬럼 id 가 속한 데이터셋 id 를 조회한다. 없으면 빈 값. */
  Optional<Long> findDatasetIdByColumnId(Long columnId);

  /** id 로 컬럼 한 건을 삭제한다. */
  void deleteById(Long id);

  /**
   * 데이터셋의 모든 컬럼의 PK 플래그를 한 번에 갱신한다. {@code pkColumnIds} 에 포함된 컬럼은 true, 나머지는 false 로 설정한다. 복합 PK 일괄
   * 변경 시 트랜잭션 안에서 호출되어야 한다.
   */
  void updatePrimaryKeys(Long datasetId, List<Long> pkColumnIds);

  /** 컬럼 한 건의 변경 가능한 항목을 갱신한다. */
  void updateOrders(Long datasetId, List<Long> columnIds);

  /** 다른 데이터셋에서 같은 이름의 컬럼에 적힌 첫 설명을 찾는다(설명 자동 채움용). 없으면 null. */
  String findFirstDescriptionByColumnName(String columnName, Long excludeDatasetId);

  /** 컬럼 한 건의 변경 가능한 항목을 갱신한다. */
  void updateDescription(Long columnId, String description);
}
