-- V153: 워크스페이스(테넌트) 역할에 붙어 있는 플랫폼 평면 권한(category='platform') 부여 행을 지운다 (WD-280).
--
-- 무엇: role_permission 에서 permission.category='platform' 인 행만 지운다. 권한 카탈로그(permission)와
--       플랫폼 롤 부여(platform_role_permission)는 건드리지 않는다 — 운영자 콘솔은 그대로 쓴다.
--
-- 왜: 카탈로그를 두 평면이 함께 쓰게 된 뒤(V82) 워크스페이스 역할 편집 화면이 플랫폼 권한까지 보여 주고 저장도 됐다.
--     하지만 테넌트 토큰으로는 플랫폼 API 에 닿지 못하므로(평면 불일치 403) 그 부여는 아무 효과가 없고, 관리자에게
--     「이 역할에 테넌트 생성·정지를 맡겼다」는 오해만 남긴다. 이제 서버가 그런 저장을 400 으로 거부하므로, 이미 저장된
--     행도 함께 정리해 화면·감사·위임 범위 검사(RoleGrantGuard)에 남지 않게 한다 — 남겨 두면 위임 관리자는 플랫폼 권한을
--     가질 수 없어 그 역할을 영영 편집하지 못한다.
--
-- 되돌리는 법: 이 파일을 편집하지 말고 새 마이그레이션으로. 다만 되살릴 이유가 없다 — 부여해도 효과가 없는 행이다.

DELETE FROM role_permission rp
USING permission p
WHERE rp.permission_id = p.id
  AND p.category = 'platform';
