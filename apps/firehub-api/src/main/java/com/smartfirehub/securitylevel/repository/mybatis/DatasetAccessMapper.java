package com.smartfirehub.securitylevel.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessRows.ClearanceRoleRow;
import com.smartfirehub.securitylevel.repository.mybatis.DatasetAccessRows.FactRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 데이터셋 접근 사실·자격 원자료 MyBatis 매퍼(WD-711). SQL 은 {@code
 * mybatis/securitylevel/DatasetAccessMapper.xml}. 문장 1:1 바인딩만 한다 — 테넌트는 정책(RLS)이 맡고, 정책 없는
 * membership·사용자 조인과 자격 원자료만 인자로 받은 tenantId 를 명시한다.
 *
 * <p>chunks 는 InChunks(500 개) 조각, roleIds 는 호출자 자격의 보유 역할(몇 개뿐이라 조각내지 않는다 — 가시성 조각과 같은 규칙).
 */
@PlatformMapper
public interface DatasetAccessMapper {

  /** id 조각에 든 데이터셋의 판정 사실(userId·roleIds 로 허용 목록 여부를 함께 계산). */
  List<FactRow> selectFactsByIds(
      @Param("chunks") List<List<Long>> chunks,
      @Param("userId") long userId,
      @Param("roleIds") List<Long> roleIds);

  /** 테이블 이름 조각에 든 데이터셋의 판정 사실. */
  List<FactRow> selectFactsByTableNames(
      @Param("chunks") List<List<String>> chunks,
      @Param("userId") long userId,
      @Param("roleIds") List<Long> roleIds);

  /**
   * 주어진 역할(조각)의 허용 항목 중 허용 목록 필요 등급이면서 호출자가 볼 수 없는 데이터셋을 가진 역할 id(중복 없음).
   *
   * @param rank 호출자 rank(NO_RANK 면 Integer.MIN_VALUE — 모든 등급이 초과)
   * @param tenantAdmin 시스템 ADMIN 이면 admin_bypass 등급은 허용 목록 없이 볼 수 있다
   */
  List<Long> selectRoleIdsWithAllowlistNotViewable(
      @Param("chunks") List<List<Long>> chunks,
      @Param("rank") int rank,
      @Param("userId") long userId,
      @Param("roleIds") List<Long> roleIds,
      @Param("tenantAdmin") boolean tenantAdmin);

  /** 사용자 1명의 자격 원자료 행(이 테넌트 ACTIVE 멤버십 + 활성 계정의 이 테넌트 역할). */
  List<ClearanceRoleRow> selectClearanceRows(
      @Param("userId") long userId, @Param("tenantId") long tenantId);

  /** 여러 사용자(조각)의 자격 원자료 행 — {@link #selectClearanceRows} 와 같은 조인. */
  List<ClearanceRoleRow> selectClearanceRowsByUsers(
      @Param("chunks") List<List<Long>> chunks, @Param("tenantId") long tenantId);
}
