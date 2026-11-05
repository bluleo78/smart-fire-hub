package com.smartfirehub.proactive.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * proactive_message MyBatis 매퍼(WD-487 PoC). SQL 은 {@code
 * mybatis/proactive/ProactiveMessageMapper.xml}.
 *
 * <p>문장 1:1 바인딩만 한다. 읽은 시각은 jOOQ 구현과 같이 자바({@code ProactiveTime.nowUtc()})에서 만들어 넘긴다.
 */
@PlatformMapper
public interface ProactiveMessageMapper {

  /** 메시지를 넣고 생성된 id 를 {@code insert.id} 에 채운다. tenant_id 는 컬럼 DEFAULT(GUC)가 채운다. */
  int insert(ProactiveMessageInsert insert);

  /** 수신자의 메시지 목록(LEFT JOIN 실행·작업, id 내림차순). */
  List<ProactiveMessageRow> findByUserId(
      @Param("userId") Long userId,
      @Param("limit") int limit,
      @Param("offset") int offset,
      @Param("unreadOnly") boolean unreadOnly);

  /** 수신자의 안 읽은 메시지 전체(페이징 없음). */
  List<ProactiveMessageRow> findUnreadByUserId(@Param("userId") Long userId);

  int countUnreadByUserId(@Param("userId") Long userId);

  int markAsRead(
      @Param("id") Long id, @Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);

  int markAllAsRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
