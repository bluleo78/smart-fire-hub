-- V144: 분석 자산 관리(관리자) 권한 analytics:admin 신설 (WD-199).
--
-- 왜 필요한가: 대시보드·차트·저장 쿼리의 수정·삭제는 작성자 한정이다(created_by = 요청자). 작성자가 정지·제거되면
-- 그가 공유한 분석 자산은 다른 사람에게 계속 보이고 실행되는데, 정지·제거된 작성자는 로그인도 못 하므로 아무도
-- 공유를 해제하거나 지울 수 없었다(관리자도 404). 이 권한을 가진 사람은 워크스페이스 전체 분석 자산을 최소 정보로
-- 보고 공유 해제·삭제만 할 수 있다(남의 비공개 내용 열람·편집·실행은 여전히 작성자만).
--
-- 시드 방식은 V143(proactive:admin) 과 같다: permission 은 전역 카탈로그, role_permission 은 테넌트별(RLS) 이라
-- tenant_id 를 역할 행에서 그대로 옮긴다. 마이그레이션은 소유자 계정으로 돌아 RLS 를 받지 않으므로 모든 테넌트의
-- 시스템 ADMIN 역할에 한 번에 부여된다. 이후 새로 만들어지는 테넌트는 provision_tenant_defaults 가 기본 테넌트(1)의
-- 시스템 역할 권한을 통째로 복제하므로 함수를 다시 정의할 필요가 없다.
--
-- 역할 관리 화면은 「코드 - 설명」으로 보이므로 한국어 표기는 description 에 싣는다.
INSERT INTO permission (code, description, category) VALUES
  ('analytics:admin', '분석 자산 관리(관리자) — 워크스페이스의 모든 대시보드·차트·저장 쿼리를 보고 공유 해제하거나 삭제', 'analytics')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_id)
SELECT r.tenant_id, r.id, p.id
FROM role r
JOIN permission p ON p.code = 'analytics:admin'
WHERE r.name = 'ADMIN' AND r.is_system
ON CONFLICT DO NOTHING;
