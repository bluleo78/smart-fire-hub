package com.smartfirehub.file.repository.mybatis;

import com.smartfirehub.file.repository.UploadedFileRepository.ExpiredFile;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** uploaded_files MyBatis 매퍼(WD-712). SQL 은 {@code mybatis/file/UploadedFileMapper.xml}. */
@PlatformMapper
public interface UploadedFileMapper {

  /** 한 건을 넣고 생성 키를 {@code row.id} 에 채운다. */
  int insert(UploadedFileInsert row);

  /** id 와 업로더가 모두 일치하는 행. 없으면 null. */
  UploadedFileRow selectByIdAndUploader(@Param("fileId") Long fileId, @Param("userId") Long userId);

  /** 만료 시각이 now 보다 앞선 행의 id·저장 경로(id 오름차순). */
  List<ExpiredFile> selectExpired(@Param("now") Instant now);

  /** id 청크(조각당 500 개 이하) 중 아직 만료인 행만 지운다. */
  int deleteExpiredByIds(@Param("chunks") List<List<Long>> chunks, @Param("now") Instant now);
}
