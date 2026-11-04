package com.smartfirehub.securitylevel.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 데이터셋 허용 목록(dataset_access_grant 테이블) 저장·조회 리포지토리. 대상 사용자·역할 이름은 user·role 과 조인해 읽는다.
 *
 * <p>구현은 {@code JooqDatasetAccessGrantRepository}(jOOQ/PostgreSQL) 이다 — WD-485.
 */
public interface DatasetAccessGrantRepository {

  /** 화면용 행 — 대상 이름과 추가한 사람 이름까지 조인해 돌려준다. */
  record GrantRow(
      long id,
      long datasetId,
      Long userId,
      Long roleId,
      String subjectName,
      Long grantedBy,
      String grantedByName,
      LocalDateTime grantedAt) {

    /** 대상 종류 — 사용자 항목이면 USER, 아니면 ROLE(화면·감사 메타 표기). */
    public String type() {
      return userId != null ? "USER" : "ROLE";
    }

    /** 대상 id — 사용자 항목이면 user_id, 아니면 role_id. */
    public Long subjectId() {
      return userId != null ? userId : roleId;
    }
  }

  /**
   * 허용 항목의 대상 — 사용자 또는 역할 중 하나만 non-null. 파이프라인 출력 허용 목록의 교집합은 이 값의 동등성으로 낸다(항목 단위, 계획 결정 12 — 입력 A
   * 에 역할로, 입력 B 에 사용자로 올라 있는 사람은 교집합에서 빠진다: 보수적 좁힘).
   */
  record GrantSubject(Long userId, Long roleId) {
    public static GrantSubject user(long id) {
      return new GrantSubject(id, null);
    }

    public static GrantSubject role(long id) {
      return new GrantSubject(null, id);
    }

    /** 감사 메타 표기 — USER 또는 ROLE. */
    public String type() {
      return userId != null ? "USER" : "ROLE";
    }

    /** 감사 메타 표기 — 사용자 id 또는 역할 id. */
    public long subjectId() {
      return userId != null ? userId : roleId;
    }
  }

  /** 데이터셋별 허용 항목 대상 집합(항목 없는 데이터셋은 키가 없다). */
  Map<Long, Set<GrantSubject>> findSubjects(Collection<Long> datasetIds);

  /**
   * 대상 항목을 없을 때만 넣는다({@link #insertIfAbsent} — 같은 파이프라인의 동시 실행이 같은 항목을 넣어도 유니크 위반으로 트랜잭션이 깨지지 않게).
   *
   * @return 새로 넣은 항목 id — 이미 있었으면 빈 값
   */
  Optional<Long> insertSubject(long datasetId, GrantSubject s, Long grantedBy);

  /** 대상 항목을 지운다. 지운 행 수(이미 없으면 0)를 돌려준다. */
  int deleteSubject(long datasetId, GrantSubject s);

  List<GrantRow> findByDataset(long datasetId);

  /** 이 데이터셋에서 지정 대상(사용자 또는 역할 — 둘 중 하나만 non-null)의 항목(이름 조인 포함). 같은 대상의 항목이 여럿이면 목록 순서상 첫 항목. */
  Optional<GrantRow> findByDatasetAndSubject(long datasetId, Long userId, Long roleId);

  /** 항목 1개(이름 조인 포함 — 화면 응답용). {@link #findById} 는 이름 없이 원 행만 읽는다. */
  Optional<GrantRow> findRowById(long grantId);

  Optional<GrantRow> findById(long grantId);

  int countByDataset(long datasetId);

  boolean existsUser(long datasetId, long userId);

  long insertUser(long datasetId, long userId, Long grantedBy);

  long insertRole(long datasetId, long roleId, Long grantedBy);

  /**
   * 허용 항목을 "없을 때만" 추가한다(후속 F5 — 같은 대상 동시 추가 경합). 같은 (데이터셋, 대상) 항목이 이미 있으면(동시 요청이 먼저 커밋한 경우 포함) 아무것도
   * 넣지 않고 빈 값을 돌려준다.
   *
   * <p>왜 {@code ON CONFLICT DO NOTHING} 인가: 사전 조회 → INSERT 사이에 같은 대상이 동시에 들어오면 INSERT 가 유니크 위반으로
   * 끝나는데, 그 순간 PostgreSQL 트랜잭션이 중단돼 예외를 잡고 다시 조회할 수도 없다(코드 없는 일반 409 로 끝난다). 충돌을 문장 안에서 흡수하면 트랜잭션이
   * 살아 있어 승자의 행을 다시 읽어 줄 수 있다. 시더·러너 시드가 쓰는 {@link #insertUser}/{@link #insertRole} 은 반환 계약(long)이
   * 달라 그대로 둔다.
   *
   * @param userId 사용자 대상(역할 대상이면 null)
   * @param roleId 역할 대상(사용자 대상이면 null)
   * @return 새로 넣은 항목 id — 이미 있었으면 빈 값
   */
  Optional<Long> insertIfAbsent(long datasetId, Long userId, Long roleId, Long grantedBy);

  void delete(long grantId);

  /** clone 용 — 원본 항목을 그대로 복사한다(대상·주체 동일, 추가한 사람만 복제자). */
  int copy(long fromDatasetId, long toDatasetId, Long grantedBy);

  /**
   * 이 역할이 허용 항목으로 걸린 데이터셋 행을 id 오름차순으로 {@code FOR UPDATE} 잠근다(후속 F4 — 역할 삭제 경합).
   *
   * <p>왜: 역할 삭제의 "유일한 허용 항목" 검사가 잠금 없이 돌면, 같은 데이터셋의 다른 항목 제거({@code
   * DatasetSecurityService.removeGrant})와 동시에 진행될 때 서로의 미커밋 삭제를 못 보고 둘 다 통과해 데이터셋이 고아가 된다. 항목 제거는
   * 이미 같은 데이터셋 행을 {@code FOR UPDATE} 로 잠그므로, 역할 삭제도 같은 행을 먼저 잠가 둘을 직렬화한다. 항목 제거는 행 1개만 잠그고 여기는 id
   * 오름차순으로 잠그므로 잠금 순서가 엇갈려 교착할 일이 없다(역할 삭제끼리도 같은 순서). 등급과 무관하게 잠근다 — 등급 변경({@code changeLevel})도 같은
   * 행을 UPDATE 하므로, 허용 목록 등급으로 올리는 중인 데이터셋도 새 등급으로 검사된다. 조인 없이 dataset 행만 잠근다.
   *
   * <p>검사({@link #datasetsWhereRoleIsSoleGrantOnAllowlistLevel})는 반드시 이 잠금이 돌아온 <b>뒤의 별도 문장</b>으로
   * 한다 — READ COMMITTED 는 문장 시작 시점 스냅샷을 보므로, 잠금 대기 뒤 새 문장이어야 상대의 커밋된 제거가 보인다.
   *
   * @return 잠근 데이터셋 id(오름차순)
   */
  List<Long> lockDatasetsGrantedToRole(long roleId);

  /** 이 역할이 "유일한 허용 항목"인 allowlist_required 데이터셋 — 역할 삭제가 고아 데이터셋을 만들지 않게 하는 검사용(판단 사항 14). */
  List<Long> datasetsWhereRoleIsSoleGrantOnAllowlistLevel(long roleId);

  /**
   * 이 등급에 속하면서 허용 항목이 하나도 없는 데이터셋 id. 허용 목록 시드와 영향 수 계산이 같은 정의를 쓰도록 한 곳에 둔다.
   *
   * <p>호출자(SecurityLevelService)의 트랜잭션에 합류한다(클래스 레벨 @Transactional, REQUIRED).
   */
  List<Long> findDatasetIdsWithoutAllowlist(long levelId);
}
