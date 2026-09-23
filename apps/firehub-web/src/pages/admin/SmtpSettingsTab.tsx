import { Info, Mail, RotateCcw, Save, Send } from 'lucide-react';
import { useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { FormField } from '../../components/ui/form-field';
import { InlineBanner } from '../../components/ui/inline-banner';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import { useTestSmtpSettings } from '../../hooks/queries/useProactiveMessages';
import type { SmtpSettingsFormState } from '../../hooks/useSmtpSettingsForm';
import { PORT_MAX, PORT_MIN, SMTP_FIELD_META } from '../../hooks/useSmtpSettingsForm';
import { ClearConfirmDialog } from './ClearConfirmDialog';

const CLEAR_LABEL = '설정 해제';
const CLEAR_TITLE = 'SMTP 설정 해제';
// 6개 항목을 이름으로 나열한다 — 발신자 주소까지 함께 지워진다는 사실을 누르기 전에 알린다.
const CLEAR_BODY =
  'SMTP 호스트, 포트, 사용자 이름, 비밀번호, STARTTLS, 발신자 주소 6개 항목이 모두 삭제됩니다. 해제하면 이 워크스페이스의 이메일 알림과 리포트 메일이 발송되지 않으며, 저장된 비밀번호는 복구할 수 없습니다.';

/**
 * 이메일(SMTP) 설정 탭 — 워크스페이스 전용 SMTP 6키(#712). <b>표현 전용</b> 컴포넌트다: 폼 상태는
 * `useSmtpSettingsForm` 이 갖고 그 인스턴스는 `SettingsPage` 가 소유한다(탭 전환에도 편집이 살아남는다).
 *
 * - 미설정: 안내 배너 + 빈 폼. 저장하면 설정된 상태가 된다.
 * - 설정됨: 저장된 값 + 하단 좌측 "설정 해제"(6키를 한 번에 지우므로 확인창을 거친다).
 * 레이아웃은 AI 분류 탭(`AiClassifySettingsTab`)과 같다 — 해제는 좌측, 저장은 우측.
 *
 * <b>비밀번호 칸은 항상 빈 칸으로 시작한다</b>(서버 마스크를 편집 가능한 입력에 넣지 않는다 —
 * 이유는 `useSmtpSettingsForm` 의 `seedFrom`). 저장된 비밀번호가 있으면 안내문이 그 사실과
 * "비워 두면 유지된다"를 알린다.
 */
export default function SmtpSettingsTab({ state }: { state: SmtpSettingsFormState }) {
  const testMutation = useTestSmtpSettings();
  const [clearOpen, setClearOpen] = useState(false);
  const {
    isLoading,
    loadFailed,
    configured,
    passwordSaved,
    form,
    errors,
    hasChanges,
    isSaving,
    isClearing,
    staleNotice,
    testNotice,
    updateField,
    handleReset,
    handleSave,
    handleClear,
    retryInitialLoad,
  } = state;

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

  // 조회 실패는 <b>종단 상태</b>다. 편집 가능한 빈 폼을 그리면 "미설정"으로 오인해 저장으로
  // 기존 설정을 덮어쓸 수 있으므로 원인과 재시도만 보여준다.
  if (loadFailed) {
    return (
      <div className="space-y-4 py-8 text-center">
        <p className="text-sm text-muted-foreground">
          SMTP 설정을 불러오지 못했습니다. 지금 저장된 값을 확인할 수 없어 편집을 열지 않습니다.
        </p>
        <Button variant="outline" onClick={retryInitialLoad}>
          <RotateCcw className="h-4 w-4" />
          다시 시도
        </Button>
      </div>
    );
  }

  const busy = isSaving || isClearing;
  // 라벨·필수 표시는 훅의 단일 표(SMTP_FIELDS)에서 읽는다 — 검증 규칙과 화면 표시가 갈라지지 않게.
  const meta = SMTP_FIELD_META;

  return (
    <Card className="card-hover">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Mail className="h-4 w-4" />
          SMTP 서버 설정
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* "지금 화면을 믿지 말고 다시 읽어라" 안내 — 사용자가 다시 조작해야 하는 상태라 사라지는
            토스트가 아니라 지속 배너로 둔다. */}
        {staleNotice && <InlineBanner variant="warning">{staleNotice}</InlineBanner>}

        {configured ? (
          <p className="text-sm text-muted-foreground">
            이 워크스페이스의 이메일 알림과 리포트 메일은 아래 SMTP 서버로 발송됩니다.
          </p>
        ) : (
          <InlineBanner variant="info" icon={<Info />}>
            SMTP 서버가 설정되지 않았습니다. 이메일 알림과 리포트 메일을 보내려면 아래 정보를 입력하고
            저장하세요.
          </InlineBanner>
        )}

        <FormField
          label={meta['smtp.host'].label}
          htmlFor="smtp-host"
          required={meta['smtp.host'].required}
          error={errors['smtp.host']}
        >
          <Input
            id="smtp-host"
            className="max-w-md"
            value={form['smtp.host']}
            onChange={(e) => updateField('smtp.host', e.target.value)}
            placeholder="smtp.gmail.com"
            aria-invalid={errors['smtp.host'] ? true : undefined}
            aria-describedby="smtp-host-desc"
          />
          <p id="smtp-host-desc" className="text-sm text-muted-foreground">
            발신 메일 서버 주소
          </p>
        </FormField>

        <FormField
          label={meta['smtp.port'].label}
          htmlFor="smtp-port"
          required={meta['smtp.port'].required}
          error={errors['smtp.port']}
        >
          <Input
            id="smtp-port"
            type="number"
            min={PORT_MIN}
            max={PORT_MAX}
            className="max-w-[120px]"
            value={form['smtp.port']}
            onChange={(e) => updateField('smtp.port', e.target.value)}
            placeholder="587"
            aria-invalid={errors['smtp.port'] ? true : undefined}
          />
        </FormField>

        <FormField label={meta['smtp.username'].label} htmlFor="smtp-username">
          <Input
            id="smtp-username"
            className="max-w-md"
            value={form['smtp.username']}
            onChange={(e) => updateField('smtp.username', e.target.value)}
            placeholder="user@example.com"
            aria-describedby="smtp-username-desc"
          />
          <p id="smtp-username-desc" className="text-sm text-muted-foreground">
            인증 없는 릴레이라면 비워 둘 수 있습니다
          </p>
        </FormField>

        {/* 비밀번호 — 항상 빈 칸으로 시작한다. 표시/숨기기 토글을 두지 않는 이유는 서버가 평문을
            내려주지 않아 보여줄 것이 없기 때문이다. */}
        <FormField label={meta['smtp.password'].label} htmlFor="smtp-password">
          <Input
            id="smtp-password"
            type="password"
            className="max-w-md"
            autoComplete="new-password"
            value={form['smtp.password']}
            onChange={(e) => updateField('smtp.password', e.target.value)}
            placeholder={passwordSaved ? '변경하려면 새 비밀번호 입력' : undefined}
            aria-describedby="smtp-password-desc"
          />
          <p id="smtp-password-desc" className="text-sm text-muted-foreground">
            {passwordSaved
              ? '저장된 비밀번호가 있습니다. 비워 두면 유지됩니다.'
              : '설정된 비밀번호가 없습니다 (인증 없는 SMTP)'}
          </p>
        </FormField>

        {/* STARTTLS — Switch 는 라벨 오른쪽에 놓이는 배치라 FormField 대신 라벨+설명을 직접 조합한다. */}
        <div className="flex items-center justify-between max-w-md">
          <div className="space-y-1">
            <Label htmlFor="smtp-starttls">{meta['smtp.starttls'].label}</Label>
            <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
          </div>
          <Switch
            id="smtp-starttls"
            aria-label={meta['smtp.starttls'].label}
            checked={form['smtp.starttls'] === 'true'}
            onCheckedChange={(checked) => updateField('smtp.starttls', checked ? 'true' : 'false')}
          />
        </div>

        <Separator />

        <FormField
          label={meta['smtp.from_address'].label}
          htmlFor="smtp-from"
          required={meta['smtp.from_address'].required}
          error={errors['smtp.from_address']}
        >
          <Input
            id="smtp-from"
            className="max-w-md"
            value={form['smtp.from_address']}
            onChange={(e) => updateField('smtp.from_address', e.target.value)}
            placeholder="noreply@example.com"
            aria-invalid={errors['smtp.from_address'] ? true : undefined}
            aria-describedby="smtp-from-desc"
          />
          <p id="smtp-from-desc" className="text-sm text-muted-foreground">
            이메일 발신자로 표시되는 주소
          </p>
        </FormField>

        {/* 액션 행 — 좌측은 해제(설정됨일 때만), 우측은 되돌리기·연결 테스트·저장. AI 분류 탭과 같은
            배치다. 미설정이면 좌측을 빈 자리로 채워 저장이 오른쪽에 남게 한다(justify-between). */}
        <div className="flex flex-wrap items-center justify-between gap-3">
          {configured ? (
            <Button type="button" variant="ghost" onClick={() => setClearOpen(true)} disabled={busy}>
              {CLEAR_LABEL}
            </Button>
          ) : (
            <span aria-hidden />
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button variant="outline" onClick={handleReset} disabled={!hasChanges || busy}>
              <RotateCcw className="h-4 w-4" />
              되돌리기
            </Button>
            {/* 연결 테스트는 <b>서버에 저장된</b> 설정으로 접속한다. 편집 중에도 막지 않는 이유:
                지금 저장된 값을 확인하려는 것도 유효한 용도다. 미설정이면 테스트할 대상이 없어 막는다. */}
            <Button
              variant="outline"
              onClick={handleTest}
              disabled={!configured || testMutation.isPending}
              aria-describedby={testNotice ? 'smtp-test-notice' : undefined}
            >
              <Send className="h-4 w-4" />
              {testMutation.isPending ? '테스트 중...' : '연결 테스트'}
            </Button>
            <Button onClick={() => void handleSave()} disabled={!hasChanges || busy}>
              <Save className="h-4 w-4" />
              {isSaving ? '저장 중...' : '저장'}
            </Button>
          </div>
        </div>
        {testNotice && (
          <p id="smtp-test-notice" className="text-right text-sm text-muted-foreground">
            {testNotice}
          </p>
        )}
      </CardContent>

      {/* 해제 확인 — 저장된 비밀번호가 복구 불가로 지워지고 발송이 멈추므로 파괴적 확인 버튼을 쓴다. */}
      <ClearConfirmDialog
        open={clearOpen}
        onOpenChange={setClearOpen}
        title={CLEAR_TITLE}
        description={CLEAR_BODY}
        confirmLabel={CLEAR_LABEL}
        onConfirm={() => void handleClear()}
      />
    </Card>
  );
}
