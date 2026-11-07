package com.smartfirehub.global.jobrunr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JobRunr 저장소 전용 커넥션 풀 설정({@code firehub.jobrunr.datasource.*}).
 *
 * <p>왜 따로 두는가(WD-555): JobRunr 가 업무용 풀과 같은 풀을 쓰면 풀이 30초 고갈될 때 BackgroundJobServer 의 생존 신호 쓰기가
 * Hikari 타임아웃으로 실패해 서버가 스스로 꺼진다. 전용 풀이면 업무 쿼리가 풀을 다 써도 JobRunr 는 영향받지 않는다.
 *
 * @param maxPoolSize 최대 커넥션 수. 생존 신호·잡 폴링·워커 상태 갱신이 겹쳐도 충분한 작은 값
 * @param minimumIdle 유휴 유지 커넥션 수(0 이면 쓸 때만 연다)
 * @param connectionTimeoutMs 풀에서 커넥션을 기다리는 최대 시간(ms)
 */
@ConfigurationProperties("firehub.jobrunr.datasource")
public record JobRunrDataSourceProperties(
    Integer maxPoolSize, Integer minimumIdle, Long connectionTimeoutMs) {

  /** 속성이 없을 때의 기본값을 채운다(record 컴팩트 생성자). */
  public JobRunrDataSourceProperties {
    if (maxPoolSize == null) {
      maxPoolSize = 4;
    }
    if (minimumIdle == null) {
      minimumIdle = 1;
    }
    if (connectionTimeoutMs == null) {
      connectionTimeoutMs = 10_000L;
    }
  }
}
