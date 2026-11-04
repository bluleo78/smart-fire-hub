package com.smartfirehub.ontology.repository;

import com.smartfirehub.ontology.dto.CreateOntologyRequest;
import com.smartfirehub.ontology.dto.OntologyResponse;
import com.smartfirehub.ontology.dto.OntologySummary;
import java.util.List;

/**
 * 온톨로지(ontology 테이블)와 엔티티
 * 타입·속성·관계(ontology_entity_type·ontology_entity_property·ontology_relation)·데이터셋
 * 연결(dataset_ontology·dataset_mapping) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqOntologyRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface OntologyRepository {

  OntologyResponse findById(long ontologyId);

  List<OntologySummary> findAllSummaries(String statusFilter);

  List<OntologySummary> findAllSummaries();

  boolean existsLiveDomain(String domain);

  String findStatusById(long ontologyId);

  String lockAndFindStatus(long ontologyId);

  void lockForShare(long ontologyId);

  void updateStatus(long ontologyId, String status);

  int countReferences(long ontologyId);

  void deleteOntology(long ontologyId);

  boolean existsById(long ontologyId);

  int currentSchemaVersion(long ontologyId);

  long createOntology(CreateOntologyRequest req);
}
