package com.smartfirehub.mapping.dto;

import java.util.List;
import java.util.Map;

/**
 * 한 온톨로지의 요소별 데이터셋 매핑 사용처(WD-129). 지식 모델 편집기의 삭제 확인 창이 "이 요소를 쓰는 매핑이 있으면 삭제를 막고 어느 데이터셋인지 보여 주기" 위해
 * 읽는다. 사용처가 있는 요소만 담는다.
 *
 * <p>요소를 이름이 아니라 <b>id</b> 로 가리키는 이유: 매핑 spec 은 이름으로 참조하지만, 화면 캐시가 이름 기준이면 리네임 직후 어긋난다. 서버가 현재 스키마로
 * 이름 → id 를 풀어 주면 리네임과 무관하게 같은 키로 조회된다.
 *
 * @param entityTypes 엔티티 타입 id → 사용처
 * @param properties 속성 id → 사용처
 * @param relations 관계 id → 사용처
 */
public record OntologyMappingUsage(
    Map<Long, ElementUsage> entityTypes,
    Map<Long, ElementUsage> properties,
    Map<Long, ElementUsage> relations) {

  /**
   * 요소 하나의 사용처.
   *
   * @param datasets 조회자가 볼 수 있는 데이터셋만(id·이름)
   * @param hiddenCount 조회자가 볼 수 없는 데이터셋(보안 등급) 개수 — 존재 은닉을 위해 id 도 이름도 싣지 않는다. DatasetAccessGuard 가
   *     그런 데이터셋을 404 로 숨기는 것과 맞춘다.
   */
  public record ElementUsage(List<UsageDataset> datasets, int hiddenCount) {}

  /** 볼 수 있는 사용처 데이터셋. */
  public record UsageDataset(long datasetId, String datasetName) {}
}
