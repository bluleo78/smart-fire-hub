package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.dataset.dto.CategoryResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * dataset_category MyBatis 매퍼(WD-707). SQL 은 {@code mybatis/dataset/DatasetCategoryMapper.xml}. 문장
 * 1:1 바인딩만 한다 — 테넌트는 정책(RLS)과 tenant_id DEFAULT 가 맡는다.
 */
@PlatformMapper
public interface DatasetCategoryMapper {

  /** 카테고리 전부(id 오름차순). */
  List<CategoryResponse> selectAll();

  /** id 로 카테고리 한 건. 없으면 null. */
  CategoryResponse selectById(@Param("id") Long id);

  /** 카테고리를 넣고 생성된 id 를 {@code insert.id} 에 채운다. */
  int insert(CategoryInsert insert);

  /** 이름·설명·수정 시각을 바꾸고 바뀐 행 수를 돌려준다. */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("description") String description,
      @Param("now") LocalDateTime now);

  /** 카테고리를 지우고 지운 행 수(0 또는 1)를 돌려준다. */
  int deleteById(@Param("id") Long id);

  /** trim·소문자로 맞춘 이름이 같은 카테고리 수. excludeId 가 null 이 아니면 그 한 행은 뺀다. */
  int countByName(@Param("name") String name, @Param("excludeId") Long excludeId);

  /** 이 카테고리를 참조하는 데이터셋 수(보안 등급 가시성은 걸지 않는다, RLS 만). */
  int countDatasetsUsing(@Param("categoryId") Long categoryId);
}
