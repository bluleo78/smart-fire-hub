package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.dataset.search.DatasetSearchHit;
import com.smartfirehub.document.dto.DocumentSearchHit;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 별도 벡터 저장소 검색의 플랫폼 DB 확인 매퍼(WD-720). SQL 은 {@code mybatis/vectorstore/SearchCandidateMapper.xml}.
 *
 * <p>id 목록 인자는 모두 {@code InChunks.of} 로 500 개씩 나눈 목록이다(Tibero IN 상한). 선택 목록은 null 이면 조건을 붙이지 않는다.
 */
@PlatformMapper
public interface SearchCandidateMapper {

  /** 볼 수 있는 데이터셋 id(오름차순). */
  List<Long> selectVisibleDatasetIds(
      @Param("vis") VisibilityParams vis,
      @Param("storageType") String storageType,
      @Param("restrictTo") List<List<Long>> restrictTo);

  /**
   * 후보 데이터셋의 표시 필드.
   *
   * @param dim 의미 검색이면 공간 차원(표시 열 일치), 키워드면 null
   */
  List<DatasetSearchHit> selectDatasetHits(
      @Param("ids") List<List<Long>> ids,
      @Param("vis") VisibilityParams vis,
      @Param("storageType") String storageType,
      @Param("dim") Integer dim,
      @Param("model") String model);

  /** 후보 청크의 표시 필드(완료된 문서만). */
  List<DocumentSearchHit> selectChunkHits(
      @Param("ids") List<List<Long>> ids,
      @Param("vis") VisibilityParams vis,
      @Param("restrictTo") List<List<Long>> restrictTo,
      @Param("dim") Integer dim,
      @Param("model") String model);
}
