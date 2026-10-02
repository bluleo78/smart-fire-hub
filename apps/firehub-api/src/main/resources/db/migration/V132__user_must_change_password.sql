-- V132: 관리자가 임시 비밀번호로 만든 계정에 "첫 로그인 시 비밀번호 변경 필요" 표식을 단다(WD-2).
-- 기본값 false 라 기존 사용자는 영향이 없다. 표식은 본인이 PUT /users/me/password 에 성공하면 내려간다.
-- "user" 는 전역 테이블(RLS 없음)이므로 정책 추가가 필요 없다.
ALTER TABLE "user" ADD COLUMN IF NOT EXISTS must_change_password BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN "user".must_change_password IS
  '관리자가 임시 비밀번호로 생성한 계정 — 본인이 비밀번호를 바꾸기 전까지 true (access token 클레임 pwc 의 원천)';
