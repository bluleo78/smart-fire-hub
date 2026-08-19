import { Mail, Send } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import { useSmtpSettings, useTestSmtpSettings } from '../../hooks/queries/useProactiveMessages';
import {
  PlatformLockedBanner,
  PlatformLockedNote,
  SettingFieldLabel,
  SettingStateBadge,
} from './settings-lock';

interface SmtpForm {
  'smtp.host': string;
  'smtp.port': string;
  'smtp.username': string;
  'smtp.password': string;
  'smtp.starttls': string;
  'smtp.from_address': string;
}

const EMPTY: SmtpForm = {
  'smtp.host': '',
  'smtp.port': '',
  'smtp.username': '',
  'smtp.password': '',
  'smtp.starttls': 'true',
  'smtp.from_address': '',
};

/**
 * 이메일(SMTP) 설정 탭 — P7-b 이후 **전 필드 플랫폼 전용(읽기 전용)**.
 *
 * SMTP 6키는 발신 도메인 신뢰도를 전 테넌트가 공유하므로 테넌트가 바꿀 수 없다. 백엔드
 * `PUT /settings/smtp` 는 테넌트 평면에서 항상 403 이므로, 저장 경로(저장 버튼·dirty 보고·mutation)를
 * 화면에서 통째로 제거했다 — 남겨 두면 누르는 순간 403 을 받는 버튼이 된다.
 * "연결 테스트"는 값을 노출하지 않는 진단 액션이라 유지한다.
 *
 * 값 조회는 `GET /settings/smtp` 를 그대로 쓴다(`settings:write` 권한). 프리픽스 조회
 * (`GET /settings?prefix=smtp`, `ai:settings` 권한)로 갈아타면 이 탭을 열 수 있는 사람이 바뀐다.
 * 오버라이드는 백엔드에서 화이트리스트로 걸러지므로 SMTP 키에는 존재할 수 없고, 따라서 이
 * 엔드포인트의 값이 곧 실제 적용 값이다(해석 결과와 어긋날 여지가 없다).
 */
export default function SmtpSettingsTab() {
  const { data: settings, isLoading } = useSmtpSettings();
  const testMutation = useTestSmtpSettings();

  const [form, setForm] = useState<SmtpForm>(EMPTY);
  // 서버에서 settings가 로드되면 폼 상태에 반영 — 서버 데이터 → 폼 state 초기화 패턴
  useEffect(() => {
    if (!settings) return;
    const values = { ...EMPTY };
    settings.forEach((s) => {
      const key = s.key as keyof SmtpForm;
      if (key in values) values[key] = s.value ?? '';
    });
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(values);
  }, [settings]);

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
      {/* 탭 상단 배너 — 스크롤하지 않아도 편집 불가를 먼저 알린다 */}
      <PlatformLockedBanner>
        이메일(SMTP) 설정은 플랫폼 운영자가 관리합니다. 이 화면에서는 현재 적용된 값을 확인할 수만
        있고, 테넌트에서 변경할 수 없습니다.
      </PlatformLockedBanner>

      <Card className="card-hover">
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <Mail className="h-4 w-4" />
            SMTP 서버 설정
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {/* 아래 6필드의 `state="locked"` 는 의도적으로 하드코딩이다 — 서버 플래그로 바꾸지 말 것.
              AI 탭은 키마다 상태가 달라 서버 `tenantEditable` 을 따라야 하지만, SMTP 6키는 정책상
              전부 균일하게 플랫폼 잠금이라 데이터로 구동할 편차가 없다. 플래그를 받으려면 읽기를
              `GET /settings?prefix=smtp` 로 옮겨야 하는데, 그 엔드포인트의 권한은 `ai:settings` 이고
              여기서 쓰는 `GET /settings/smtp` 는 `settings:write` 다 — 갈아타면 한쪽만 가진 역할에서
              탭 전체가 403 이 된다. 표시를 데이터로 구동하려다 화면을 못 여는 사람을 만드는 셈이다. */}
          {/* Host */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="smtp-host" state="locked">
              SMTP 호스트
            </SettingFieldLabel>
            <Input
              id="smtp-host"
              className="max-w-md"
              value={form['smtp.host']}
              disabled
              placeholder="smtp.gmail.com"
            />
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* Port */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="smtp-port" state="locked">
              포트
            </SettingFieldLabel>
            <Input
              id="smtp-port"
              type="number"
              className="max-w-[120px]"
              value={form['smtp.port']}
              disabled
              placeholder="587"
            />
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* Username */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="smtp-username" state="locked">
              사용자 이름
            </SettingFieldLabel>
            <Input
              id="smtp-username"
              className="max-w-md"
              value={form['smtp.username']}
              disabled
              placeholder="user@example.com"
            />
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* Password — 서버에서 **** 로 마스킹되어 내려오고 편집도 불가하므로 표시/숨기기 토글을 두지 않는다 */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="smtp-password" state="locked">
              비밀번호
            </SettingFieldLabel>
            <Input
              id="smtp-password"
              type="password"
              className="max-w-md"
              value={form['smtp.password']}
              disabled
              placeholder="••••••••"
            />
            <PlatformLockedNote />
          </div>

          <Separator />

          {/* STARTTLS — Switch 는 라벨 오른쪽에 놓이는 배치라 SettingFieldLabel 대신
              라벨+배지를 왼쪽 열에 직접 조합한다(배지 자체는 동일 컴포넌트를 재사용). */}
          <div className="flex items-center justify-between max-w-md">
            <div className="space-y-1">
              <div className="flex flex-wrap items-center gap-2">
                <Label htmlFor="smtp-starttls">STARTTLS 사용</Label>
                <SettingStateBadge state="locked" />
              </div>
              <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
              <PlatformLockedNote />
            </div>
            <Switch
              id="smtp-starttls"
              aria-label="STARTTLS 사용"
              checked={form['smtp.starttls'] === 'true'}
              disabled
            />
          </div>

          <Separator />

          {/* From Address */}
          <div className="space-y-2">
            <SettingFieldLabel htmlFor="smtp-from" state="locked">
              발신자 주소
            </SettingFieldLabel>
            <Input
              id="smtp-from"
              className="max-w-md"
              value={form['smtp.from_address']}
              disabled
              placeholder="noreply@example.com"
            />
            <p className="text-sm text-muted-foreground">이메일 발신자로 표시되는 주소</p>
            <PlatformLockedNote />
          </div>
        </CardContent>
      </Card>

      {/* 원래 저장/되돌리기 버튼 행이 있던 자리 — 배너로 대체하고 진단용 연결 테스트만 남긴다 */}
      <PlatformLockedBanner>
        이메일(SMTP) 설정은 플랫폼 운영자가 관리합니다. 이 화면에서는 현재 적용된 값을 확인할 수만
        있고, 테넌트에서 변경할 수 없습니다.
      </PlatformLockedBanner>

      <div className="flex items-center gap-3">
        <Button variant="outline" onClick={handleTest} disabled={testMutation.isPending}>
          <Send className="h-4 w-4" />
          {testMutation.isPending ? '테스트 중...' : '연결 테스트'}
        </Button>
      </div>
    </div>
  );
}
