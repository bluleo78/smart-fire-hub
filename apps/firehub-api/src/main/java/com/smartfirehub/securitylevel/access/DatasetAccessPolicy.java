package com.smartfirehub.securitylevel.access;

/**
 * 데이터셋 접근 판정 — 순수 함수(스펙 §4.1). DB·Spring 을 모른다.
 *
 * <p>왜 순수 함수인가: 같은 규칙이 상세(이 함수), 목록(SQL 조각 — {@link DatasetAccessGuard#visibleCondition}), SQL 경로에서
 * 쓰인다. 규칙의 정본을 한 곳에 두고 조합 매트릭스로 고정해야 SQL 조각과의 일치 테스트가 의미를 갖는다.
 */
public final class DatasetAccessPolicy {

  /** '권한 필요' 등급 내보내기 권한(스펙 §2.6). S4 가 강제 경로에 연결한다. */
  public static final String EXPORT_RESTRICTED_PERMISSION =
      com.smartfirehub.securitylevel.SecurityPermissions.EXPORT_RESTRICTED;

  private DatasetAccessPolicy() {}

  /** VIEW 만 묻는 단축형 — 목록 SQL 조각과의 일치 테스트가 이것을 기준으로 삼는다. */
  public static boolean canView(
      int userRank, boolean onAllowlist, boolean tenantAdmin, LevelPolicy level) {
    return decide(
            new AccessInput(
                userRank,
                onAllowlist,
                tenantAdmin,
                level,
                DatasetAction.VIEW,
                java.util.Set.of(),
                null))
        .allowed();
  }

  /** 스펙 §2.5: VIEW 를 먼저 판정하고, 통과한 경우에만 행위별 정책을 본다. */
  public static Decision decide(AccessInput in) {
    LevelPolicy level = in.level();
    if (level == null) {
      // 등급을 모르는 데이터셋은 볼 수 없다(fail-closed).
      return Decision.deny("LEVEL_UNKNOWN", null, null);
    }
    Long levelId = level.id();
    if (in.userRank() < level.rank()) {
      return Decision.deny("CLEARANCE_INSUFFICIENT", levelId, "rank");
    }
    if (level.allowlistRequired()
        && !in.onAllowlist()
        && !(level.adminBypass() && in.tenantAdmin())) {
      return Decision.deny("NOT_ON_ALLOWLIST", levelId, "allowlist_required");
    }
    return switch (in.action()) {
      case VIEW -> Decision.allow(levelId);
      case EXPORT -> decideExport(in, level);
      case AI -> decideAi(in, level);
      case SHARE ->
          level.sharePolicy() == LevelPolicy.SharePolicy.ALLOW
              ? Decision.allow(levelId)
              : Decision.deny("SHARE_DENIED", levelId, "share_policy");
    };
  }

  private static Decision decideExport(AccessInput in, LevelPolicy level) {
    return switch (level.exportPolicy()) {
      case ALLOW -> Decision.allow(level.id());
      case PERMISSION ->
          in.permissions() != null && in.permissions().contains(EXPORT_RESTRICTED_PERMISSION)
              ? Decision.allow(level.id())
              : Decision.deny("EXPORT_PERMISSION_REQUIRED", level.id(), "export_policy");
      case DENY -> Decision.deny("EXPORT_DENIED", level.id(), "export_policy");
    };
  }

  private static Decision decideAi(AccessInput in, LevelPolicy level) {
    return switch (level.aiPolicy()) {
      case ALL -> Decision.allow(level.id());
      // hosting 이 null 이면 외부로 간주한다 — 호출자가 위치를 모르면 보수적으로 막는다.
      case SELF_HOSTED_ONLY ->
          in.hosting() == ProviderHosting.SELF_HOSTED
              ? Decision.allow(level.id())
              : Decision.deny("AI_EXTERNAL_DENIED", level.id(), "ai_policy");
      case DENY -> Decision.deny("AI_DENIED", level.id(), "ai_policy");
    };
  }

  /**
   * 등급 정책만으로 본 AI 허용(사용자 없음 — 임베딩·SQL 조각과 같은 규칙). hosting 이 null 이면 decideAi 처럼 외부로 본다. decideAi 와의
   * 일치는 DatasetAccessPolicyTest 가 고정한다.
   */
  public static boolean aiAllowedForLevel(LevelPolicy level, ProviderHosting hosting) {
    return switch (level.aiPolicy()) {
      case ALL -> true;
      case SELF_HOSTED_ONLY -> hosting == ProviderHosting.SELF_HOSTED;
      case DENY -> false;
    };
  }

  /** 등급 정책만으로 본 공유 허용(GraphRAG 적재·리포트 발송 판정의 등급 부분). */
  public static boolean shareAllowedForLevel(LevelPolicy level) {
    return level.sharePolicy() == LevelPolicy.SharePolicy.ALLOW;
  }
}
