package com.smartfirehub.pipeline.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.pipeline.repository.PipelineRepository.LockedPipeline;
import com.smartfirehub.pipeline.repository.mybatis.PipelineRows.PipelineInsert;
import com.smartfirehub.pipeline.repository.mybatis.PipelineRows.PipelineRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 파이프라인(pipeline) MyBatis 매퍼(WD-708). SQL 은 {@code mybatis/pipeline/PipelineMapper.xml}. 문장 1:1
 * 바인딩만 한다 — 테넌트는 정책(RLS)이 맡는다.
 */
@PlatformMapper
public interface PipelineMapper {

  /** 목록 한 쪽 — id 내림차순. */
  List<PipelineRow> selectPage(@Param("limit") int limit, @Param("offset") long offset);

  long count();

  /** 한 건(스텝·활성 트리거 수, 만든이 이름 포함) — 없으면 null. */
  PipelineRow selectById(@Param("id") long id);

  /** 한 건을 넣고 생성 키를 홀더에 돌려준다. */
  int insert(PipelineInsert row);

  /** 메타데이터를 고친다 — isActive 가 null 이면 활성 여부는 건드리지 않는다. 고친 행 수. */
  int update(
      @Param("id") Long id,
      @Param("name") String name,
      @Param("description") String description,
      @Param("isActive") Boolean isActive,
      @Param("updatedBy") Long updatedBy,
      @Param("updatedAt") LocalDateTime updatedAt);

  int deleteById(@Param("id") Long id);

  int countByName(@Param("name") String name);

  int countById(@Param("id") Long id);

  /** id 조각에 든 파이프라인 id. */
  List<Long> selectExistingIds(@Param("chunks") List<List<Long>> chunks);

  String selectName(@Param("id") Long id);

  Long selectCreatedBy(@Param("id") Long id);

  LocalDateTime selectUpdatedAt(@Param("id") Long id);

  Long selectUpdatedBy(@Param("id") Long id);

  /** 행을 FOR UPDATE 로 잠그고 읽는다 — 없으면 null. */
  LockedPipeline selectForUpdate(@Param("id") Long id);
}
