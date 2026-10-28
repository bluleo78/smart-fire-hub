-- WD-271: 프로필 이메일의 전역 유일 인덱스를 없앤다.
-- 프로필 이메일은 소유 확인 없이 바꿀 수 있는 연락처라, 유일성을 걸면 아무 멤버나 남의 주소를 먼저 저장해
-- 그 주소 주인의 계정 생성(멤버 추가·운영자 계정 생성 — username = 소문자 이메일)을 막을 수 있었다.
-- 계정 식별은 username 의 유일 제약(user_username_key)만으로 한다. 기존 데이터는 바꾸지 않는다.
DROP INDEX IF EXISTS idx_user_email_unique;
