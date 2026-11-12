package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 요소 단위 온톨로지 쓰기 MyBatis 매퍼(WD-711). SQL 은 {@code mybatis/ontology/OntologyElementMapper.xml}. 문장
 * 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 거르고 tenant_id 는 열 DEFAULT 가 채운다.
 *
 * <p>PATCH 갱신 문장은 null 인 인자를 "변경 없음"으로 보고 SET 에서 뺀다. 리포지토리가 전부 null 인 경우를 먼저 걸러 빈 SET 문법 오류가 나지 않게
 * 한다. 단위는 "NULL 로 지우기"가 있어 별도 플래그({@code unitSet})로 바꿀지 여부를 받는다.
 */
@PlatformMapper
public interface OntologyElementMapper {

  /** schema_version 을 1 올리고 수정 시각을 DB 시계로 쓴다 — 바뀐 행 수. */
  int bumpVersion(@Param("ontologyId") long ontologyId);

  int updateDomain(@Param("ontologyId") long ontologyId, @Param("domain") String domain);

  List<String> selectTypeNames(@Param("ontologyId") long ontologyId);

  int countEntityTypes(@Param("ontologyId") long ontologyId);

  /** 이 온톨로지에 속한 타입이면 이름, 아니면 null. */
  String selectTypeName(
      @Param("ontologyId") long ontologyId, @Param("entityTypeId") long entityTypeId);

  /** 이 온톨로지 타입의 최대 sort_order — 타입이 없으면 null. */
  Integer selectMaxTypeOrder(@Param("ontologyId") long ontologyId);

  int updateEntityType(
      @Param("entityTypeId") long entityTypeId,
      @Param("type") String type,
      @Param("description") String description,
      @Param("naming") String naming,
      @Param("resolution") String resolution);

  List<Long> selectRelationIdsTouching(@Param("entityTypeId") long entityTypeId);

  int deleteEntityType(@Param("entityTypeId") long entityTypeId);

  List<String> selectPropertyNames(@Param("entityTypeId") long entityTypeId);

  /** 이 타입에 속한 속성이면 이름, 아니면 null. */
  String selectPropertyName(
      @Param("entityTypeId") long entityTypeId, @Param("propertyId") long propertyId);

  /** 이 타입 속성의 최대 sort_order — 속성이 없으면 null. */
  Integer selectMaxPropertyOrder(@Param("entityTypeId") long entityTypeId);

  /**
   * @param unitSet true 면 unit 을 바꾼다(null 이면 NULL 로 지우기), false 면 그대로 둔다
   */
  int updateProperty(
      @Param("propertyId") long propertyId,
      @Param("name") String name,
      @Param("description") String description,
      @Param("dataType") String dataType,
      @Param("unitSet") boolean unitSet,
      @Param("unit") String unit);

  int deleteProperty(@Param("propertyId") long propertyId);

  int countTriple(
      @Param("ontologyId") long ontologyId,
      @Param("subjectTypeId") long subjectTypeId,
      @Param("relation") String relation,
      @Param("objectTypeId") long objectTypeId);

  /** 이 온톨로지 관계의 최대 sort_order — 관계가 없으면 null. */
  Integer selectMaxRelationOrder(@Param("ontologyId") long ontologyId);

  int updateRelation(
      @Param("relationId") long relationId,
      @Param("relation") String relation,
      @Param("description") String description);

  int deleteRelation(@Param("relationId") long relationId);
}
