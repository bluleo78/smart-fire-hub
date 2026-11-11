package com.smartfirehub.ai.repository.mybatis;

import com.smartfirehub.ai.dto.AiSessionResponse;
import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** ai_session MyBatis 매퍼(WD-712). SQL 은 {@code mybatis/ai/AiSessionMapper.xml}. */
@PlatformMapper
public interface AiSessionMapper {

  /** 사용자의 세션 한 페이지(수정 시각 내림차순, 같으면 id 내림차순). */
  List<AiSessionResponse> selectByUser(
      @Param("userId") Long userId, @Param("limit") int limit, @Param("offset") long offset);

  /** id 로 한 행. 없으면 null. */
  AiSessionResponse selectById(@Param("id") Long id);

  /** 사용자·컨텍스트가 모두 일치하는 행. 없으면 null(둘 이상이면 TooManyResultsException). */
  AiSessionResponse selectByUserAndContext(
      @Param("userId") Long userId,
      @Param("contextType") String contextType,
      @Param("contextResourceId") Long contextResourceId);

  /** 사용자·외부 세션 ID 가 모두 일치하는 행. 없으면 null. */
  AiSessionResponse selectByUserAndSessionId(
      @Param("userId") Long userId, @Param("sessionId") String sessionId);

  /** 같은 세션 ID 가 다른 사용자에게 있는 행 수(현재 테넌트 안, RLS). */
  int countForOtherUser(@Param("userId") Long userId, @Param("sessionId") String sessionId);

  /** WEB 세션을 넣고 생성 키를 {@code row.id} 에 채운다. */
  int insertWeb(AiSessionInsert row);

  /** SLACK 세션을 넣고 생성 키를 {@code row.id} 에 채운다. */
  int insertSlack(AiSessionInsert row);

  /** 제목과 수정 시각(now — JVM 시간대 벽시계)을 바꾼다. */
  int updateTitle(
      @Param("id") Long id, @Param("title") String title, @Param("now") LocalDateTime now);

  /** id 로 한 행을 지운다. */
  int deleteById(@Param("id") Long id);

  /** SLACK 스레드 3-tuple 로 한 행. 없으면 null. */
  AiSessionResponse selectBySlackContext(
      @Param("teamId") String teamId,
      @Param("channelId") String channelId,
      @Param("threadTs") String threadTs);
}
