-- V173: refresh_token 에 폐기 시각(revoked_at)을 더한다 (WD-1141).
--
-- 왜: 리프레시 토큰 회전을 커밋한 뒤에 도착한 진 탭의 재제시는 `revoked=true` 로 이미 걸려
--     재사용으로 판정됐고, 그때 로그인 패밀리 전체를 폐기해 세션이 살아 있던 이긴 탭까지 로그아웃됐다.
--     회전 직후 짧은 시간 안의 재제시는 "동시 갱신의 진 쪽"이므로 패밀리를 죽이지 않고 401 로 돌려보내기 위해,
--     이 토큰이 언제 폐기됐는지 알 수 있는 열이 필요하다.
--
-- 동작: 회전·로그아웃 등 모든 폐기 UPDATE 가 이 열을 함께 갱신한다.
--       유예 창(5초, RefreshTokenRotation.GRACE_WINDOW) 안에 재제시되면 패밀리 폐기 없이 401,
--       그 밖이거나 이 열이 NULL(마이그레이션 이전에 폐기된 행)이면 오늘처럼 재사용으로 패밀리 전체를 폐기한다.
--       유예 중에도 새 토큰은 발급하지 않으므로 탈취자는 얻을 것이 없다.
--
-- Tibero 짝: db/migration-tibero/V173 (MigrationParityTest).
ALTER TABLE refresh_token
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMP;

COMMENT ON COLUMN refresh_token.revoked_at
    IS 'revoked=true 로 바뀐 시각. NULL 은 유예 판정을 하지 않는다(마이그레이션 이전 행).';
