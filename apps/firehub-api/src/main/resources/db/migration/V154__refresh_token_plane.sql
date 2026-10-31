-- V154: refresh_token 에 평면(WEB/PLATFORM) 구분을 둔다 (WD-314).
--
-- 무엇: plane 컬럼(기본 WEB)을 더한다. 운영자 콘솔 로그인·갱신이 저장하는 토큰은 PLATFORM 으로 저장된다.
--
-- 왜: refresh_token 은 두 평면이 함께 쓰는 전역 테이블인데 평면 구분이 없어, 웹 로그아웃(사용자 단위 폐기)이 같은 사람의
--     운영자 콘솔 세션까지 끊었다. 운영자이면서 워크스페이스 사용자인 계정이 웹에서 로그아웃하면 콘솔을 새로고침하는 순간
--     로그인 화면으로 튕긴다. 평면 컬럼이 있어야 웹 로그아웃이 웹 세션만 폐기할 수 있다(PlatformAuthService.logout 주석의 백로그).
--
-- 기존 행: 어느 평면 토큰인지 알 수 없어 WEB 으로 둔다. 운영자 콘솔 세션은 다음 로그인부터 PLATFORM 으로 저장되고, 기존 세션은
--     만료(회전)되면 사라진다 — 그 사이 웹 로그아웃이 기존 콘솔 세션을 끊는 것은 지금과 같은 동작이라 더 나빠지지 않는다.
--
-- 되돌리는 법: 이 파일을 편집하지 말고 새 마이그레이션으로 컬럼을 지운다(평면별 폐기 코드도 함께 되돌려야 한다).

ALTER TABLE refresh_token
    ADD COLUMN plane VARCHAR(16) NOT NULL DEFAULT 'WEB'
        CONSTRAINT chk_refresh_token_plane CHECK (plane IN ('WEB', 'PLATFORM'));
