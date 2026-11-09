package com.smartfirehub.securitylevel.access;

import com.smartfirehub.global.tenant.TenantContext;
import com.smartfirehub.permission.service.PermissionService;
import com.smartfirehub.securitylevel.repository.DatasetAccessRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** 사용자 → {@link Clearance}. 요청 경로에서는 요청 속성에 캐시해 한 요청 안의 여러 판정이 DB 를 한 번만 읽게 한다. */
@Service
@RequiredArgsConstructor
public class ClearanceResolver {

  /** 요청 속성 키 — 같은 요청의 반복 판정에서 재사용. */
  static final String REQUEST_ATTR = ClearanceResolver.class.getName() + ".clearance";

  private final DatasetAccessRepository accessRepository;
  private final PermissionService permissionService;

  /**
   * 현재 테넌트(TenantContext) 기준으로 계산한다. 비요청 경로(러너·폴러)가 쓴다.
   *
   * <p>여기에 @Transactional 을 두지 않는다: current() 의 자기 호출은 프록시를 우회해 어차피 무효이고, 트랜잭션 보장은 각 리포지토리·서비스
   * (DatasetAccessRepository 클래스 레벨, PermissionService)가 맡는다.
   */
  public Clearance resolve(long userId) {
    long tenantId = TenantContext.require("열람 자격 계산");
    var row = accessRepository.findClearanceRow(userId, tenantId);
    if (row.maxRank() == null) {
      return Clearance.none(userId, tenantId);
    }
    return new Clearance(
        userId,
        tenantId,
        row.maxRank(),
        row.roleIds(),
        row.tenantAdmin(),
        permissionService.getUserPermissions(userId));
  }

  /**
   * 현재 요청 사용자의 자격. principal 은 {@code Long userId}(JwtAuthenticationFilter 계약). 인증이 없으면 아무것도 못 보는
   * 자격(fail-closed). 요청 경로에서는 요청 속성에 캐시한 값을 돌려준다 — 한 요청 안의 판정은 모두 같은 자격을 본다. 실행 직전 재판정만 {@link
   * #readFresh()} 를 쓴다.
   */
  public Clearance current() {
    RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      Object cached = attrs.getAttribute(REQUEST_ATTR, RequestAttributes.SCOPE_REQUEST);
      if (cached instanceof Clearance c) {
        return c;
      }
    }
    Clearance c = readFresh();
    if (attrs != null) {
      attrs.setAttribute(REQUEST_ATTR, c, RequestAttributes.SCOPE_REQUEST);
    }
    return c;
  }

  /**
   * 요청 캐시를 보지 않고 현재 요청 사용자의 자격을 DB 에서 새로 읽어 돌려준다. <b>실행 직전 재판정 전용</b>이다 — 데이터셋 /query 는 허가 대기 전에 앞
   * 판정을 하고 허가를 얻은 뒤 다시 판정하는데(WD-638), 기다리는 동안 관리자가 열람자 등급을 낮췄으면 캐시된 자격으로는 낡은 판정이 된다(WD-682).
   *
   * <p>요청 캐시는 <b>건드리지 않는다</b> — 갈아 끼우면 같은 요청의 이후 {@link #current()} 호출이 앞뒤로 다른 자격을 보게 된다. 요청
   * 밖(스케줄러·러너 — 요청 속성 없음)에서 불러도 안전하다({@link #current()} 와 같은 규칙으로 계산만 한다).
   */
  public Clearance readFresh() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof Long userId
        ? resolve(userId)
        : Clearance.none(-1L, TenantContext.require("열람 자격 계산"));
  }
}
