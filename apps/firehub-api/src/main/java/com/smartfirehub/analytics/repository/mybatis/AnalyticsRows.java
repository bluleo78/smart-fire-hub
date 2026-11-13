package com.smartfirehub.analytics.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformSharing;
import java.time.LocalDateTime;

/**
 * 분석 매퍼들(차트·저장 쿼리·대시보드)이 함께 쓰는 평평한 행 모양(WD-710). 결과 매핑은 {@code
 * mybatis/analytics/AnalyticsCommon.xml} 한 곳에 두고 각 매퍼가 그 이름공간을 참조한다 — 한 도메인 파일에 두고 다른 도메인이 빌려 쓰면 주인
 * 파일을 고칠 때 남의 문장이 조용히 깨지므로 중립 위치로 뺐다.
 */
public final class AnalyticsRows {

  private AnalyticsRows() {}

  /** 공유 대상으로 잠근 행의 id·이름 — {@code shareCharts}/{@code shareQueries} 가 돌려줄 id→이름 맵의 원본. */
  public record IdName(Long id, String name) implements PlatformSharing.Target {}

  /** 잠금 조회한 수정 시각. id 를 함께 읽어 수정 시각이 비어 있어도 「행 없음」(null 객체)으로 무너지지 않게 한다. */
  public record VersionRow(Long id, LocalDateTime updatedAt) {}

  /** 내가 만든 자산의 공유 여부별 수(집계라 행은 늘 하나). */
  public record SharingCounts(long privateCount, long sharedCount) {}
}
