import { Mail, RotateCcw, Save, Send } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import { useTestSmtpSettings } from '../../hooks/queries/useProactiveMessages';
import { indexSettingsByKey, resolveSettingFieldState } from '../../lib/settings-fields';
import type { ResolvedSettingResponse } from '../../types/settings';
import { ClearOverrideButton, SettingFieldLabel, SettingStateBadge } from './settings-lock';

interface SmtpForm {
  'smtp.host': string;
  'smtp.port': string;
  'smtp.username': string;
  'smtp.password': string;
  'smtp.starttls': string;
  'smtp.from_address': string;
}

// 조회 전 초기값. starttls 만 'true' 인 이유: Switch 는 빈 문자열을 표현할 수 없어 조회 전에도
// 켜짐/꺼짐 중 하나를 그려야 하고, 플랫폼 기본값이 켜짐이다.
const EMPTY: SmtpForm = {
  'smtp.host': '',
  'smtp.port': '',
  'smtp.username': '',
  'smtp.password': '',
  'smtp.starttls': 'true',
  'smtp.from_address': '',
};

// 저장이 거부된 필드를 이름으로 지목하는 데 쓴다 — "어떤 필드가 문제인지" 말해주지 않으면
// 사용자가 무엇을 고쳐야 할지 알 수 없다. 6키 전부를 담는다(빠진 키는 토스트에 undefined 가 찍힌다).
const FIELD_LABELS: Record<keyof SmtpForm, string> = {
  'smtp.host': 'SMTP 호스트',
  'smtp.port': '포트',
  'smtp.username': '사용자 이름',
  'smtp.password': '비밀번호',
  'smtp.starttls': 'STARTTLS 사용',
  'smtp.from_address': '발신자 주소',
};

/**
 * 빈 값으로 저장해도 되는 키의 <b>화이트리스트</b>.
 *
 * 나머지 키를 비운 채 저장하면 거부한다 — 값의 유효성 문제가 아니라 <b>의도 불일치</b>다.
 * 비운 사람은 대개 "플랫폼 값으로 되돌리기"를 뜻하는데, 빈 값을 그냥 페이로드에서 빼고 저장하면
 * "저장했다"면서 아무것도 쓰지 않고 dirty 까지 지워, 사용자는 반영된 줄 알고 떠나는데 옛 오버라이드가
 * 그대로 적용된다. 되돌리기의 정식 조작은 "재정의 해제"(DELETE)다.
 *
 * <b>username/password 만 예외인 이유</b>: 인증 없는 릴레이가 합법적 최종 상태라 "비어 있음"이
 * 사용자가 원하는 결과일 수 있다. "SMTP 는 전부 예외"로 뭉뚱그리면 `smtp.host` 를 실수로 비웠을 때도
 * 조용히 통과한다.
 *
 * <b>`smtp.starttls` 를 넣지 않는 이유</b>: Switch 라 값이 항상 'true'/'false' 이고 빈 값이 될 수
 * 없다 — 목록에 넣으면 도달 불가한 방어 코드가 된다.
 * <b>`smtp.port` 를 넣지 않는 이유</b>: 비운 채 저장하면 위 거부 경로로 가는 것이 맞다. 범위 검증은
 * `validate()` 가 따로 담당한다.
 */
const BLANK_ALLOWED_KEYS: (keyof SmtpForm)[] = ['smtp.username', 'smtp.password'];

// 포트 범위는 백엔드 `SettingsService.normalizeSmtpPayload`(1~65535)와 반드시 같아야 한다 —
// 어긋나면 한쪽이 통과시킨 값을 다른 쪽이 거부해 "저장했는데 400" 또는 그 반대가 된다.
const PORT_MIN = 1;
const PORT_MAX = 65535;

/**
 * 이메일(SMTP) 설정 탭 — P7-c1 이후 <b>테넌트 상속/재정의 편집 화면</b>.
 *
 * SMTP 6키는 P7-b 에서 플랫폼 전용이었으나 P7-c1 이 테넌트 오버라이드 허용으로 재분류했다.
 * 그래서 이 탭은 AI 탭(`SettingsPage`)과 같은 체계를 그대로 쓴다 — 필드별 상태 배지, 재정의 해제,
 * "바꾼 키만 PUT" 저장.
 *
 * <b>읽기는 `GET /settings?prefix=smtp`(해석된 값 + 플래그) 다.</b> 예전에는 전용
 * `GET /settings/smtp` 를 썼는데 그 경로는 <b>해석기를 타지 않아</b> 플랫폼 값을 보여줬다 —
 * 테넌트가 SMTP 를 재정의하면 메일은 테넌트 값으로 나가는데 화면은 플랫폼 값을 보여주는 어긋남이
 * 생긴다. 예전 주석은 "프리픽스 조회로 갈아타면 권한이 달라 탭을 못 여는 사람이 생긴다"고 경고했지만
 * 사실이 아니다: `ai:settings`(V16)와 `settings:write`(V42)는 <b>둘 다 ADMIN 롤에만</b> 부여되어
 * 한쪽만 가진 사용자는 존재할 수 없다(마이그레이션 실측). 그 경고 때문에 남아 있던 전용 읽기
 * 엔드포인트는 이 태스크에서 삭제했다.
 *
 * <b>비밀번호는 서버가 마스킹(`****ab3f`)해서 주고 폼에 그대로 시드된다.</b> 사용자가 손대지 않으면
 * `form === original` 이라 페이로드에서 빠지므로 마스크가 저장될 일이 없다(백엔드의 센티널 필터는
 * 심층 방어이고, 정상 경로는 애초에 보내지 않는 것이다). 표시/숨기기 토글은 두지 않는다 — 서버가
 * 평문을 절대 내려주지 않아 눌러도 보여줄 것이 없다.
 */
export default function SmtpSettingsTab({
  onDirtyChange,
}: {
  /** 페이지 전체 이탈 가드(이슈 #86)에 이 탭의 dirty 여부를 보고한다. */
  onDirtyChange: (dirty: boolean) => void;
}) {
  const testMutation = useTestSmtpSettings();

  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [isClearing, setIsClearing] = useState(false);
  const [form, setForm] = useState<SmtpForm>(EMPTY);
  const [original, setOriginal] = useState<SmtpForm>(EMPTY);
  // 서버 응답을 키로 인덱싱해 보관한다 — 배지 상태(overridden/tenantEditable)의 근거.
  const [settings, setSettings] = useState<Record<string, ResolvedSettingResponse>>({});
  const [errors, setErrors] = useState<Partial<Record<keyof SmtpForm, string>>>({});

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('smtp');
      const byKey = indexSettingsByKey(data);
      const values = { ...EMPTY };
      (Object.keys(values) as (keyof SmtpForm)[]).forEach((key) => {
        values[key] = byKey[key]?.value ?? EMPTY[key];
      });
      setSettings(byKey);
      setForm(values);
      setOriginal(values);
    } catch {
      toast.error('설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  // 배지 상태만 다시 읽는다 — 폼 값은 건드리지 않으므로 입력 중인 내용이 사라지지 않는다.
  // 실패를 여기서 삼키지 않는 이유: 재정의 해제는 이 조회로 결과를 확정하므로, 삼키면 해제가
  // "성공처럼 보이는 무동작"이 된다. 삼킴 여부는 호출부가 정한다.
  const refreshMeta = useCallback(async () => {
    const { data } = await settingsApi.getByPrefix('smtp');
    const byKey = indexSettingsByKey(data);
    setSettings(byKey);
    return byKey;
  }, []);

  // 필드 상태 판정 — 배지·disabled·저장 대상·dirty 가 모두 이 한 곳을 거쳐 서로 어긋나지 않게 한다.
  const fieldState = (key: keyof SmtpForm) => resolveSettingFieldState(key, settings[key]);
  const isEditable = (key: keyof SmtpForm) => fieldState(key) !== 'locked';

  const updateField = (key: keyof SmtpForm, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors((prev) => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  /**
   * 입력값 규칙 검증. 지금은 포트 범위 하나뿐이다.
   *
   * <b>비어 있으면 검사하지 않는다</b> — AI 탭의 숫자 규칙은 빈 값도 오류로 보지만, SMTP 는
   * V42 가 6키를 전부 `''` 로 시드해서 <b>미설정 플랫폼의 기본 상태가 빈 포트</b>다. 빈 값을
   * 오류로 만들면 `smtp.host` 하나만 고치려는 테넌트가 자기가 건드리지도 않은 빈 포트 때문에
   * 영원히 저장하지 못한다(P7-b 가 `session_max_tokens` 하한에서 겪은 "운영자가 저장한 값 때문에
   * 테넌트가 아무것도 저장 못 하는" 모양 그대로다). 사용자가 <b>직접 비운</b> 경우는 오류가 아니라
   * `BLANK_ALLOWED_KEYS` 판정으로 넘어가 저장이 거부된다.
   */
  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof SmtpForm, string>> = {};
    const port = form['smtp.port'].trim();
    if (port !== '') {
      const n = Number(port);
      if (isNaN(n) || !Number.isInteger(n) || n < PORT_MIN || n > PORT_MAX) {
        newErrors['smtp.port'] = `${PORT_MIN}~${PORT_MAX} 사이의 정수를 입력하세요`;
      }
    }
    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) {
      toast.error('입력값을 확인하세요.');
      return;
    }

    // 페이로드는 <b>이번에 바꾼 키만</b> 담는다. 6키를 전부 보내면 사용자가 호스트 하나를 고쳐도
    // 나머지 5키가 같은 값으로 tenant_settings 에 기록되어 상속이 조용히 끊긴다 — 그 뒤로는
    // 플랫폼이 기본값을 바꿔도 이 테넌트에는 영원히 전파되지 않는다.
    // 저장 대상 판정의 권위는 서버 플래그(fieldState)다 — web 상수로 거르면 표시와 저장이 갈라진다.
    const settingsToSave: Record<string, string> = {};
    const droppedChangedKeys: (keyof SmtpForm)[] = [];
    (Object.keys(form) as (keyof SmtpForm)[]).forEach((key) => {
      if (fieldState(key) === 'locked') return;
      if (form[key] === original[key]) return;
      if (form[key].trim() !== '' || BLANK_ALLOWED_KEYS.includes(key)) {
        settingsToSave[key] = form[key];
      } else {
        droppedChangedKeys.push(key);
      }
    });
    if (droppedChangedKeys.length > 0) {
      const names = droppedChangedKeys.map((key) => FIELD_LABELS[key]).join(', ');
      toast.error(
        `${names}을(를) 비워 둔 채로는 저장할 수 없습니다. 플랫폼 기본값으로 되돌리려면 "재정의 해제"를 사용하세요.`,
      );
      return;
    }

    setIsSaving(true);
    try {
      await settingsApi.update({ settings: settingsToSave });
      setOriginal({ ...form });
      toast.success('설정이 저장되었습니다.');
      // 저장한 키는 이제 테넌트 재정의 상태이므로 배지를 다시 읽어 맞춘다.
      refreshMeta().catch(() => undefined);
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  /**
   * 재정의 해제 — DELETE 후 해당 키 하나만 서버 값으로 되돌린다.
   * 전체 폼을 다시 시드하지 않는 이유: 다른 필드에 입력 중이던 미저장 변경을 조용히 날려버린다.
   */
  const handleClearOverride = async (key: string) => {
    const formKey = key as keyof SmtpForm;
    setIsClearing(true);
    try {
      await settingsApi.clearOverride(key);
      const byKey = await refreshMeta();
      const restored = byKey[key]?.value ?? EMPTY[formKey];
      setForm((prev) => ({ ...prev, [formKey]: restored }));
      setOriginal((prev) => ({ ...prev, [formKey]: restored }));
      setErrors((prev) => {
        const next = { ...prev };
        delete next[formKey];
        return next;
      });
      toast.success('플랫폼 기본값으로 되돌렸습니다.');
    } catch {
      toast.error('재정의 해제에 실패했습니다.');
    } finally {
      setIsClearing(false);
    }
  };

  // 재정의 중인 필드에만 해제 버튼을 붙인다 — 상속 중인 필드에는 지울 오버라이드가 없다.
  const clearAction = (key: keyof SmtpForm) =>
    fieldState(key) === 'overridden' ? (
      <ClearOverrideButton settingKey={key} onConfirm={handleClearOverride} disabled={isClearing} />
    ) : undefined;

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  // dirty 판정도 저장 대상과 같은 기준을 쓴다 — 서버가 잠갔다고 한 키는 세지 않는다.
  const hasChanges = (Object.keys(form) as (keyof SmtpForm)[]).some(
    (key) => fieldState(key) !== 'locked' && form[key] !== original[key],
  );

  // 이탈 가드(이슈 #86)에 dirty 를 보고한다. <b>언마운트 시 반드시 해제한다</b> — 탭을 바꾸면
  // Radix 가 이 컴포넌트를 언마운트해 편집 내용도 함께 사라지는데, 합산기에 남은 true 를 지우지
  // 않으면 존재하지 않는 변경 때문에 이탈 다이얼로그가 뜬다.
  useEffect(() => {
    onDirtyChange(hasChanges);
    return () => onDirtyChange(false);
  }, [onDirtyChange, hasChanges]);

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
            SMTP 설정은 플랫폼 기본값을 따르며, 필요한 항목만 우리 조직 값으로 재정의할 수 있습니다.
          </p>

          {/* Host */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-host"
              state={fieldState('smtp.host')}
              action={clearAction('smtp.host')}
            >
              SMTP 호스트
            </SettingFieldLabel>
            <Input
              id="smtp-host"
              className="max-w-md"
              value={form['smtp.host']}
              disabled={!isEditable('smtp.host')}
              onChange={(e) => updateField('smtp.host', e.target.value)}
              placeholder="smtp.gmail.com"
            />
            <p className="text-sm text-muted-foreground">발신 메일 서버 주소</p>
          </div>

          <Separator />

          {/* Port */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-port"
              state={fieldState('smtp.port')}
              action={clearAction('smtp.port')}
            >
              포트
            </SettingFieldLabel>
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
            />
            {errors['smtp.port'] && (
              <p className="text-sm text-destructive">{errors['smtp.port']}</p>
            )}
          </div>

          <Separator />

          {/* Username */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-username"
              state={fieldState('smtp.username')}
              action={clearAction('smtp.username')}
            >
              사용자 이름
            </SettingFieldLabel>
            <Input
              id="smtp-username"
              className="max-w-md"
              value={form['smtp.username']}
              disabled={!isEditable('smtp.username')}
              onChange={(e) => updateField('smtp.username', e.target.value)}
              placeholder="user@example.com"
            />
            <p className="text-sm text-muted-foreground">인증 없는 릴레이라면 비워 둘 수 있습니다</p>
          </div>

          <Separator />

          {/* Password — 마스킹된 값이 그대로 시드된다. 표시/숨기기 토글을 두지 않는 이유는
              눌러도 보여줄 평문이 서버에서 오지 않기 때문이다(편집 가능해져도 마찬가지). */}
          <div className="space-y-2">
            <SettingFieldLabel
              htmlFor="smtp-password"
              state={fieldState('smtp.password')}
              action={clearAction('smtp.password')}
            >
              비밀번호
            </SettingFieldLabel>
            <Input
              id="smtp-password"
              type="password"
              className="max-w-md"
              value={form['smtp.password']}
              disabled={!isEditable('smtp.password')}
              onChange={(e) => updateField('smtp.password', e.target.value)}
            />
            {/* 마스킹은 자물쇠 아이콘 같은 시각 단서를 쓰지 않으므로 "설정됨/안 됨"을 전달하는
                경로가 이 텍스트뿐이다. 값 유무로 분기한다. */}
            <p className="text-sm text-muted-foreground">
              {form['smtp.password'] === ''
                ? '설정된 비밀번호가 없습니다 (인증 없는 SMTP)'
                : '현재 비밀번호가 설정되어 있습니다. 값을 바꾸려면 새 비밀번호를 입력하세요.'}
            </p>
          </div>

          <Separator />

          {/* STARTTLS — Switch 는 라벨 오른쪽에 놓이는 배치라 SettingFieldLabel 대신
              라벨+배지+해제버튼을 왼쪽 열에 직접 조합한다(구성 요소는 동일 컴포넌트를 재사용). */}
          <div className="flex items-center justify-between max-w-md">
            <div className="space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <Label htmlFor="smtp-starttls">STARTTLS 사용</Label>
                <SettingStateBadge state={fieldState('smtp.starttls')} />
                {clearAction('smtp.starttls')}
              </div>
              <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
            </div>
            <Switch
              id="smtp-starttls"
              aria-label="STARTTLS 사용"
              checked={form['smtp.starttls'] === 'true'}
              disabled={!isEditable('smtp.starttls')}
              onCheckedChange={(checked) => updateField('smtp.starttls', checked ? 'true' : 'false')}
            />
          </div>

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
            <p className="text-sm text-muted-foreground">이메일 발신자로 표시되는 주소</p>
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
          {hasChanges && (
            <p className="text-sm text-muted-foreground">
              저장 전 값이 아니라 마지막 저장값으로 테스트합니다
            </p>
          )}
          <Button variant="outline" onClick={handleTest} disabled={testMutation.isPending}>
            <Send className="h-4 w-4" />
            {testMutation.isPending ? '테스트 중...' : '연결 테스트'}
          </Button>
        </div>
      </div>
    </div>
  );
}
