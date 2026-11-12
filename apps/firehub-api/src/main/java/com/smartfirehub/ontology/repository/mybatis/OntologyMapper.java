package com.smartfirehub.ontology.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.EntityTypeInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.EntityTypeRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.HeadRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.OntologyInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.PropertyInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.PropertyRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.RelationInsert;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.RelationRow;
import com.smartfirehub.ontology.repository.mybatis.OntologyRows.SummaryRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 온톨로지(ontology 와 하위 타입·속성·관계) MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/ontology/OntologyMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 거르고 tenant_id 는 열 DEFAULT 가
 * 채운다.
 */
@PlatformMapper
public interface OntologyMapper {

  /** 온톨로지 머리 — 없으면 null. */
  HeadRow selectHead(@Param("ontologyId") long ontologyId);

  /** 이 온톨로지의 엔티티 타입(sort_order 순). */
  List<EntityTypeRow> selectEntityTypes(@Param("ontologyId") long ontologyId);

  /** 이 온톨로지의 모든 타입의 속성((타입 id, sort_order) 순) — 타입당 조회(N+1)를 피한다. */
  List<PropertyRow> selectProperties(@Param("ontologyId") long ontologyId);

  /** 이 온톨로지의 관계(sort_order 순). */
  List<RelationRow> selectRelations(@Param("ontologyId") long ontologyId);

  /** 목록 요약(id 순). status 가 null 이면 전체. */
  List<SummaryRow> selectSummaries(@Param("status") String status);

  /** archived 가 아닌 같은 도메인 행 수. */
  int countLiveDomain(@Param("domain") String domain);

  /** 상태 — 없으면 null. */
  String selectStatus(@Param("ontologyId") long ontologyId);

  /** 행을 잠그고 상태를 읽는다(PG FOR NO KEY UPDATE, Tibero FOR UPDATE) — 없으면 null. */
  String lockAndSelectStatus(@Param("ontologyId") long ontologyId);

  /** 행을 공유 잠금한다(PG FOR SHARE, Tibero FOR UPDATE). 없는 id 는 빈 결과. */
  List<Long> lockForShare(@Param("ontologyId") long ontologyId);

  int updateStatus(@Param("ontologyId") long ontologyId, @Param("status") String status);

  /** 바인딩 ∪ 매핑의 서로 다른 데이터셋 수. */
  int countReferences(@Param("ontologyId") long ontologyId);

  int deleteOntology(@Param("ontologyId") long ontologyId);

  int countById(@Param("ontologyId") long ontologyId);

  /** schema_version — 없으면 null. */
  Integer selectSchemaVersion(@Param("ontologyId") long ontologyId);

  /** 온톨로지 행을 넣고 생성 키를 홀더에 돌려준다. */
  int insertOntology(OntologyInsert row);

  int insertEntityType(EntityTypeInsert row);

  int insertProperty(PropertyInsert row);

  int insertRelation(RelationInsert row);
}
