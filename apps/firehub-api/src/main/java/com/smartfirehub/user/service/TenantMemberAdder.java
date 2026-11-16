package com.smartfirehub.user.service;

import com.smartfirehub.global.exception.CodedApiException;
import com.smartfirehub.tenant.dto.TenantMembership;
import com.smartfirehub.tenant.repository.MembershipRepository;
import com.smartfirehub.user.dto.UserResponse;
import com.smartfirehub.user.repository.UserRepository;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 테넌트에 멤버를 붙이는 핵심 단계(계정 확보 → 멤버십 → 역할)를 한 곳에 모은 공용 컴포넌트(WD-750).
 *
 * <p>웹 경로({@link UserService#addMember})와 운영자 콘솔 경로(플랫폼 엔드포인트)가 같은 규칙을 쓰도록 뽑았다 — 두 곳에 복사해 두면 409
 * 코드·메시지·동시성 번역이 어긋난다. 역할 검증·USER 합치기·역할 범위 검사·감사는 호출자마다 달라 여기에 두지 않는다.
 *
 * <p><b>트랜잭션:</b> 이 클래스에는 {@code @Transactional} 을 붙이지 않는다. 호출자의 트랜잭션에 합류해, 중간에 예외가 나면 부분 생성된
 * 계정·멤버십이 호출자 롤백으로 함께 사라진다. 특히 플랫폼 호출자는 테넌트 범위(RLS 용 테넌트 설정)를 <b>자기 트랜잭션이 시작되기 전에</b> 잡아야 하므로, 여기서
 * 새 트랜잭션을 열면 그 순서가 깨진다.
 *
 * <p>대상 테넌트는 {@code tenantId} 인자로만 받는다 — {@code TenantContext} 를 읽지 않으므로 웹(현재 테넌트)과 플랫폼(명시 지정 테넌트)
 * 모두 쓸 수 있다.
 */
@Component
@RequiredArgsConstructor
public class TenantMemberAdder {

  private final UserRepository userRepository;
  private final MembershipRepository membershipRepository;
  private final TemporaryAccountCreator temporaryAccountCreator;

  /**
   * 추가 결과. {@code created} 는 이번에 계정을 새로 만들었는지(true) 기존 계정을 붙였는지(false)이다.
   *
   * @param user 멤버가 된 계정
   * @param created 새 계정 생성 여부
   */
  public record Result(UserResponse user, boolean created) {}

  /**
   * 계정을 확보해 대상 테넌트의 멤버로 붙이고 역할을 부여한다.
   *
   * <ul>
   *   <li>계정 없음 → 임시 비밀번호로 계정 생성(변경 강제 표식 on) + 멤버십 + 역할.
   *   <li>계정 있음 + 비멤버 → 멤버십·역할만. 비밀번호·이름 불변, temporaryPassword 무시.
   *   <li>이미 멤버 → 409. ACTIVE 는 {@code MEMBER_ALREADY_EXISTS}, 그 밖(정지 등)은 {@code
   *       MEMBER_SUSPENDED}(userId 동봉).
   * </ul>
   *
   * <p>이메일 정규화(trim + 소문자)는 여기 한 곳에서 한다 — 이메일 = username 이라 대소문자·앞뒤 공백이 달라도 같은 사람이어야 기존 계정을 찾고 중복
   * 계정을 만들지 않는다. 호출자마다 정규화를 두면 한쪽만 빠져 중복 계정이 생길 수 있다.
   *
   * @param tenantId 멤버를 붙일 테넌트 id
   * @param email 이메일(= username). 여기서 trim·소문자로 정규화한다
   * @param name 새 계정을 만들 때의 이름(기존 계정이면 무시)
   * @param temporaryPassword 새 계정을 만들 때의 임시 비밀번호(기존 계정이면 무시)
   * @param roleIds 부여할 역할 id(호출자가 기본 USER 까지 합쳐 넘긴다)
   * @param existingAccountCheck 기존 계정을 찾았을 때 붙이기 전에 부르는 호출자별 검사(예: 플랫폼의 비활성 계정 거부). 검사가 없으면 아무것도 하지
   *     않는 함수를 넘긴다. 거부하려면 예외를 던진다 — 계정 조회를 호출자와 여기서 두 번 하지 않으려고 검사를 인자로 받는다.
   */
  public Result add(
      long tenantId,
      String email,
      String name,
      String temporaryPassword,
      Collection<Long> roleIds,
      Consumer<UserResponse> existingAccountCheck) {
    String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
    Optional<UserResponse> existing = userRepository.findByUsernameIgnoreCase(normalizedEmail);
    if (existing.isPresent()) {
      // 기존 계정이면 호출자 검사를 먼저 거친 뒤 기존 계정 진입점과 같은 단계(이미 멤버 검사 → 멤버십 → 역할)를 탄다.
      existingAccountCheck.accept(existing.get());
      return addExisting(tenantId, existing.get(), roleIds);
    }
    return attach(
        tenantId,
        () -> temporaryAccountCreator.create(normalizedEmail, name, temporaryPassword),
        true,
        roleIds);
  }

  /**
   * 이미 확보한 기존 계정을 대상 테넌트의 멤버로 붙이고 역할을 부여한다(계정 생성 없음).
   *
   * <p>운영자 콘솔은 검색 결과에서 고른 계정을 id 로 지정한다 — 이메일로 username 을 다시 찾으면 username ≠ email 인 계정을 놓치기 때문이다.
   * 이미 멤버면 {@link #add} 와 같은 409 를 낸다. 계정 활성 여부 같은 호출자별 정책은 호출자가 먼저 검사한다.
   *
   * @param tenantId 멤버를 붙일 테넌트 id
   * @param user 붙일 기존 계정
   * @param roleIds 부여할 역할 id(호출자가 기본 USER 까지 합쳐 넘긴다)
   * @return {@code created=false} 인 결과
   */
  public Result addExisting(long tenantId, UserResponse user, Collection<Long> roleIds) {
    rejectIfAlreadyMember(user.id(), tenantId);
    return attach(tenantId, () -> user, false, roleIds);
  }

  /**
   * 계정 확보(생성 또는 기존) → 멤버십 → 역할을 한 묶음으로 실행하고, 동시 요청으로 유니크 제약이 걸리면 409 로 번역한다.
   *
   * <p>계정 생성도 이 try 안에서 해야 한다 — 같은 이메일 동시 생성의 username 유니크 위반도 같은 409 로 바꾸기 위해서다.
   */
  private Result attach(
      long tenantId, Supplier<UserResponse> account, boolean created, Collection<Long> roleIds) {
    UserResponse user;
    try {
      // 계정 생성 규칙은 운영자 계정 생성(WD-46)과 공유하는 TemporaryAccountCreator 에 있다.
      user = account.get();
      membershipRepository.insertMember(user.id(), tenantId);
      for (Long roleId : roleIds) {
        userRepository.addRole(user.id(), roleId);
      }
    } catch (DuplicateKeyException e) {
      // 사전 검사(rejectIfAlreadyMember) 와 INSERT 사이에 같은 요청이 동시에 들어오면 유니크 제약이 먼저 잡는다.
      // 영어 "Data integrity violation" 이 그대로 노출되지 않도록 같은 409 로 번역한다.
      // CodedApiException(런타임) 이라 호출자 트랜잭션이 롤백한다 — 부분 생성된 계정/멤버십은 남지 않는다.
      throw new CodedApiException(
          HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS", "이미 이 워크스페이스의 멤버입니다");
    }
    return new Result(user, created);
  }

  /** 이미 이 테넌트 멤버면 409. 정지 멤버는 코드로 구분하고 userId 를 실어 웹의 상세 링크를 돕는다. */
  private void rejectIfAlreadyMember(Long userId, long tenantId) {
    Optional<TenantMembership> membership = membershipRepository.findInTenant(userId, tenantId);
    if (membership.isEmpty()) {
      return;
    }
    if (membership.get().isActive()) {
      throw new CodedApiException(
          HttpStatus.CONFLICT, "MEMBER_ALREADY_EXISTS", "이미 이 워크스페이스의 멤버입니다");
    }
    throw new CodedApiException(
        HttpStatus.CONFLICT,
        "MEMBER_SUSPENDED",
        "이미 이 워크스페이스의 멤버입니다(정지됨). 상세에서 재활성화하세요",
        Map.of("userId", String.valueOf(userId)));
  }
}
