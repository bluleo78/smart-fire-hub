package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.OffsetDateTime;
import org.apache.ibatis.annotations.Param;

/**
 * oauth_state MyBatis 매퍼(WD-709). SQL 은 {@code mybatis/notification/OAuthStateMapper.xml}.
 *
 * <p>oauth_state 는 RLS 정책이 없는 테이블이라(V106 [R7]) 테넌트 조건을 SQL 에 쓰지 않는다 — tenant_id 는 INSERT 때 열
 * DEFAULT 가 채우고 소비 때는 값으로 읽어 돌려줄 뿐이다. 시각은 jOOQ 구현과 같이 자바에서 UTC {@link OffsetDateTime} 으로 만들어 넘긴다.
 */
@PlatformMapper
public interface OAuthStateMapper {

  /** 소비 대상 조회 결과 — tenantId 는 NOT NULL 열이지만 fail-closed 방어를 위해 null 가능으로 받는다. */
  record StateRow(Long userId, String channelType, Long tenantId) {}

  /** state 한 건 저장. tenant_id 는 열 DEFAULT 가 채운다. */
  int insert(
      @Param("state") String state,
      @Param("userId") long userId,
      @Param("channelType") String channelType,
      @Param("expiresAt") OffsetDateTime expiresAt);

  /** 미소비·미만료 state 를 행 잠금(FOR UPDATE)과 함께 읽는다 — 없으면 null. */
  StateRow selectConsumableForUpdate(
      @Param("state") String state, @Param("now") OffsetDateTime now);

  /** 아직 소비되지 않은 state 에만 consumed_at 을 찍는다. 반환=갱신 행 수(0 이면 다른 트랜잭션이 먼저 소비한 것). */
  int markConsumed(@Param("state") String state, @Param("now") OffsetDateTime now);

  /** 만료 시각이 now 보다 이른 행을 지운다. 반환=삭제 행 수. */
  int deleteExpired(@Param("now") OffsetDateTime now);
}
