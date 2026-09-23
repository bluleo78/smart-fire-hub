# SMTP 설정 테넌트 전용 전환 (#712)

## 배경
#706 에서 AI 자격증명과 AI 설정을 플랫폼에서 걷어내 워크스페이스(테넌트) 전용으로 만들었다.
하지만 SMTP 6키(`smtp.host/port/username/password/starttls/from_address`)는 아직 `TWO_PLANE` 이다.
즉 플랫폼 기본값(`system_settings`)과 워크스페이스 값(`tenant_settings`)을 함께 쓴다.
그래서 관리자 앱과 웹 곳곳에 "재정의" 정신모델(배지, 해제 버튼, 상속 안내)이 남아 있다.

사용자 결정(2026-09-23):
- SMTP 도 테넌트 전용으로 전환한다. 문구만 다듬는 것이 아니다.
- 워크스페이스 이메일 탭의 "설정 해제"는 6칸을 한 묶음으로 지운다. 발신자 주소도 포함한다.
- 관리자 앱 플랫폼 설정은 SMTP 가 빠지면 임베딩만 남는다. 탭바 없이 임베딩 카드만 보여 준다.
- 플랫폼 SMTP 값을 워크스페이스로 복사하지 않는다. 미설정이면 명확한 오류가 나는 것이 의도다.

## 동작
- 워크스페이스는 자기 SMTP 6키만 쓴다. 값이 없으면 미설정이다.
  - 미설정이면 발송은 기존처럼 실패한다(`SMTP 호스트 미설정` 계열).
  - 오류 문구는 워크스페이스 설정 › 이메일에서 등록하라고 안내한다.
- 테넌트 컨텍스트가 없는 호출도 미설정으로 본다. 플랫폼 폴백은 없다.
  - `SettingsResolutionTest.컨텍스트_없으면_플랫폼_SMTP_로_폴백한다` 를 뒤집는다.
- "연결 5키 원자 해석(번들)" 규칙은 사라진다. 섞일 다른 평면이 없기 때문이다.
  - `applySmtpConnectionBundle`, `rejectBundleKey` 와 관련 javadoc 은 필요 없으면 삭제한다.
- 저장 시 검증은 유지한다: 포트 범위, 마스킹 센티널 제거, 비밀번호 암호화.
- 워크스페이스 "설정 해제"는 SMTP 6키 행을 한 번에 지운다.
  - 새 엔드포인트 `DELETE /api/v1/settings/smtp` 를 쓴다. 권한은 기존 SMTP 설정과 같다.
  - 옛 `DELETE /api/v1/settings/overrides/{key}` 는 SMTP 외 사용처가 없으면 삭제한다.
- 플랫폼 API
  - `GET /api/platform/settings` 는 SMTP 키를 내려주지 않는다.
  - `PUT /api/platform/settings` 는 `smtp.*` 를 400 으로 거부한다. #706 의 `ai.*` 거부와 같다.

## 평면(SettingsOverridePolicy)
- `TWO_PLANE` 평면과 `twoPlaneKeys()` 를 삭제한다. 사용처가 남지 않는다.
- SMTP 6키는 "테넌트 값만, 코드 기본값 없음"이 된다.
  - 지금 `TENANT_ONLY` 는 "테넌트 → `AiBehaviorDefaults` 코드 기본값"이다.
  - `mayHavePlatformRows` 는 `ai` 접두어로 판정한다.
  - 두 곳을 일반화해 SMTP 를 `TENANT_ONLY` 에 넣는다. 기본값이 없는 키는 빈 값으로 해석한다.
  - 새 평면보다 이 방식이 단순하면 이 방식을 택하고, 이유를 주석에 남긴다.
- 테넌트 쓰기 허용 목록(`TENANT_WRITABLE`)에는 SMTP 6키와 AI 행동 키가 남는다.

## 마이그레이션
- `V128__drop_platform_smtp_settings.sql`: `DELETE FROM system_settings WHERE key LIKE 'smtp.%';`
  - 멱등이고 복사는 없다.
  - V126/V127 형식의 긴 주석으로 이유를 남긴다.
- `tenant_settings` 는 건드리지 않는다.
- **번호 주의**: #699·#713 도 V128 을 원할 수 있다. 병합 직전에 실제 main 의 마이그레이션 목록을 다시 확인한다.

## 웹(firehub-web) 이메일 탭
- 상속/재정의 UI를 모두 없앤다.
  - 대상: `SettingStateBadge` 의 inherited/overridden, `ClearOverrideButton`, `EmptyInBundleNote`, "플랫폼 기본값을 따르며…" 문구, 칸별 해제 버튼.
- 미설정 상태: 안내 문구와 빈 폼을 보여 준다.
- 설정 상태: 저장 버튼과 "설정 해제" 버튼을 둔다.
  - "설정 해제"는 확인 다이얼로그 뒤 `DELETE /api/v1/settings/smtp` 를 호출한다.
- 연결 테스트(`POST /settings/smtp/test`)는 유지한다.
- `settings-lock.tsx`·`settings-fields.ts` 에는 임베딩이 쓰는 `locked` 표현만 남긴다. SMTP 전용 조각은 삭제한다.
- `useSmtpSettingsForm` 에서 번들·상속 로직을 걷어낸다.
- `useSettingsOverrideForm` 은 AI 행동 폼이 쓰면 유지한다. 해제 기능은 사용처가 없으면 걷어낸다.
- 새 문구에 "재정의/오버라이드/상속/플랫폼 기본값" 표현을 쓰지 않는다.

## 관리자 앱(firehub-admin)
- 플랫폼 설정 화면
  - SMTP 항목과 탭바를 없애고 임베딩 카드만 렌더한다.
  - `override-policy.ts` 와 "테넌트 재정의 가능" 배지·안내 문구를 삭제한다.
  - `SaveConfirmDialog` 문구는 "모든 워크스페이스에 즉시 적용" 식으로 바꾼다.
- 함께 정리할 곳: `settings-catalog.ts`, `types/platform.ts`, `build-payload`, 단위 테스트, e2e(`settings.spec.ts`, `factories/platform.factory.ts`).

## 테스트
- 백엔드
  - 새 `SmtpSettingsTenantOnlyTest` 는 `AiSettingsTenantOnlyTest` 형식을 따르고 다음을 증명한다.
    - 플랫폼 행이 있어도 발송 설정에 쓰이지 않는다.
    - 컨텍스트가 없으면 미설정이다.
    - 플랫폼 PUT `smtp.*` 는 400 이다.
    - 테넌트 해제는 6키를 삭제한다.
  - 기존 SMTP 테스트는 뒤집거나 삭제한다: `SettingsResolutionTest`, `SmtpSettingsServiceTest`, `SettingsWritePlaneTest`, `SettingsOverridePolicyTest`, `SettingsKeyWhitelistInvariantTest`, `SettingsControllerTest`, `PlatformSettingsControllerTest`.
  - 전체 스위트를 실행한다.
- 웹
  - `e2e/pages/admin/settings.spec.ts` 의 이메일 탭 describe 를 새 계약으로 다시 쓴다.
  - 단언: 미설정 안내, 저장 페이로드, 설정 해제 → DELETE 호출, 재정의/상속 문구 부재.
  - dirty-guard 테스트는 유지한다.
- 관리자: e2e 와 단위 테스트를 정리한다. 임베딩만 보이고 탭바와 SMTP 필드는 없어야 한다.

## 배포 주의
- api + web + admin 을 동시에 배포해야 한다.
- 운영 플랫폼 SMTP 값은 V128 로 삭제된다.
  - 자기 SMTP 를 등록하지 않은 워크스페이스는 알림·리포트 메일이 실패한다.
  - 배포 전에 운영 `system_settings` SMTP 값과 워크스페이스 설정 현황을 확인한다.
- `.claude/docs/deploy.md` 에 V128 배포 노트를 추가한다.
