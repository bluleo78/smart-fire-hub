package com.smartfirehub.vectorstore.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * tenant_vector_store MyBatis 매퍼(WD-742). SQL 은 {@code
 * mybatis/vectorstore/TenantVectorStoreMapper.xml}. 전역 테이블이라 모든 문장이 tenant_id 를 인자로 받아 술어로 건다.
 */
@PlatformMapper
public interface TenantVectorStoreMapper {

  /** 한 행. 불리언은 두 DB 공통으로 0/1 정수로 읽는다(Tibero NUMBER(1)). */
  record Row(long tenantId, String host, int grpcPort, int useTls, String apiKeyEnc) {}

  void insert(
      @Param("tenantId") long tenantId,
      @Param("host") String host,
      @Param("grpcPort") int grpcPort,
      @Param("useTls") boolean useTls,
      @Param("apiKeyEnc") String apiKeyEnc);

  Row select(@Param("tenantId") long tenantId);

  List<Long> selectTenantIds();
}
