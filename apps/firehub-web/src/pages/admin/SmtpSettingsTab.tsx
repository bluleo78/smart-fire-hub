import { Mail, RotateCcw, Save, Send } from 'lucide-react';
import { toast } from 'sonner';

import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import { useTestSmtpSettings } from '../../hooks/queries/useProactiveMessages';
import type { SmtpForm, SmtpSettingsFormState } from '../../hooks/useSmtpSettingsForm';
import { PORT_MAX, PORT_MIN, SMTP_CONNECTION_KEYS } from '../../hooks/useSmtpSettingsForm';
import { ClearOverrideButton, SettingFieldLabel, SettingStateBadge } from './settings-lock';

/**
 * 번들 안에서 <b>비어 있는 항목</b>임을 알리는 정적 노트(디자인 스펙 §1-1 신설 어휘).
 *
 * 배지가 아니라 노트인 이유: 배지 문자열은 어휘를 영구히 넓혀 AI 탭까지 따라와야 하는 부담을
 * 만들지만, 노트는 이 화면 안에 머문다. 빈 입력창은 시각적으로 "아직 안 채운 칸"과 구별되지
 * 않으므로 이 텍스트가 유일한 전달 경로다 — 그래서 각 입력의 `aria-describedby` 에 포함한다.
 */
function EmptyInBundleNote({
  id,
  show,
  extra,
}: {
  id: string;
  show: boolean;
  /**
   * 비어 있을 때 <b>실제로 적용되는 값</b>이 따로 있는 키만 채운다. 포트가 그렇다 — 소비자 3곳이
   * 전부 빈 포트를 587 로 대체하므로, 이 문장이 없으면 "포트가 없어서 못 나간다"로 읽힌다.
   */
  extra?: string;
}) {
  if (!show) return null;
  return (
    <p id={id} className="text-sm text-muted-foreground">
      이 항목은 비어 있습니다 — 플랫폼 값이 사용되지 않습니다.{extra ? ` ${extra}` : ''}
    </p>
  );
}

/**
 * 이메일(SMTP) 설정 탭 — P7-c1 이후 <b>테넌트 상속/재정의 편집 화면</b>이자, #390-2b 이후
 * <b>표현 전용</b> 컴포넌트다. 폼 상태·번들 레이어는 `useSmtpSettingsForm` 이 갖고 있고
 * 그 인스턴스는 `SettingsPage` 가 소유한다.
 *
 * <b>왜 상태를 갖지 않는가</b>: Radix `TabsContent` 는 비활성 탭을 언마운트한다. 이 컴포넌트가
 * 상태를 소유하던 시절에는 탭을 바꾸는 순간 미저장 편집이 경고 없이 사라졌다 — AI 탭은 상태를
 * 페이지가 소유해 살아남는 비대칭이었고, 그 손실은 `smtp.password` 처럼 다른 시스템에서
 * 발급받아 붙여넣은 값에서 가장 아팠다.
 *
 * <b>`onDirtyChange` prop 은 없앴다.</b> 그 prop 과 언마운트 클린업(`return () => onDirtyChange(false)`)
 * 은 "언마운트가 편집을 죽이므로 dirty 도 같이 죽여야 한다"는 보정이었다. 편집이 안 죽으면
 * dirty 도 죽으면 안 된다 — 클린업을 남기면 살아 있는 편집을 dirty 아님으로 보고해 이탈 가드가
 * 침묵하고, 결함이 유실에서 <b>경고 누락</b>으로 모습만 바뀐다. 지금은 `SettingsPage` 가
 * `smtpReporter(smtp.base.hasChanges)` 를 직접 보고한다.
 *
 * <b>읽기는 `GET /settings?prefix=smtp`(해석된 값 + 플래그) 다.</b> 예전에는 전용
 * `GET /settings/smtp` 를 썼는데 그 경로는 <b>해석기를 타지 않아</b> 플랫폼 값을 보여줬다 —
 * 테넌트가 SMTP 를 재정의하면 메일은 테넌트 값으로 나가는데 화면은 플랫폼 값을 보여주는
 * 어긋남이 생긴다. 그래서 P7-c1 에서 그 전용 읽기 엔드포인트를 삭제했다.
 *
 * <b>이 탭의 네 라우트(조회·저장·해제·연결 테스트)는 전부 `ai:settings` 하나를 쓴다</b>
 * (`SettingsController` 참고). 예전에는 저장이 `ai:settings`, 바로 옆 "연결 테스트"가
 * `settings:write` 라 롤 편집으로 갈라질 수 있었다 — 도달 불가에 기대는 대신 갈라짐을 없앴다.
 *
 * <b>비밀번호는 서버가 마스킹(`****ab3f`)해서 주고 폼에 그대로 시드된다.</b> 사용자가 손대지 않으면
 * `form === original` 이라 페이로드에서 빠진다.
 *
 * <b>단, 마스크를 편집한 경우는 보호되지 않는다(알려진 결함, 이슈 참조).</b> 서버의 센티널 판정
 * (`SettingsService.isMaskSentinel`)은 `****` 로 시작하고 <b>길이가 정확히 4 또는 8</b>인 값만
 * 드롭한다. 그래서 마스크에 한 글자를 덧붙이면(`****3f2aX`, 길이 9) 센티널을 벗어나 <b>실제
 * 비밀번호가 그 문자열로 덮인다</b> — 저장 후 새 마스크가 보이므로 사용자가 알아챌 표면이 없다.
 * 근본 해법은 마스크를 편집 가능한 입력에 시드하지 않는 것이다(firehub-admin 은 c2a 에서
 * "비밀은 빈 입력 + 설정됨 힌트"로 다르게 풀었다). 여기 고치려면 서버 계약과 함께 봐야 한다.
 *
 * 표시/숨기기 토글은 두지 않는다 — 서버가 평문을 절대 내려주지 않아 눌러도 보여줄 것이 없다.
 */
export default function SmtpSettingsTab({ state }: { state: SmtpSettingsFormState }) {
  const testMutation = useTestSmtpSettings();
  const {
    base,
    isSaving,
    staleNotice,
    connectionGroupState,
    bundleTransitionPending,
    isEmptyInBundle,
    testNotice,
    handleSave,
    handleClearConnectionBundle,
  } = state;
  const {
    isLoading,
    loadFailed,
    isClearing,
    form,
    errors,
    fieldState,
    isEditable,
    hasChanges,
    updateField,
    handleReset,
    handleClearOverride,
    retryInitialLoad,
  } = base;

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  // 연결 5키는 그룹 머리의 버튼 하나가 대신하므로 여기 오지 않는다(§3).
  const clearAction = (key: keyof SmtpForm) =>
    !SMTP_CONNECTION_KEYS.includes(key) && fieldState(key) === 'overridden' ? (
      <ClearOverrideButton onConfirm={() => handleClearOverride(key)} disabled={isClearing} />
    ) : undefined;

  const handleTest = () => {
    testMutation.mutate(undefined, {
      onSuccess: (res) => {
        // 서버가 200 OK라도 success=false면 실패 (비정상 응답 처리)
        const data = res.data as { success?: boolean; message?: string } | undefined;
        if (data?.success === false) {
          toast.error(data.message ?? '연결 테스트에 실패했습니다.');
        } else {
          toast.success('SMTP 연결에 성공했습니다.');
        }
      },
      onError: () => toast.error('연결 테스트에 실패했습니다.'),
    });
  };

  if (isLoading) {
    return <div className="py-8 text-center text-muted-foreground text-sm">불러오는 중...</div>;
  }

  // 조회 실패는 **종단 상태**다. 편집 가능한 빈 폼 대신 원인과 재시도만 보여준다.
  if (loadFailed) {
    return (
      <div className="space-y-4 py-8 text-center">
        <p className="text-sm text-muted-foreground">
          SMTP 설정을 불러오지 못했습니다. 지금 적용 중인 값을 확인할 수 없어 편집을 열지 않습니다.
        </p>
        <Button variant="outline" onClick={retryInitialLoad}>
          <RotateCcw className="h-4 w-4" />
          다시 시도
        </Button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Mail className="h-4 w-4" />
            SMTP 서버 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* 탭 전체 맥락 한 줄 — 배너가 아니라 일반 텍스트다. 필드별 배지가 상태를 말하므로
              "탭 전체가 잠겼다"고 말하던 배너 두 개는 제거했다(공존하면 서로 다른 말을 한다). */}
          <p className="text-sm text-muted-foreground">
            SMTP 설정은 플랫폼 기본값을 따르며, 필요하면 우리 조직 값으로 재정의할 수 있습니다.
          </p>

          {/* "지금 화면을 믿지 말고 다시 읽어라" 안내. 토스트 한 번으로 끝내지 않는 이유는
              사용자가 다시 조작해야 하는 상태이고 토스트는 사라지기 때문이다(§6).

              **탭 범위에 둔다.** 예전에는 연결 그룹 `fieldset` 안에 있었는데, 그 자리는 원래
              입주자(번들 해제 부분 실패 — 연결 전용 사건)에게만 맞았다. 저장 후 재조회 실패는
              폼 전체 사건이라, `smtp.from_address` 하나만 저장하고 재조회가 실패하면 번들과
              아무 상관 없는 경고가 "지금은 플랫폼 기본값을 그대로 쓰고 있습니다" 문단 밑에
              붙어 사용자가 연결 설정이 잘못됐다고 읽는다. 상태는 탭 범위가 됐는데 표시만
              그룹 범위에 남아 있었다. */}
          {staleNotice && (
            <InlineBanner variant="warning">{staleNotice}</InlineBanner>
          )}

          {/* 연결 5키는 하나의 `fieldset` 으로 묶는다. 그룹 경계를 테두리로만 전달하면 스크린리더
              사용자가 "비밀번호 필드 하나"만 만났을 때 그것이 묶음의 일부라는 사실을 듣지 못한다 —
              `legend` 는 그룹 안 어느 필드에 도착하든 함께 읽힌다(§6). */}
          <fieldset
            className="space-y-6 rounded-md border p-4"
            aria-describedby={
              bundleTransitionPending
                ? 'smtp-connection-desc smtp-connection-warning'
                : 'smtp-connection-desc'
            }
          >
            <legend className="flex flex-wrap items-center gap-2 px-1 text-sm font-medium">
              연결 설정
              {/* 범위는 배지가 아니라 이 보조 문구가 짊어진다 — 배지 문자열을 새로 만들면 AI 탭까지
                  따라와야 하는 어휘 부담이 영구히 생긴다. */}
              <span className="font-normal text-muted-foreground">5개 항목이 함께 적용됩니다</span>
            </legend>

            <div className="space-y-2">
              <div className="flex flex-wrap items-center gap-2">
                {/* 그룹 배지 하나 + 필드 배지 0개. 다섯 필드가 전부 같은 배지를 달면 오히려
                    "각각 독립적으로 그런 상태다"로 읽혀 고치려던 오해를 배지가 다시 심는다. */}
                <SettingStateBadge state={connectionGroupState} />
                {connectionGroupState === 'overridden' && (
                  <ClearOverrideButton
                    onConfirm={handleClearConnectionBundle}
                    disabled={isClearing}
                    label="연결 설정 전체 재정의 해제"
                    dialogTitle="연결 설정 재정의 해제"
                    /* 5개 항목을 이름으로 나열한다 — "이 그룹"이라고 쓰면 사용자가 그룹 경계를
                       스크롤 밖에서 추정해야 한다. 비밀번호는 화면에 평문이 없어 다시 칠 수 없으므로
                       "복구할 수 없으며" 한 마디를 번들 문구에만 더한다. */
                    dialogDescription="SMTP 호스트, 포트, 사용자 이름, 비밀번호, STARTTLS 5개 항목의 테넌트 설정이 모두 삭제되고 플랫폼 기본값으로 전환됩니다. 입력한 비밀번호는 복구할 수 없으며, 필요하면 언제든 다시 재정의할 수 있습니다."
                  />
                )}
              </div>
              <p id="smtp-connection-desc" className="text-sm text-muted-foreground">
                {connectionGroupState === 'overridden'
                  ? '이 5개 항목은 우리 조직 값으로 적용되고 있습니다. 플랫폼 기본값은 이 중 어느 항목에도 더 이상 사용되지 않습니다.'
                  : '호스트·포트·사용자 이름·비밀번호·STARTTLS 는 한 서버에 대한 한 벌의 접속 정보이므로 항상 함께 적용됩니다. 지금은 플랫폼 기본값을 그대로 쓰고 있습니다.'}
              </p>
            </div>

            {/* 저장 예고(§2). 상태 전환은 저장 후에 그리고, 지금은 무슨 일이 일어날지만 말한다. */}
            {bundleTransitionPending && (
              <InlineBanner id="smtp-connection-warning" variant="warning">
                {/* "입력하지 않은 항목은 빈 값이 된다"고 뭉뚱그리면 STARTTLS 까지 꺼진다는 뜻이
                    되는데, 번들 채움은 그 키만 켜진 채로 둔다(RULING F). 그래서 빈 값이 되는 네
                    키를 이름으로 한정한다 — 문장을 늘리지 않으면서 거짓을 없애는 쪽이다. */}
                저장하면 연결 설정 5개 항목이 모두 우리 조직 값으로 전환됩니다. 입력하지 않은
                호스트·포트·사용자 이름·비밀번호는 빈 값이 되며, 플랫폼의 사용자 이름·비밀번호는 더
                이상 사용되지 않습니다. 인증이 필요한 서버라면 지금 사용자 이름과 비밀번호도 함께
                입력하세요.
              </InlineBanner>
            )}

            {/* Host */}
            <div className="space-y-2">
              <Label htmlFor="smtp-host">SMTP 호스트</Label>
              <Input
                id="smtp-host"
                className="max-w-md"
                value={form['smtp.host']}
                disabled={!isEditable('smtp.host')}
                onChange={(e) => updateField('smtp.host', e.target.value)}
                placeholder="smtp.gmail.com"
                aria-describedby={
                  isEmptyInBundle('smtp.host') ? 'smtp-host-desc smtp-host-empty' : 'smtp-host-desc'
                }
              />
              <p id="smtp-host-desc" className="text-sm text-muted-foreground">
                발신 메일 서버 주소
              </p>
              <EmptyInBundleNote id="smtp-host-empty" show={isEmptyInBundle('smtp.host')} />
            </div>

            {/* Port */}
            <div className="space-y-2">
              <Label htmlFor="smtp-port">포트</Label>
              <Input
                id="smtp-port"
                type="number"
                min={PORT_MIN}
                max={PORT_MAX}
                className="max-w-[120px]"
                value={form['smtp.port']}
                disabled={!isEditable('smtp.port')}
                onChange={(e) => updateField('smtp.port', e.target.value)}
                placeholder="587"
                aria-describedby={isEmptyInBundle('smtp.port') ? 'smtp-port-empty' : undefined}
              />
              {errors['smtp.port'] && (
                <p className="text-sm text-destructive">{errors['smtp.port']}</p>
              )}
              <EmptyInBundleNote
                id="smtp-port-empty"
                show={isEmptyInBundle('smtp.port')}
                extra="기본 포트 587 로 접속합니다."
              />
            </div>

            {/* Username */}
            <div className="space-y-2">
              <Label htmlFor="smtp-username">사용자 이름</Label>
              <Input
                id="smtp-username"
                className="max-w-md"
                value={form['smtp.username']}
                disabled={!isEditable('smtp.username')}
                onChange={(e) => updateField('smtp.username', e.target.value)}
                placeholder="user@example.com"
                aria-describedby={
                  isEmptyInBundle('smtp.username')
                    ? 'smtp-username-desc smtp-username-empty'
                    : 'smtp-username-desc'
                }
              />
              <p id="smtp-username-desc" className="text-sm text-muted-foreground">
                인증 없는 릴레이라면 비워 둘 수 있습니다
              </p>
              <EmptyInBundleNote id="smtp-username-empty" show={isEmptyInBundle('smtp.username')} />
            </div>

            {/* Password — 마스킹된 값이 그대로 시드된다. 표시/숨기기 토글을 두지 않는 이유는
                눌러도 보여줄 평문이 서버에서 오지 않기 때문이다. */}
            <div className="space-y-2">
              <Label htmlFor="smtp-password">비밀번호</Label>
              <Input
                id="smtp-password"
                type="password"
                className="max-w-md"
                value={form['smtp.password']}
                disabled={!isEditable('smtp.password')}
                onChange={(e) => updateField('smtp.password', e.target.value)}
                aria-describedby="smtp-password-desc"
              />
              {/* 번들 재정의 상태의 빈 비밀번호는 §1-1 노트가 기존 두 분기를 **대체**한다 —
                  "설정된 비밀번호가 없습니다 (인증 없는 SMTP)"는 사용자가 의도해서 비운 것처럼
                  읽혀, 실제로는 자격증명 없이 릴레이를 시도하게 된 위험을 감춘다. */}
              {isEmptyInBundle('smtp.password') ? (
                <EmptyInBundleNote id="smtp-password-desc" show />
              ) : (
                <p id="smtp-password-desc" className="text-sm text-muted-foreground">
                  {form['smtp.password'] === ''
                    ? '설정된 비밀번호가 없습니다 (인증 없는 SMTP)'
                    : '현재 비밀번호가 설정되어 있습니다. 값을 바꾸려면 새 비밀번호를 입력하세요.'}
                </p>
              )}
            </div>

            {/* STARTTLS — Switch 는 라벨 오른쪽에 놓이는 배치라 왼쪽 열에 라벨+설명을 직접 조합한다. */}
            <div className="flex items-center justify-between max-w-md">
              <div className="space-y-1">
                <Label htmlFor="smtp-starttls">STARTTLS 사용</Label>
                <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
                {/* 이 필드에는 빈 항목 노트가 없다. 번들 채움이 `smtp.starttls` 만 'true' 로
                    채우므로(RULING F) **번들 채움으로는** 이 키가 빈 값으로 내려올 수 없다 —
                    노트를 달아 두면 도달 불가 방어 코드가 된다.
                    ("어떤 경로로도 불가능"이라고는 적지 않는다: PUT 으로 빈 문자열을 직접 보내면
                    행이 실재해 채움을 건너뛰고 그대로 해석된다. 다만 그것은 'false' 를 보내는 것과
                    같은 명시적 조작이고, Switch 가 꺼짐으로 그려져 화면이 거짓말을 하지 않는다.) */}
              </div>
              <Switch
                id="smtp-starttls"
                aria-label="STARTTLS 사용"
                checked={form['smtp.starttls'] === 'true'}
                disabled={!isEditable('smtp.starttls')}
                onCheckedChange={(checked) => updateField('smtp.starttls', checked ? 'true' : 'false')}
              />
            </div>
          </fieldset>

          <Separator />

          {/* From Address */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-from"
              state={fieldState('smtp.from_address')}
              action={clearAction('smtp.from_address')}
            >
              발신자 주소
            </SettingFieldLabel>
            <Input
              id="smtp-from"
              className="max-w-md"
              value={form['smtp.from_address']}
              disabled={!isEditable('smtp.from_address')}
              onChange={(e) => updateField('smtp.from_address', e.target.value)}
              placeholder="noreply@example.com"
            />
            {/* 그룹 밖 + 개별 배지 + 개별 해제 버튼. 구분은 구조가 하고, 텍스트가 한 번 더 말한다(§4). */}
            <p className="text-sm text-muted-foreground">
              이메일 발신자로 표시되는 주소. 접속 정보와 무관하게 이 항목만 따로 재정의할 수 있습니다.
            </p>
          </div>
        </CardContent>
      </Card>

      {/* 액션 행 하나에 저장·되돌리기·연결 테스트를 모두 둔다 — 행을 둘로 나누면 화면에 액션
          영역이 두 개 생겨 어느 것이 주 동작인지 흐려진다. */}
      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
          <Save className="h-4 w-4" />
          {isSaving ? '저장 중...' : '저장'}
        </Button>
        <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
          <RotateCcw className="h-4 w-4" />
          되돌리기
        </Button>
        <div className="ml-auto flex items-center gap-3">
          {/* 연결 테스트는 <b>서버에 저장된</b> 설정으로 접속한다 — 편집 중인 폼 값이 아니다.
              버튼을 막지 않는 이유: 지금 실제로 적용 중인 값을 확인하려는 것도 유효한 용도라
              (AI 탭의 "인증 확인"이 dirty 에서 비활성인 것과 다르다) 막으면 그 진단을 없앤다.
              대신 dirty 인 동안 무엇으로 테스트하는지 문자열로 알려 거짓 결과 해석을 막는다. */}
          {/* 우선순위·배타성의 근거는 testNotice 선언부에 있다 — 여기서는 고른 문자열을 그릴 뿐이다. */}
          {testNotice && <p className="max-w-md text-sm text-muted-foreground">{testNotice}</p>}
          <Button variant="outline" onClick={handleTest} disabled={testMutation.isPending}>
            <Send className="h-4 w-4" />
            {testMutation.isPending ? '테스트 중...' : '연결 테스트'}
          </Button>
        </div>
      </div>
    </div>
  );
}
