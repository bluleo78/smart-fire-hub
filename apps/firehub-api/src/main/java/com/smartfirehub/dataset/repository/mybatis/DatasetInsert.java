package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.CreateDatasetRequest;

/**
 * dataset INSERT 파라미터 + 생성 키 홀더(WD-707). Tibero 에는 RETURNING 이 없어 JDBC 생성 키(useGeneratedKeys,
 * keyColumn "id")로 id 를 받아 {@link #setId} 로 채운다.
 */
public class DatasetInsert {

  private Long id;
  private final String name;
  private final String tableName;
  private final String description;
  private final Long categoryId;
  private final String storageType;
  private final String originType;
  private final Long createdBy;
  private final Long sourcePipelineStepId;

  public DatasetInsert(CreateDatasetRequest request, Long createdBy) {
    this.name = request.name();
    this.tableName = request.tableName();
    this.description = request.description();
    this.categoryId = request.categoryId();
    this.storageType = request.storageType();
    this.originType = request.originType();
    this.createdBy = createdBy;
    this.sourcePipelineStepId = request.sourcePipelineStepId();
  }

  /** 넣은 뒤 채워진 생성 키. */
  public Long getId() {
    return id;
  }

  /** 생성 키 — 넣은 뒤 MyBatis 가 채운다(Tibero 는 RETURNING 이 없다). */
  public void setId(Long id) {
    this.id = id;
  }

  /** 매퍼 XML 의 #{name} 바인딩용 접근자. */
  public String getName() {
    return name;
  }

  /** 매퍼 XML 의 #{tableName} 바인딩용 접근자. */
  public String getTableName() {
    return tableName;
  }

  /** 매퍼 XML 의 #{description} 바인딩용 접근자. */
  public String getDescription() {
    return description;
  }

  /** 매퍼 XML 의 #{categoryId} 바인딩용 접근자. */
  public Long getCategoryId() {
    return categoryId;
  }

  /** 매퍼 XML 의 #{storageType} 바인딩용 접근자. */
  public String getStorageType() {
    return storageType;
  }

  /** 매퍼 XML 의 #{originType} 바인딩용 접근자. */
  public String getOriginType() {
    return originType;
  }

  /** 매퍼 XML 의 #{createdBy} 바인딩용 접근자. */
  public Long getCreatedBy() {
    return createdBy;
  }

  /** 매퍼 XML 의 #{sourcePipelineStepId} 바인딩용 접근자. */
  public Long getSourcePipelineStepId() {
    return sourcePipelineStepId;
  }
}
