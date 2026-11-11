package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetColumnResponse;
import com.smartfirehub.dataset.dto.DatasetColumnSnapshot;
import com.smartfirehub.dataset.dto.UpdateColumnRequest;
import com.smartfirehub.dataset.repository.DatasetColumnRepository;
import com.smartfirehub.global.platformdb.mybatis.InChunks;
import com.smartfirehub.global.platformdb.mybatis.PlatformMyBatisRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * DatasetColumnRepository 의 MyBatis 구현(WD-707) — {@code JooqDatasetColumnRepository} 와 같은 의미.
 *
 * <p>클래스 레벨 {@code @Transactional} 은 jOOQ 구현 그대로다 — 배경 잡(JobRunr/@Async/@Scheduled)은 앰비언트 트랜잭션이 없고
 * RLS 테넌트 컨텍스트는 트랜잭션이 열릴 때 심기므로, 트랜잭션이 없으면 정책이 전 행을 막는다(REQUIRED 라 서비스 트랜잭션에는 합류). {@code
 * snapshotByDatasetId}/{@code restoreSnapshot} 도 이 인터페이스에 있어 함께 구현한다. 빈 문자열은 변환하지 않는다 — Tibero 는
 * {@code ''} 를 NULL 로 저장해 NULL 허용 열(display_name·description)은 null 로 돌아온다.
 */
@PlatformMyBatisRepository
@Transactional
public class MyBatisDatasetColumnRepository implements DatasetColumnRepository {

  private final DatasetColumnMapper mapper;

  public MyBatisDatasetColumnRepository(DatasetColumnMapper mapper) {
    this.mapper = mapper;
  }

  /** 데이터셋의 컬럼 전부를 열 순서(column_order, id)대로 돌려준다. */
  @Override
  public List<DatasetColumnResponse> findByDatasetId(Long datasetId) {
    return mapper.selectByDatasetId(datasetId);
  }

  /** 넣고 생성 키로 다시 읽어 돌려준다(Tibero 는 RETURNING 이 없다). */
  @Override
  public DatasetColumnResponse save(Long datasetId, DatasetColumnRequest request, int columnOrder) {
    ColumnInsert row = new ColumnInsert(datasetId, request, columnOrder);
    mapper.insert(row);
    return mapper.selectById(row.getId());
  }

  /** 요청 순서대로 0 부터 열 순서를 매겨 한 건씩 넣는다. */
  @Override
  public void saveBatch(Long datasetId, List<DatasetColumnRequest> columns) {
    for (int i = 0; i < columns.size(); i++) {
      mapper.insert(new ColumnInsert(datasetId, columns.get(i), i));
    }
  }

  /** id 로 컬럼을 찾는다. 없으면 빈 값. */
  @Override
  public Optional<DatasetColumnResponse> findById(Long id) {
    return Optional.ofNullable(mapper.selectById(id));
  }

  /**
   * 부분 수정(PUT) 의미를 유지하려고 null 필드는 기존 값을 보존한다(is_indexed 는 NOT NULL 이라 null 을 넣으면 거절된다, #222). 데이터
   * 타입이 오고 VARCHAR 가 아니면 max_length 를 NULL 로 비운다(VARCHAR→TEXT 등).
   */
  @Override
  public void update(Long id, UpdateColumnRequest request) {
    boolean clearMaxLength = request.dataType() != null && !"VARCHAR".equals(request.dataType());
    mapper.update(
        id,
        request.columnName(),
        request.displayName(),
        request.dataType(),
        request.maxLength(),
        clearMaxLength,
        request.isNullable(),
        request.isIndexed(),
        request.description(),
        request.isPrimaryKey());
  }

  /** 컬럼이 없으면 -1. */
  @Override
  public int getMaxOrder(Long datasetId) {
    Integer maxOrder = mapper.selectMaxOrder(datasetId);
    return maxOrder != null ? maxOrder : -1;
  }

  /** 데이터셋의 컬럼을 모두 지운다. */
  @Override
  public void deleteByDatasetId(Long datasetId) {
    mapper.deleteByDatasetId(datasetId);
  }

  /** 되돌리기용 보관본 — 컬럼 전부를 id 까지 열 순서대로 읽는다(WD-238). */
  @Override
  public List<DatasetColumnSnapshot> snapshotByDatasetId(Long datasetId) {
    return mapper.selectSnapshotByDatasetId(datasetId);
  }

  /** 현재 행을 지우고 보관 행을 id 까지 그대로 다시 넣는다(WD-238). tenant_id 는 정책 기본값이 채운다 — 보관본은 같은 테넌트에서 읽은 행이다. */
  @Override
  public void restoreSnapshot(Long datasetId, List<DatasetColumnSnapshot> snapshot) {
    deleteByDatasetId(datasetId);
    for (DatasetColumnSnapshot row : snapshot) {
      mapper.insertSnapshotRow(
          row.id(),
          row.datasetId(),
          row.columnName(),
          row.displayName(),
          row.dataType(),
          row.isNullable(),
          row.isIndexed(),
          row.description(),
          row.columnOrder(),
          row.maxLength(),
          row.isPrimaryKey(),
          row.isSearchable());
    }
  }

  /** 같은 데이터셋에 같은 이름의 컬럼이 있는지(excludeColumnId 는 빼고 — 수정 시 자기 자신 제외). */
  @Override
  public boolean existsByDatasetIdAndColumnName(
      Long datasetId, String columnName, Long excludeColumnId) {
    return mapper.countByDatasetIdAndName(datasetId, columnName, excludeColumnId) > 0;
  }

  /** 컬럼이 속한 데이터셋 id. 컬럼이 없으면 빈 값. */
  @Override
  public Optional<Long> findDatasetIdByColumnId(Long columnId) {
    return Optional.ofNullable(mapper.selectDatasetIdByColumnId(columnId));
  }

  /** 컬럼 한 건을 지운다. */
  @Override
  public void deleteById(Long id) {
    mapper.deleteById(id);
  }

  /** 먼저 데이터셋의 모든 컬럼 PK 를 내리고, 지정한 컬럼만 올린다. 목록이 1000 개를 넘어도 터지지 않게 500 개씩 나눈다. */
  @Override
  public void updatePrimaryKeys(Long datasetId, List<Long> pkColumnIds) {
    mapper.clearPrimaryKeys(datasetId);
    if (pkColumnIds == null || pkColumnIds.isEmpty()) {
      return;
    }
    mapper.markPrimaryKeys(datasetId, InChunks.of(pkColumnIds));
  }

  /** 요청 목록 순서대로 0 부터 열 순서를 다시 매긴다(한 건씩 갱신). */
  @Override
  public void updateOrders(Long datasetId, List<Long> columnIds) {
    for (int i = 0; i < columnIds.size(); i++) {
      mapper.updateOrder(datasetId, columnIds.get(i), i);
    }
  }

  /** 다른 데이터셋에서 같은 이름 컬럼의 비어 있지 않은 첫 설명(id 순) — 설명 자동 채움용. 없으면 null. */
  @Override
  public String findFirstDescriptionByColumnName(String columnName, Long excludeDatasetId) {
    return mapper.selectFirstDescriptionByColumnName(columnName, excludeDatasetId);
  }

  /** 컬럼 설명만 바꾼다. */
  @Override
  public void updateDescription(Long columnId, String description) {
    mapper.updateDescription(columnId, description);
  }
}
