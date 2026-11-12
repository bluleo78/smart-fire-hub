package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.GeneratedId;
import java.time.Instant;

/**
 * 온톨로지 MyBatis 매퍼(OntologyMapper·OntologyElementMapper)의 평평한 행·INSERT 홀더 모음(WD-711).
 *
 * <p>행은 SQL 결과를 그대로 담고, 중첩 DTO(OntologyResponse) 조립과 빈 문자열 보정은 리포지토리가 자바에서 한다 — 결과 매핑 안에서 {@code
 * NVL(col, '')} 로 보정하면 Tibero 에서 {@code ''} 가 다시 NULL 이 되기 때문이다.
 */
public final class OntologyRows {

  private OntologyRows() {}

  /** 온톨로지 머리(도메인·스키마 버전) — findById 의 첫 조회. */
  public record HeadRow(String domain, int schemaVersion) {}

  /** 엔티티 타입 한 줄(sort_order 순). */
  public record EntityTypeRow(
      long id, String type, String description, String naming, String resolution) {}

  /** 속성 한 줄 — 리포지토리가 타입 id 로 묶는다((타입, sort_order) 순). */
  public record PropertyRow(
      long entityTypeId, String name, String description, String dataType, String unit, long id) {}

  /** 관계 한 줄(sort_order 순) — 끝점은 타입 id, 이름은 리포지토리가 되붙인다. */
  public record RelationRow(
      long id, long subjectTypeId, String relation, long objectTypeId, String description) {}

  /** 목록 요약 한 줄 — updated_at 은 UTC 순간(리포지토리가 UTC OffsetDateTime 으로 바꾼다). */
  public record SummaryRow(
      long id,
      String domain,
      int schemaVersion,
      String status,
      int entityCount,
      int datasetCount,
      Instant updatedAt) {}

  /** ontology INSERT 파라미터(schema_version 1, updated_at 은 DB 시계). */
  public static class OntologyInsert extends GeneratedId {
    private final String domain;
    private final String status;

    public OntologyInsert(String domain, String status) {
      this.domain = domain;
      this.status = status;
    }

    public String getDomain() {
      return domain;
    }

    public String getStatus() {
      return status;
    }
  }

  /** ontology_entity_type INSERT 파라미터. */
  public static class EntityTypeInsert extends GeneratedId {
    private final long ontologyId;
    private final String type;
    private final String description;
    private final String naming;
    private final String resolution;
    private final int sortOrder;

    public EntityTypeInsert(
        long ontologyId,
        String type,
        String description,
        String naming,
        String resolution,
        int sortOrder) {
      this.ontologyId = ontologyId;
      this.type = type;
      this.description = description;
      this.naming = naming;
      this.resolution = resolution;
      this.sortOrder = sortOrder;
    }

    public long getOntologyId() {
      return ontologyId;
    }

    public String getType() {
      return type;
    }

    public String getDescription() {
      return description;
    }

    public String getNaming() {
      return naming;
    }

    public String getResolution() {
      return resolution;
    }

    public int getSortOrder() {
      return sortOrder;
    }
  }

  /** ontology_entity_property INSERT 파라미터. */
  public static class PropertyInsert extends GeneratedId {
    private final long entityTypeId;
    private final String name;
    private final String description;
    private final String dataType;
    private final String unit;
    private final int sortOrder;

    public PropertyInsert(
        long entityTypeId,
        String name,
        String description,
        String dataType,
        String unit,
        int sortOrder) {
      this.entityTypeId = entityTypeId;
      this.name = name;
      this.description = description;
      this.dataType = dataType;
      this.unit = unit;
      this.sortOrder = sortOrder;
    }

    public long getEntityTypeId() {
      return entityTypeId;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }

    public String getDataType() {
      return dataType;
    }

    public String getUnit() {
      return unit;
    }

    public int getSortOrder() {
      return sortOrder;
    }
  }

  /** ontology_relation INSERT 파라미터. */
  public static class RelationInsert extends GeneratedId {
    private final long ontologyId;
    private final long subjectTypeId;
    private final String relation;
    private final long objectTypeId;
    private final String description;
    private final int sortOrder;

    public RelationInsert(
        long ontologyId,
        long subjectTypeId,
        String relation,
        long objectTypeId,
        String description,
        int sortOrder) {
      this.ontologyId = ontologyId;
      this.subjectTypeId = subjectTypeId;
      this.relation = relation;
      this.objectTypeId = objectTypeId;
      this.description = description;
      this.sortOrder = sortOrder;
    }

    public long getOntologyId() {
      return ontologyId;
    }

    public long getSubjectTypeId() {
      return subjectTypeId;
    }

    public String getRelation() {
      return relation;
    }

    public long getObjectTypeId() {
      return objectTypeId;
    }

    public String getDescription() {
      return description;
    }

    public int getSortOrder() {
      return sortOrder;
    }
  }
}
