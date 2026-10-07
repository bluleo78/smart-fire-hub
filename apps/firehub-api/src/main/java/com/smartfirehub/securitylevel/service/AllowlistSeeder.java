package com.smartfirehub.securitylevel.service;

import static com.smartfirehub.jooq.Tables.DATASET;
import static com.smartfirehub.jooq.Tables.DATASET_ACCESS_GRANT;
import static org.jooq.impl.DSL.notExists;
import static org.jooq.impl.DSL.selectOne;

import com.smartfirehub.securitylevel.access.LevelPolicy;
import com.smartfirehub.securitylevel.repository.DatasetAccessGrantRepository;
import com.smartfirehub.securitylevel.repository.SecurityLevelRepository;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

/**
 * 허용 목록이 필요한 등급에 속하게 된(또는 그렇게 바뀐) 데이터셋의 빈 허용 목록을 "현재 열람 가능한 역할"로 채운다(스펙 §4.7).
 *
 * <p>왜 역할 단위인가: 시드 직전의 열람자 집합은 "그 등급 rank 이상 자격 역할 보유자"였다. 그 역할들을 넣으면 전후 열람자가 같다(확대도 축소도 없음) — 사용자
 * 단위로 풀어 넣으면 이후 역할 구성원 변화가 반영되지 않는다. 또한 허용 목록이 빈 allowlist_required 데이터셋은 아무도 볼 수 없는 고아가 되므로(스펙
 * §2.4, §4.5) 이동·토글 직후 반드시 채운다.
 */
@Component
@RequiredArgsConstructor
public class AllowlistSeeder {

  private final DSLContext dsl;
  private final SecurityLevelRepository levelRepository;
  private final DatasetAccessGrantRepository grantRepository;

  /**
   * 해당 등급 데이터셋 중 허용 목록이 빈 것마다, 그 등급 rank 이상 자격 역할 전부(시스템 ADMIN 포함)를 ROLE 항목으로 추가한다. 이미 목록이 있는 데이터셋은
   * 건드리지 않는다. 호출자 트랜잭션 안에서 돈다.
   *
   * @return 추가한 허용 항목 수
   */
  public int seedEmptyAllowlistsWithViewerRoles(long levelId, long actor) {
    LevelPolicy level = levelRepository.findById(levelId).orElseThrow();
    Map<Long, Integer> rankById =
        levelRepository.findAll().stream()
            .collect(Collectors.toMap(LevelPolicy::id, LevelPolicy::rank));
    List<Long> viewerRoles =
        levelRepository.findRoleLevels().stream()
            .filter(r -> r.systemAdmin() || rankById.get(r.levelId()) >= level.rank())
            .map(SecurityLevelRepository.RoleLevel::roleId)
            .toList();
    List<Long> emptyDatasets = datasetsWithoutAllowlist(levelId);
    int inserted = 0;
    for (long ds : emptyDatasets) {
      for (long roleId : viewerRoles) {
        grantRepository.insertRole(ds, roleId, actor);
        inserted++;
      }
    }
    return inserted;
  }

  /** 이 등급에 속하면서 허용 항목이 하나도 없는 데이터셋. 영향 수 계산과 시드가 같은 정의를 쓴다. */
  public List<Long> datasetsWithoutAllowlist(long levelId) {
    return dsl.select(DATASET.ID)
        .from(DATASET)
        .where(DATASET.SECURITY_LEVEL_ID.eq(levelId))
        .and(
            notExists(
                selectOne()
                    .from(DATASET_ACCESS_GRANT)
                    .where(DATASET_ACCESS_GRANT.DATASET_ID.eq(DATASET.ID))))
        .fetch(DATASET.ID);
  }
}
