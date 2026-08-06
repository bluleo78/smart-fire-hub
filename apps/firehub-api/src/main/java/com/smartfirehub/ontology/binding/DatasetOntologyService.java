package com.smartfirehub.ontology.binding;

import com.smartfirehub.ontology.dto.DatasetOntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// 데이터셋↔온톨로지 바인딩 서비스 — 온톨로지 존재 검증 후 바인딩.
// 주의: datasetId의 실존 여부는 의도적으로 검증하지 않는다. dataset_ontology.dataset_id는
// 감사 테이블 패턴을 따라 FK 없이 저장되며(@RequirePermission("dataset:write")은 호출자의 권한
// 코드 보유 여부만 검사할 뿐 경로의 datasetId가 실제로 존재하는지는 확인하지 않는다), 존재하지 않는
// datasetId로 바인딩해도 orphan 레코드가 생길 뿐이다 — slice-0 시점에는 이를 읽는 consumer가 없어
// 무해하다. 따라서 여기선 ontologyId의 유효성만 확인해 잘못된 ontologyId를 400으로 차단한다.
@Service
@RequiredArgsConstructor
public class DatasetOntologyService {

  private final DatasetOntologyRepository bindingRepository;
  private final OntologyRepository ontologyRepository;

  // 바인딩 — ontologyId가 실존하지 않거나 운영 중(active)이 아니면 IllegalArgumentException(→400).
  // 상태 강제의 관문이 여기다. 목록 필터링(GET /ontologies?status=active)은 UX 편의일 뿐이고,
  // API를 직접 호출하면 우회되므로 서버에서 막아야 한다.
  public void bind(long datasetId, long ontologyId, Long userId) {
    if (!ontologyRepository.existsById(ontologyId)) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    String status = ontologyRepository.findStatusById(ontologyId);
    if ("draft".equals(status)) {
      throw new IllegalArgumentException(
          "초안 상태의 온톨로지에는 데이터셋을 연결할 수 없습니다. '지식 모델' 화면에서 먼저 활성화하세요.");
    }
    if ("archived".equals(status)) {
      throw new IllegalArgumentException(
          "은퇴한 온톨로지에는 데이터셋을 연결할 수 없습니다. 복귀시키거나 다른 온톨로지를 선택하세요.");
    }
    bindingRepository.bind(datasetId, ontologyId, userId);
  }

  // 바인딩 조회 — 미바인딩이면 ontologyId=null.
  public DatasetOntologyResponse get(long datasetId) {
    Long ontologyId = bindingRepository.findOntologyIdByDataset(datasetId).orElse(null);
    return new DatasetOntologyResponse(datasetId, ontologyId);
  }
}
