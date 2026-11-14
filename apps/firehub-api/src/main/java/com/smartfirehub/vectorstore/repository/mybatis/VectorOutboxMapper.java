package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.vectorstore.VectorOutboxRepository.Entry;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 벡터 아웃박스 매퍼(WD-721). SQL 은 {@code mybatis/vectorstore/VectorOutboxMapper.xml}. */
@PlatformMapper
public interface VectorOutboxMapper {

  /** 시퀀스 {@code vector_seq} 의 다음 값. */
  long nextSeq();

  /** 삭제 한 건을 쌓는다. */
  void insert(
      @Param("seq") long seq,
      @Param("tenantId") long tenantId,
      @Param("kind") String kind,
      @Param("targetId") Long targetId,
      @Param("keepDim") Integer keepDim);

  /** 순번 순서로 {@code limit} 건({@code offset} 은 늘 0 — pageClause 공용 조각이 요구한다). */
  List<Entry> findPending(
      @Param("tenantId") long tenantId, @Param("limit") int limit, @Param("offset") int offset);

  /** 반영을 마친 행을 지운다. */
  void delete(@Param("tenantId") long tenantId, @Param("seq") long seq);

  /** 실패 기록. */
  void markFailed(
      @Param("tenantId") long tenantId, @Param("seq") long seq, @Param("error") String error);
}
