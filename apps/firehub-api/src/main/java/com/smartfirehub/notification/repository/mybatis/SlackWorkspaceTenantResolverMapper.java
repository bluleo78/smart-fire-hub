package com.smartfirehub.notification.repository.mybatis;

import com.smartfirehub.global.platformdb.mybatis.PlatformMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 비인증 Slack 웹훅 경로의 테넌트 해석 매퍼(WD-709, 스펙 D2). SQL 은 {@code
 * mybatis/notification/SlackWorkspaceTenantResolverMapper.xml}.
 *
 * <p>해석 문장은 벤더마다 결과 자리가 다르다 — PG 는 반환 목록(workspace_id, tenant_id), Tibero 는 {@link
 * TeamLookup#getTenantId()} OUT 인자(반환 목록은 비어 있다).
 */
@PlatformMapper
public interface SlackWorkspaceTenantResolverMapper {

  /** Slack 해석 입력과 Tibero OUT 인자(테넌트)를 함께 싣는 파라미터 객체. */
  class TeamLookup {
    private final String teamId;
    private Long tenantId;

    public TeamLookup(String teamId) {
      this.teamId = teamId;
    }

    public String getTeamId() {
      return teamId;
    }

    public Long getTenantId() {
      return tenantId;
    }

    public void setTenantId(Long tenantId) {
      this.tenantId = tenantId;
    }
  }

  /** PG 해석 결과 한 행. */
  record RefRow(Long workspaceId, Long tenantId) {}

  /** team_id → (워크스페이스, 테넌트) 또는 테넌트(정책 우회). 해지된 워크스페이스는 해석하지 않는다. */
  List<RefRow> resolveByTeamId(@Param("q") TeamLookup q);

  /** 테넌트를 심은 트랜잭션에서 RLS 아래 활성 워크스페이스 id 를 다시 읽는다(Tibero 재조회). 없으면 null. */
  Long selectWorkspaceIdByTeamId(@Param("teamId") String teamId);
}
