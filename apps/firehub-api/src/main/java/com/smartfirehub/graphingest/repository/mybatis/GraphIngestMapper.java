package com.smartfirehub.graphingest.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.graphingest.dto.GraphIngestRecord;
import com.smartfirehub.graphingest.dto.GraphIngestRecord.StaleRow;
import com.smartfirehub.securitylevel.repository.mybatis.MyBatisDatasetVisibility.VisibilityParams;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 그래프 적재 이력(dataset_graph_ingest) MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/graphingest/GraphIngestMapper.xml}. 테넌트는 정책(RLS)이 거르고 tenant_id 는 열 DEFAULT 가 채운다.
 */
@PlatformMapper
public interface GraphIngestMapper {

  /** 이력 1행 INSERT — 생성 id 는 {@code row.getId()} 로 돌아온다. */
  int insert(GraphIngestInsert row);

  /** 데이터셋의 적재 이력(최신순, 같은 시각이면 id 큰 것 먼저). */
  List<GraphIngestRecord> selectByDataset(@Param("datasetId") long datasetId);

  /** 바인딩 온톨로지 현재 버전보다 낮은 최신 적재행(가시성 필터 포함). */
  List<StaleRow> selectStale(@Param("vis") VisibilityParams vis);
}
