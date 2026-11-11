package com.smartfirehub.document.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** document_file MyBatis 매퍼(WD-712). SQL 은 {@code mybatis/document/DocumentFileMapper.xml}. */
@PlatformMapper
public interface DocumentFileMapper {

  /** PENDING 상태로 한 건을 넣고 생성 키를 {@code row.id} 에 채운다. */
  int insert(DocumentFileInsert row);

  /** 상태만 바꾼다. */
  int updateStatus(@Param("id") Long id, @Param("status") String status);

  /** COMPLETED + 페이지/청크 수 + 완료 시각(DB 시계). */
  int markCompleted(
      @Param("id") Long id,
      @Param("pageCount") Integer pageCount,
      @Param("chunkCount") int chunkCount);

  /** FAILED + 오류 상세(리포지토리가 이미 잘라 넘긴다). */
  int markFailed(@Param("id") Long id, @Param("errorDetail") String errorDetail);

  /** 저장 경로. 행이 없으면 null. */
  String selectStoragePath(@Param("id") Long id);

  /** 같은 데이터셋 안에 같은 체크섬 행 수. */
  int countByChecksum(@Param("datasetId") Long datasetId, @Param("checksum") String checksum);

  /** 데이터셋의 문서들(최신순, 같은 시각이면 id 내림차순). */
  List<DocumentFileRow> selectByDataset(@Param("datasetId") Long datasetId);

  /** id 로 한 건. 없으면 null. */
  DocumentFileRow selectById(@Param("id") Long id);

  /** id 로 삭제. */
  int deleteById(@Param("id") Long id);
}
