package com.smartfirehub.dataset.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.global.platformdb.mybatis.StatementTimeoutInterceptor;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * tenant_data_store·tenant_data_store_account MyBatis 매퍼(WD-707). SQL 은 {@code
 * mybatis/dataset/TenantDataStoreMapper.xml}.
 *
 * <p>두 테이블은 정책(RLS)이 없는 전역 테이블이라 모든 문장이 tenant_id 를 인자로 받아 술어로 건다(jOOQ 와 같음).
 */
@PlatformMapper
public interface TenantDataStoreMapper {

  /** 저장소 설정 한 행. 열 값은 문자열 그대로(enum 변환은 리포지토리가 한다). */
  record StoreRow(
      long tenantId,
      String backend,
      String host,
      int port,
      String databaseName,
      String schemaName,
      String tablespace,
      String provisionMode,
      String status) {}

  /** 계정 한 행(역할 이름 문자열). */
  record AccountRow(String accountRole, String username, String passwordEnc) {}

  /** 테넌트 데이터 저장소 행을 넣는다(정책 없는 테이블이라 tenant_id 를 명시한다). */
  int insertStore(
      @Param("tenantId") long tenantId,
      @Param("backend") String backend,
      @Param("host") String host,
      @Param("port") int port,
      @Param("databaseName") String databaseName,
      @Param("schemaName") String schemaName,
      @Param("tablespace") String tablespace,
      @Param("provisionMode") String provisionMode,
      @Param("status") String status);

  /** 저장소 접속 계정 한 행을 넣는다(비밀번호는 암호문). */
  int insertAccount(
      @Param("tenantId") long tenantId,
      @Param("accountRole") String accountRole,
      @Param("username") String username,
      @Param("passwordEnc") String passwordEnc);

  /** 테넌트 저장소의 백엔드 이름. 행이 없으면 null. */
  String selectBackend(@Param("tenantId") long tenantId);

  /** 테넌트 저장소 한 행. 없으면 null. */
  StoreRow selectStore(@Param("tenantId") long tenantId);

  /** 이 백엔드에서 준비 완료 상태인 테넌트 id. */
  List<Long> selectReadyTenantIds(
      @Param("backend") String backend, @Param("readyStatus") String readyStatus);

  /** 테넌트 저장소의 접속 계정 전부. */
  List<AccountRow> selectAccounts(@Param("tenantId") long tenantId);

  /** 저장소 상태와 수정 시각을 바꾼다. */
  int updateStatus(
      @Param("tenantId") long tenantId,
      @Param("status") String status,
      @Param("now") LocalDateTime now);

  /** 행을 SELECT … FOR UPDATE 로 잠근다(결과는 쓰지 않는다). */
  List<Long> lockStore(@Param("tenantId") long tenantId);

  /** 계정 한 개의 비밀번호 암호문을 바꾼다. */
  int updatePassword(
      @Param("tenantId") long tenantId,
      @Param("accountRole") String accountRole,
      @Param("passwordEnc") String passwordEnc);

  /** 스크립트 토큰 세대 번호. 행이 없으면 null. */
  Long selectScriptTokenEpoch(@Param("tenantId") long tenantId);

  /**
   * 세대 번호를 1 올리고 올린 행 수를 돌려준다(새 값은 같은 트랜잭션의 {@link #selectScriptTokenEpoch} 로 읽는다).
   * queryTimeoutSeconds 가 null 이 아니고 양수면 문장 시간 제한(초)을 건다 — {@code
   * StatementTimeoutInterceptor}(null·0 이하는 무시).
   */
  int incrementScriptTokenEpoch(
      @Param("tenantId") long tenantId,
      @Param("now") LocalDateTime now,
      @Param(StatementTimeoutInterceptor.PARAM) Integer queryTimeoutSeconds);
}
