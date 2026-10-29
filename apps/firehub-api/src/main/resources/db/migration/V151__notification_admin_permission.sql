-- V151: 알림 관리(관리자) 권한 notification:admin 신설 (WD-433).
--
-- 왜 필요한가: 알림 발송 대기열 관리 API(/admin/notifications — 정체 목록·다시 보내기)와 Slack 워크스페이스 설치 URL
-- 발급(/oauth/slack/auth-url)은 관리자 전용으로 @PreAuthorize("hasRole('ADMIN')") 를 달고 있었지만, 메서드 보안
-- (@EnableMethodSecurity)이 켜져 있지 않아 평가되지 않았고 로그인한 누구나 호출할 수 있었다. 메서드 보안을 켜도
-- JwtAuthenticationFilter 는 권한 코드만 authority 로 넣어(ROLE_ADMIN 없음) 실제 관리자까지 403 이 된다. 그래서 나머지
-- 컨트롤러와 같은 @RequirePermission 으로 바꾸고, 그 판정에 쓸 전용 권한을 여기서 만든다.
--
-- 시드 방식은 V143(proactive:admin)·V144(analytics:admin) 과 같다: permission 은 전역 카탈로그, role_permission 은
-- 테넌트별(RLS) 이라 tenant_id 를 역할 행에서 그대로 옮긴다. 마이그레이션은 소유자 계정으로 돌아 RLS 를 받지 않으므로
-- 모든 테넌트의 시스템 ADMIN 역할에 한 번에 부여된다. 이후 새로 만들어지는 테넌트는 provision_tenant_defaults 가 기본
-- 테넌트(1)의 시스템 역할 권한을 통째로 복제한다.
INSERT INTO permission (code, description, category) VALUES
  ('notification:admin', '알림 관리(관리자) — 워크스페이스의 알림 발송 대기열을 보고 실패한 알림을 다시 보내며, Slack 워크스페이스를 설치', 'notification')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_id)
SELECT r.tenant_id, r.id, p.id
FROM role r
JOIN permission p ON p.code = 'notification:admin'
WHERE r.name = 'ADMIN' AND r.is_system
ON CONFLICT DO NOTHING;
