import { Eye, EyeOff, Mail, Send } from 'lucide-react';
import { useEffect, useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Separator } from '../../components/ui/separator';
import { Switch } from '../../components/ui/switch';
import {
  useSmtpSettings,
  useTestSmtpSettings,
  useUpdateSmtpSettings,
} from '../../hooks/queries/useProactiveMessages';

interface SmtpForm {
  'smtp.host': string;
  'smtp.port': string;
  'smtp.username': string;
  'smtp.password': string;
  'smtp.starttls': string;
  'smtp.from_address': string;
}

const DEFAULT: SmtpForm = {
  'smtp.host': '',
  'smtp.port': '587',
  'smtp.username': '',
  'smtp.password': '',
  'smtp.starttls': 'true',
  'smtp.from_address': '',
};

export default function SmtpSettingsTab() {
  const { data: settings, isLoading } = useSmtpSettings();
  const updateMutation = useUpdateSmtpSettings();
  const testMutation = useTestSmtpSettings();

  const [form, setForm] = useState<SmtpForm>(DEFAULT);
  const [original, setOriginal] = useState<SmtpForm>(DEFAULT);
  const [showPassword, setShowPassword] = useState(false);

  // 서버에서 settings가 로드되면 폼 상태에 반영 — 서버 데이터 → 폼 state 초기화 패턴
  useEffect(() => {
    if (!settings) return;
    const values = { ...DEFAULT };
    settings.forEach((s) => {
      const key = s.key as keyof SmtpForm;
      if (key in values) values[key] = s.value ?? '';
    });
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setForm(values);
    // eslint-disable-next-line react-hooks/set-state-in-effect
    setOriginal(values);
  }, [settings]);

  const hasChanges = JSON.stringify(form) !== JSON.stringify(original);

  const updateField = (key: keyof SmtpForm, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }));
  };

  const handleSave = async () => {
    const toSave = { ...form };
    if (toSave['smtp.password'].startsWith('****')) {
      delete (toSave as Partial<SmtpForm>)['smtp.password'];
    }
    updateMutation.mutate(toSave, {
      onSuccess: () => {
        setOriginal({ ...form });
        toast.success('SMTP 설정이 저장되었습니다.');
      },
      onError: () => toast.error('SMTP 설정 저장에 실패했습니다.'),
    });
  };

  const handleTest = () => {
    testMutation.mutate(undefined, {
      onSuccess: (res) => {
        // 서버가 200 OK라도 success=false면 실패 (비정상 응답 처리)
        const data = res.data as { success?: boolean; message?: string } | undefined;
        if (data?.success === false) {
          toast.error(data.message ?? '테스트 발송에 실패했습니다.');
        } else {
          toast.success('테스트 이메일이 발송되었습니다.');
        }
      },
      onError: () => toast.error('테스트 발송에 실패했습니다.'),
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
          {/* Host */}
          <div className="space-y-2">
            <Label htmlFor="smtp-host">SMTP 호스트</Label>
            <Input
              id="smtp-host"
              className="max-w-md"
              value={form['smtp.host']}
              onChange={(e) => updateField('smtp.host', e.target.value)}
              placeholder="smtp.gmail.com"
            />
          </div>

          <Separator />

          {/* Port */}
          <div className="space-y-2">
            <Label htmlFor="smtp-port">포트</Label>
            <Input
              id="smtp-port"
              type="number"
              className="max-w-[120px]"
              value={form['smtp.port']}
              onChange={(e) => updateField('smtp.port', e.target.value)}
              placeholder="587"
            />
          </div>

          <Separator />

          {/* Username */}
          <div className="space-y-2">
            <Label htmlFor="smtp-username">사용자 이름</Label>
            <Input
              id="smtp-username"
              className="max-w-md"
              value={form['smtp.username']}
              onChange={(e) => updateField('smtp.username', e.target.value)}
              placeholder="user@example.com"
            />
          </div>

          <Separator />

          {/* Password */}
          <div className="space-y-2">
            <Label htmlFor="smtp-password">비밀번호</Label>
            <div className="relative max-w-md">
              <Input
                id="smtp-password"
                type={showPassword ? 'text' : 'password'}
                className="pr-10 focus-visible:ring-2"
                value={form['smtp.password']}
                onChange={(e) => updateField('smtp.password', e.target.value)}
                placeholder="••••••••"
              />
              <button
                type="button"
                className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                onClick={() => setShowPassword((v) => !v)}
                aria-label={showPassword ? '비밀번호 숨기기' : '비밀번호 보기'}
              >
                {showPassword ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
              </button>
            </div>
          </div>

          <Separator />

          {/* STARTTLS */}
          <div className="flex items-center justify-between max-w-md">
            <div className="space-y-1">
              <Label htmlFor="smtp-starttls">STARTTLS 사용</Label>
              <p className="text-sm text-muted-foreground">TLS 암호화로 SMTP 연결 보안</p>
            </div>
            <Switch
              id="smtp-starttls"
              aria-label="STARTTLS 사용"
              checked={form['smtp.starttls'] === 'true'}
              onCheckedChange={(checked) => updateField('smtp.starttls', checked ? 'true' : 'false')}
            />
          </div>

          <Separator />

          {/* From Address */}
          <div className="space-y-2">
            <Label htmlFor="smtp-from">발신자 주소</Label>
            <Input
              id="smtp-from"
              className="max-w-md"
              value={form['smtp.from_address']}
              onChange={(e) => updateField('smtp.from_address', e.target.value)}
              placeholder="noreply@example.com"
            />
            <p className="text-sm text-muted-foreground">이메일 발신자로 표시되는 주소</p>
          </div>
        </CardContent>
      </Card>

      <div className="flex items-center gap-3">
        <Button onClick={handleSave} disabled={updateMutation.isPending || !hasChanges}>
          {updateMutation.isPending ? '저장 중...' : '저장'}
        </Button>
        <Button
          variant="outline"
          onClick={handleTest}
          disabled={testMutation.isPending || hasChanges}
        >
          <Send className="h-4 w-4" />
          {testMutation.isPending ? '발송 중...' : '테스트 발송'}
        </Button>
      </div>
    </div>
  );
}
