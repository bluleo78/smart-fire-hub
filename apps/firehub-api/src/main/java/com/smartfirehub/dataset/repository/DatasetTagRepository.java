package com.smartfirehub.dataset.repository;

import com.smartfirehub.securitylevel.access.DatasetVisibility;
import java.util.List;

/**
 * 데이터셋 태그(dataset_tag) 저장·조회 리포지토리.
 *
 * <p>구현은 {@code JooqDatasetTagRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetTagRepository {

  /** 데이터셋에 붙은 태그 이름 목록을 조회한다. */
  List<String> findByDatasetId(Long datasetId);

  /**
   * 태그를 넣고 넣은 행 수를 돌려준다. 같은 데이터셋에 대소문자만 다른 태그가 이미 있으면(유일 인덱스 (dataset_id, lower(tag_name)), V155)
   * 넣지 않고 0 — 호출자는 0 이면 중복으로 본다(WD-389).
   */
  int insert(Long datasetId, String tagName, Long userId);

  /**
   * 데이터셋의 태그를 대소문자 무시로 지우고 지운 행 수를 돌려준다.
   *
   * <p>WD-242: 추가 중복 검사가 대소문자를 무시하므로(#671) 삭제도 같은 기준이어야 한다 — 예전에는 정확 일치만 지워 「Fire」가 있을 때 「fire」 삭제가
   * 204 를 주고도 아무것도 지우지 않았다. 호출자는 0 이면 없는 태그로 본다.
   */
  int deleteIgnoreCase(Long datasetId, String tagName);

  /** 열람 가능한 데이터셋의 태그만 집계한다 — 숨김 데이터셋의 태그명도 정보다(스펙 §4.2 1행). */
  List<String> findAllDistinctTags(DatasetVisibility visibleDataset);
}
