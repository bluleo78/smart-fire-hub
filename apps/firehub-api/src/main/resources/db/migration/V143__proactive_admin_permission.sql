-- V143: 스마트 작업 관리(관리자) 권한 proactive:admin 신설 (WD-192).
--
-- 왜 필요한가: 스마트 작업의 조회·수정·삭제는 작성자 한정이다. 작성자가 정지·제거되거나 스마트 작업 쓰기 권한을
-- 잃으면 작업은 「활성」인 채 실행 때마다 건너뛰는데(WD-166 결정 — 자동으로 끄지 않는다), 정지·제거된 작성자는
-- 로그인도 못 하므로 아무도 그 작업을 정리할 수 없었다. 이 권한을 가진 사람은 워크스페이스 전체 작업을 최소 정보로
-- 보고 끄거나 삭제할 수 있다(열람·편집·켜기·지금 실행은 여전히 작성자만).
--
-- 시드 방식은 V133 과 같다: permission 은 전역 카탈로그, role_permission 은 테넌트별(RLS) 이라 tenant_id 를
-- 역할 행에서 그대로 옮긴다. 마이그레이션은 소유자 계정으로 돌아 RLS 를 받지 않으므로 모든 테넌트의 시스템 ADMIN
-- 역할에 한 번에 부여된다. 이후 새로 만들어지는 테넌트는 provision_tenant_defaults 가 기본 테넌트(1)의 시스템
-- 역할 권한을 통째로 복제하므로 함수를 다시 정의할 필요가 없다.
--
-- 역할 관리 화면은 「코드 - 설명」으로 보이므로 한국어 표기는 description 에 싣는다.
INSERT INTO permission (code, description, category) VALUES
  ('proactive:admin', '스마트 작업 관리(관리자) — 워크스페이스의 모든 스마트 작업을 보고 끄거나 삭제', 'proactive')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permission (tenant_id, role_id, permission_id)
SELECT r.tenant_id, r.id, p.id
FROM role r
JOIN permission p ON p.code = 'proactive:admin'
WHERE r.name = 'ADMIN' AND r.is_system
ON CONFLICT DO NOTHING;
