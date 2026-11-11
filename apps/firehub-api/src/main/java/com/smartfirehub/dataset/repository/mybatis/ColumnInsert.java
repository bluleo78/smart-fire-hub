package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.DatasetColumnRequest;

/**
 * 데이터셋 컬럼 INSERT 파라미터 겸 생성 키 홀더(WD-707). {@code useGeneratedKeys} 가 생성된 id 를 이 객체의 {@code id} 에 써
 * 넣는다 — RETURNING 이 없는 Tibero 도 JDBC 표준 경로로 같은 방식이다.
 */
public class ColumnInsert {

  private final Long datasetId;
  private final DatasetColumnRequest request;
  private final int columnOrder;

  private Long id;

  public ColumnInsert(Long datasetId, DatasetColumnRequest request, int columnOrder) {
    this.datasetId = datasetId;
    this.request = request;
    this.columnOrder = columnOrder;
  }

  /** 매퍼 XML 의 #{datasetId} 바인딩용 접근자. */
  public Long getDatasetId() {
    return datasetId;
  }

  /** 매퍼 XML 의 #{columnName} 바인딩용 접근자. */
  public String getColumnName() {
    return request.columnName();
  }

  /** 매퍼 XML 의 #{displayName} 바인딩용 접근자. */
  public String getDisplayName() {
    return request.displayName();
  }

  /** 매퍼 XML 의 #{dataType} 바인딩용 접근자. */
  public String getDataType() {
    return request.dataType();
  }

  /** 매퍼 XML 의 #{maxLength} 바인딩용 접근자. */
  public Integer getMaxLength() {
    return request.maxLength();
  }

  /** 매퍼 XML 의 #{isNullable} 바인딩용 접근자. */
  public Boolean getIsNullable() {
    return request.isNullable();
  }

  /** 매퍼 XML 의 #{isIndexed} 바인딩용 접근자. */
  public Boolean getIsIndexed() {
    return request.isIndexed();
  }

  /** 매퍼 XML 의 #{description} 바인딩용 접근자. */
  public String getDescription() {
    return request.description();
  }

  /** 매퍼 XML 의 #{isPrimaryKey} 바인딩용 접근자. */
  public Boolean getIsPrimaryKey() {
    return request.isPrimaryKey();
  }

  /** 매퍼 XML 의 #{columnOrder} 바인딩용 접근자. */
  public int getColumnOrder() {
    return columnOrder;
  }

  /** 넣은 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }

  /** 생성 키 — 넣은 뒤 MyBatis 가 채운다(Tibero 는 RETURNING 이 없다). */
  public void setId(Long id) {
    this.id = id;
  }
}
