package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import org.apache.ibatis.annotations.Param;

/**
 * user_channel_preference MyBatis 매퍼(WD-709). SQL 은 {@code
 * mybatis/notification/UserChannelPreferenceMapper.xml}. 테넌트는 정책(RLS)이 맡는다 — tenant_id 는 INSERT
 * 값·WHERE 술어로 쓰지 않는다(열 DEFAULT 가 채운다).
 */
@PlatformMapper
public interface UserChannelPreferenceMapper {

  /** 저장된 enabled 값 — 행이 없으면 null. */
  Boolean selectEnabled(@Param("userId") long userId, @Param("channelType") String channelType);

  /** (user_id, channel_type) 키로 넣거나 enabled 를 덮는다(PG ON CONFLICT, Tibero MERGE). */
  int upsert(
      @Param("userId") long userId,
      @Param("channelType") String channelType,
      @Param("enabled") boolean enabled);
}
