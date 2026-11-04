package com.smartfirehub.dataset.repository;

import com.smartfirehub.dataset.search.DatasetSourceTextBuilder;
import java.util.List;

/**
 * 데이터셋 메타를 검색 합본 빌더 입력({@link DatasetSourceTextBuilder.Input})으로 읽어온다. 없으면 null.
 *
 * <p>jOOQ 필드 참조 방식은 {@code DatasetRepository} 와 동일하게 {@code field(name(...))} 동적 참조를 쓴다 (생성 코드 의존
 * 없이 public 스키마 테이블을 직접 가리킴).
 *
 * <p>구현은 {@code JooqDatasetMetaReader}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetMetaReader {

  /**
   * 데이터셋 단건 메타를 합본 입력으로 구성한다.
   *
   * @return 데이터셋이 없으면 null (삭제됨 → 호출측에서 인덱스 제거 판단)
   */
  DatasetSourceTextBuilder.Input read(long datasetId);

  /** 백필용: 전체 데이터셋 id 목록(오름차순). */
  List<Long> findAllIds();
}
