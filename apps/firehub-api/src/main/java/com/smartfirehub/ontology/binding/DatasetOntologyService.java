package com.smartfirehub.ontology.binding;

import com.smartfirehub.dataset.repository.DatasetRepository;
import com.smartfirehub.mapping.repository.MappingRepository;
import com.smartfirehub.ontology.dto.BindOntologyResponse;
import com.smartfirehub.ontology.dto.DatasetOntologyResponse;
import com.smartfirehub.ontology.repository.OntologyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
  // WD-184: 다른 모델로 재연결할 때 옛 모델 기준 매핑을 지우고, 그 동안 매핑 저장과 직렬화하는 데이터셋 행 잠금을 잡는다.
  private final MappingRepository mappingRepository;
  private final DatasetRepository datasetRepository;

  // 바인딩 — ontologyId가 실존하지 않거나 운영 중(active)이 아니면 IllegalArgumentException(→400).
  // 상태 강제의 관문이 여기다. 목록 필터링(GET /ontologies?status=active)은 UX 편의일 뿐이고,
  // API를 직접 호출하면 우회되므로 서버에서 막아야 한다.
  //
  // WD-184: 다른 모델로 다시 연결하면 같은 트랜잭션에서 그 데이터셋의 옛 모델 기준 매핑(dataset_mapping)을 지운다. 지우지 않으면
  // 매핑이 옛 모델로 「활성」인 채 남아 ① 매핑 탭이 새 모델 아래에 옛 매핑을 보이고 ② 활성화가 새 모델 스키마로 재검증돼 400
  // 「…에 없는 속성」으로 실패하며 ③ 옛 모델의 사용처(findLiveByOntology)·참조 수(countReferences)가 이 데이터셋을 계속 센다.
  // 같은 모델로 다시 연결하거나 처음 연결하면 매핑은 그대로다. 옛 모델 그래프(Neo4j)에 이미 적재된 노드는 지우지 않는다.
  //
  // 동시성(잠금 순서 온톨로지 행 → 데이터셋 행 → 매핑 행): 먼저 데이터셋 행을 NO KEY UPDATE 로 잡는다. 매핑 저장·활성화
  // (MappingService.validate)는 같은 행을 FOR SHARE 로 잡은 뒤 연결 모델을 다시 읽으므로, 재연결이 커밋되기 전에 옛 모델로 검증한
  // 매핑이 재연결 뒤에 끼어 저장되는 일이 없다. 이전 연결도 이 잠금을 쥔 뒤에 읽어야 동시 재연결끼리 어긋나지 않는다. 온톨로지 행은
  // 잡지 않는다(스키마를 읽지 않음) — 데이터셋 행 → 매핑 행은 규약의 부분 순서라 교착이 없다.
  @Transactional
  public BindOntologyResponse bind(long datasetId, long ontologyId, Long userId) {
    if (!ontologyRepository.existsById(ontologyId)) {
      throw new IllegalArgumentException("존재하지 않는 온톨로지입니다: " + ontologyId);
    }
    String status = ontologyRepository.findStatusById(ontologyId);
    if ("draft".equals(status)) {
      throw new IllegalArgumentException("초안 상태의 온톨로지에는 데이터셋을 연결할 수 없습니다. '지식 모델' 화면에서 먼저 활성화하세요.");
    }
    if ("archived".equals(status)) {
      throw new IllegalArgumentException("은퇴한 온톨로지에는 데이터셋을 연결할 수 없습니다. 복귀시키거나 다른 온톨로지를 선택하세요.");
    }
    datasetRepository.lockForOntologyRebind(datasetId);
    Long previousOntologyId = bindingRepository.findOntologyIdByDataset(datasetId).orElse(null);
    bindingRepository.bind(datasetId, ontologyId, userId);
    // 매핑의 모델이 새 연결과 다르면 지운다 — 같은 모델 재연결·첫 연결은 지울 행이 없어 그대로 남는다.
    boolean mappingRemoved = mappingRepository.deleteByDatasetExceptOntology(datasetId, ontologyId);
    // 이전 모델 이름(도메인)은 안내 문구용이다. 바인딩은 온톨로지 FK(ON DELETE CASCADE)라 이전 모델은 반드시 존재한다.
    String previousOntologyName =
        previousOntologyId == null
            ? null
            : ontologyRepository.findById(previousOntologyId).domain();
    return new BindOntologyResponse(
        datasetId, ontologyId, mappingRemoved, previousOntologyId, previousOntologyName);
  }

  // 바인딩 조회 — 미바인딩이면 ontologyId=null.
  public DatasetOntologyResponse get(long datasetId) {
    Long ontologyId = bindingRepository.findOntologyIdByDataset(datasetId).orElse(null);
    return new DatasetOntologyResponse(datasetId, ontologyId);
  }
}
