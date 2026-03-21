import { Bot, Eye, EyeOff, RotateCcw, Save, Settings, ShieldCheck } from 'lucide-react';
import { useCallback, useEffect, useState } from 'react';
import { toast } from 'sonner';

import { settingsApi } from '../../api/settings';
import { Button } from '../../components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { Separator } from '../../components/ui/separator';
import { Skeleton } from '../../components/ui/skeleton';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '../../components/ui/tabs';
import { Textarea } from '../../components/ui/textarea';
import type { SettingResponse } from '../../types/settings';

const AGENT_TYPE_OPTIONS = [
  { value: 'sdk', label: 'AI Agent (SDK)' },
  { value: 'cli', label: 'Claude Code (구독)' },
  { value: 'cli-api', label: 'Claude Code (API)' },
];

const MODEL_OPTIONS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5' },
];

interface AISettingsForm {
  [key: string]: string;
  'ai.api_key': string;
  'ai.cli_oauth_token': string;
  'ai.agent_type': string;
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
  'ai.session_max_tokens': string;
}

const DEFAULT_VALUES: AISettingsForm = {
  'ai.api_key': '',
  'ai.cli_oauth_token': '',
  'ai.agent_type': 'sdk',
  'ai.model': 'claude-sonnet-4-6',
  'ai.max_turns': '10',
  'ai.system_prompt': '',
  'ai.temperature': '1.0',
  'ai.max_tokens': '16384',
  'ai.session_max_tokens': '50000',
};

export default function SettingsPage() {
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [form, setForm] = useState<AISettingsForm>(DEFAULT_VALUES);
  const [original, setOriginal] = useState<AISettingsForm>(DEFAULT_VALUES);
  const [errors, setErrors] = useState<Partial<Record<keyof AISettingsForm, string>>>({});
  const [showApiKey, setShowApiKey] = useState(false);
  const [showCliOauthToken, setShowCliOauthToken] = useState(false);
  const [authStatus, setAuthStatus] = useState<{ valid: boolean; email?: string; subscriptionType?: string } | null>(null);
  const [isVerifying, setIsVerifying] = useState(false);

  const verifyAuth = useCallback(async () => {
    setIsVerifying(true);
    try {
      const { data } = await settingsApi.verifyAuthStatus();
      setAuthStatus(data);
    } catch {
      setAuthStatus(null);
    } finally {
      setIsVerifying(false);
    }
  }, []);

  const fetchSettings = useCallback(async () => {
    setIsLoading(true);
    try {
      const { data } = await settingsApi.getByPrefix('ai');
      const values = { ...DEFAULT_VALUES };
      data.forEach((s: SettingResponse) => {
        if (s.key in values) {
          values[s.key as keyof AISettingsForm] = s.value;
        }
      });
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

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

    // API 키 검증은 sdk 또는 cli-api 모드에서만 필요
    if (form['ai.agent_type'] !== 'cli') {
      const apiKey = form['ai.api_key'];
      if (!apiKey.trim()) {
        newErrors['ai.api_key'] = 'API 키를 입력하세요';
      } else if (!apiKey.startsWith('****') && apiKey.length < 10) {
        newErrors['ai.api_key'] = 'API 키는 10자 이상이어야 합니다';
      }
    }
    // cli 모드에서 oauth 토큰이 없어도 저장 가능 (로그인 안 된 상태일 수 있음)

    const maxTurns = Number(form['ai.max_turns']);
    if (isNaN(maxTurns) || maxTurns < 1 || maxTurns > 50 || !Number.isInteger(maxTurns)) {
      newErrors['ai.max_turns'] = '1~50 사이의 정수를 입력하세요';
    }

    const temperature = Number(form['ai.temperature']);
    if (isNaN(temperature) || temperature < 0 || temperature > 1) {
      newErrors['ai.temperature'] = '0.0~1.0 사이의 값을 입력하세요';
    }

    const maxTokens = Number(form['ai.max_tokens']);
    if (isNaN(maxTokens) || maxTokens < 1 || maxTokens > 65536 || !Number.isInteger(maxTokens)) {
      newErrors['ai.max_tokens'] = '1~65536 사이의 정수를 입력하세요';
    }

    const sessionMaxTokens = Number(form['ai.session_max_tokens']);
    if (isNaN(sessionMaxTokens) || sessionMaxTokens < 10000 || sessionMaxTokens > 200000 || !Number.isInteger(sessionMaxTokens)) {
      newErrors['ai.session_max_tokens'] = '10,000~200,000 사이의 정수를 입력하세요';
    }

    if (!form['ai.system_prompt'].trim()) {
      newErrors['ai.system_prompt'] = '시스템 프롬프트를 입력하세요';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSave = async () => {
    if (!validate()) return;

    setIsSaving(true);
    try {
      const { 'ai.api_key': apiKey, 'ai.cli_oauth_token': cliOauthToken, ...rest } = form;
      const settingsToSave: Record<string, string> = { ...rest };
      if (apiKey && !apiKey.startsWith('****')) settingsToSave['ai.api_key'] = apiKey;
      if (cliOauthToken && !cliOauthToken.startsWith('****')) settingsToSave['ai.cli_oauth_token'] = cliOauthToken;
      await settingsApi.update({ settings: settingsToSave });
      setOriginal({ ...form });
      toast.success('설정이 저장되었습니다.');
      verifyAuth();
    } catch {
      toast.error('설정 저장에 실패했습니다.');
    } finally {
      setIsSaving(false);
    }
  };

  const handleReset = () => {
    setForm({ ...original });
    setErrors({});
  };

  const hasChanges = JSON.stringify(form) !== JSON.stringify(original);

  const updateField = (key: keyof AISettingsForm, value: string) => {
    setForm(prev => ({ ...prev, [key]: value }));
    if (errors[key]) {
      setErrors(prev => {
        const next = { ...prev };
        delete next[key];
        return next;
      });
    }
  };

  if (isLoading) {
    return (
      <div className="max-w-2xl mx-auto space-y-6 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="max-w-2xl mx-auto space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Settings className="h-6 w-6" />
        <h1 className="text-[28px] leading-[36px] font-semibold tracking-tight">설정</h1>
      </div>

      <Tabs defaultValue="ai">
        <TabsList>
          <TabsTrigger value="general">일반</TabsTrigger>
          <TabsTrigger value="ai">
            <Bot className="h-4 w-4" />
            AI 에이전트
          </TabsTrigger>
        </TabsList>

        {/* 일반 탭 */}
        <TabsContent value="general" className="mt-6">
          <Card>
            <CardContent className="flex flex-col items-center justify-center py-16">
              <Settings className="h-12 w-12 text-muted-foreground mb-4" />
              <p className="text-base leading-6 font-semibold">일반 설정</p>
              <p className="text-sm text-muted-foreground mt-1">
                준비 중입니다
              </p>
            </CardContent>
          </Card>
        </TabsContent>

        {/* AI 에이전트 탭 */}
        <TabsContent value="ai" className="mt-6 space-y-6">
          <Card>
            <CardHeader>
              <CardTitle>모델 설정</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              {/* 에이전트 유형 */}
              <div className="space-y-2">
                <Label htmlFor="ai-agent-type">에이전트 유형</Label>
                <Select
                  value={form['ai.agent_type']}
                  onValueChange={(value) => updateField('ai.agent_type', value)}
                >
                  <SelectTrigger id="ai-agent-type" className="w-full max-w-md">
                    <SelectValue placeholder="에이전트 유형을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {AGENT_TYPE_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 채팅에 사용할 에이전트 유형</p>
              </div>

              <Separator />

              {/* CLI OAuth 토큰 또는 API 키 */}
              {form['ai.agent_type'] === 'cli' ? (
                <div className="space-y-2">
                  <Label htmlFor="ai-cli-oauth-token">OAuth 토큰</Label>
                  <div className="flex gap-2 max-w-md">
                    <div className="relative flex-1">
                      <Input
                        id="ai-cli-oauth-token"
                        type={showCliOauthToken ? 'text' : 'password'}
                        className="pr-10"
                        value={form['ai.cli_oauth_token']}
                        onChange={(e) => updateField('ai.cli_oauth_token', e.target.value)}
                        placeholder="sk-ant-oat01-..."
                      />
                      <button
                        type="button"
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                        onClick={() => setShowCliOauthToken(!showCliOauthToken)}
                        aria-label={showCliOauthToken ? '토큰 숨기기' : '토큰 보기'}
                      >
                        {showCliOauthToken ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={verifyAuth}
                      disabled={isVerifying}
                      className="shrink-0"
                    >
                      <ShieldCheck className="mr-1.5 h-3.5 w-3.5" />
                      {isVerifying ? '검증 중...' : '인증 확인'}
                    </Button>
                  </div>
                  <p className="text-sm text-muted-foreground">
                    로컬에서 claude setup-token으로 발급받은 OAuth 토큰
                    {authStatus && (
                      <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'}`}>
                        {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                        {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                        {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                      </span>
                    )}
                  </p>
                </div>
              ) : (
                <div className="space-y-2">
                  <Label htmlFor="ai-api-key">API 키</Label>
                  <div className="flex gap-2 max-w-md">
                    <div className="relative flex-1">
                      <Input
                        id="ai-api-key"
                        type={showApiKey ? 'text' : 'password'}
                        className="pr-10"
                        value={form['ai.api_key']}
                        onChange={(e) => updateField('ai.api_key', e.target.value)}
                        placeholder="sk-ant-..."
                      />
                      <button
                        type="button"
                        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted-foreground hover:text-foreground"
                        onClick={() => setShowApiKey(!showApiKey)}
                        aria-label={showApiKey ? 'API 키 숨기기' : 'API 키 보기'}
                      >
                        {showApiKey ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
                      </button>
                    </div>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      onClick={verifyAuth}
                      disabled={isVerifying}
                      className="shrink-0"
                    >
                      <ShieldCheck className="mr-1.5 h-3.5 w-3.5" />
                      {isVerifying ? '검증 중...' : '인증 확인'}
                    </Button>
                  </div>
                  {errors['ai.api_key'] && (
                    <p className="text-sm text-destructive">{errors['ai.api_key']}</p>
                  )}
                  <p className="text-sm text-muted-foreground">
                    Anthropic API 키 (sk-ant-...)
                    {authStatus && (
                      <span className={`ml-2 inline-flex items-center text-xs font-medium ${authStatus.valid ? 'text-green-600 dark:text-green-400' : 'text-red-500 dark:text-red-400'}`}>
                        {authStatus.valid ? '✓ 인증됨' : '✗ 유효하지 않음'}
                        {authStatus.valid && authStatus.email && ` (${authStatus.email})`}
                        {authStatus.valid && authStatus.subscriptionType && ` · ${authStatus.subscriptionType}`}
                      </span>
                    )}
                  </p>
                </div>
              )}

              <Separator />

              {/* 모델 선택 */}
              <div className="space-y-2">
                <Label htmlFor="ai-model">모델</Label>
                <Select
                  value={form['ai.model']}
                  onValueChange={(value) => updateField('ai.model', value)}
                >
                  <SelectTrigger id="ai-model" className="w-full max-w-md">
                    <SelectValue placeholder="모델을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {MODEL_OPTIONS.map((opt) => (
                      <SelectItem key={opt.value} value={opt.value}>
                        {opt.label}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-sm text-muted-foreground">AI 에이전트가 사용할 Claude 모델</p>
              </div>

              <Separator />

              {/* Max Turns */}
              <div className="space-y-2">
                <Label htmlFor="ai-max-turns">최대 턴 수</Label>
                <Input
                  id="ai-max-turns"
                  type="number"
                  min={1}
                  max={50}
                  className="w-full max-w-md"
                  value={form['ai.max_turns']}
                  onChange={(e) => updateField('ai.max_turns', e.target.value)}
                />
                {errors['ai.max_turns'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_turns']}</p>
                )}
                <p className="text-sm text-muted-foreground">에이전트가 도구를 사용할 수 있는 최대 반복 횟수 (1~50)</p>
              </div>

              <Separator />

              {/* Temperature */}
              <div className="space-y-2">
                <Label htmlFor="ai-temperature">Temperature</Label>
                <Input
                  id="ai-temperature"
                  type="number"
                  min={0}
                  max={1}
                  step={0.1}
                  className="w-full max-w-md"
                  value={form['ai.temperature']}
                  onChange={(e) => updateField('ai.temperature', e.target.value)}
                />
                {errors['ai.temperature'] && (
                  <p className="text-sm text-destructive">{errors['ai.temperature']}</p>
                )}
                <p className="text-sm text-muted-foreground">응답의 창의성 수준 (0.0: 결정적, 1.0: 창의적)</p>
              </div>

              <Separator />

              {/* Max Tokens */}
              <div className="space-y-2">
                <Label htmlFor="ai-max-tokens">최대 응답 토큰</Label>
                <Input
                  id="ai-max-tokens"
                  type="number"
                  min={1}
                  max={65536}
                  className="w-full max-w-md"
                  value={form['ai.max_tokens']}
                  onChange={(e) => updateField('ai.max_tokens', e.target.value)}
                />
                {errors['ai.max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.max_tokens']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 응답의 최대 토큰 수 (1~65536)</p>
              </div>

              <Separator />

              {/* Session Max Tokens */}
              <div className="space-y-2">
                <Label htmlFor="ai-session-max-tokens">세션 최대 토큰</Label>
                <Input
                  id="ai-session-max-tokens"
                  type="number"
                  min={10000}
                  max={200000}
                  step={10000}
                  className="w-full max-w-md"
                  value={form['ai.session_max_tokens']}
                  onChange={(e) => updateField('ai.session_max_tokens', e.target.value)}
                />
                {errors['ai.session_max_tokens'] && (
                  <p className="text-sm text-destructive">{errors['ai.session_max_tokens']}</p>
                )}
                <p className="text-sm text-muted-foreground">
                  세션의 입력 토큰이 이 값을 초과하면 대화를 자동 요약하고 새 세션으로 전환합니다 (10,000~200,000)
                </p>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>시스템 프롬프트</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-2">
                <Textarea
                  id="ai-system-prompt"
                  rows={15}
                  className="font-mono text-sm"
                  value={form['ai.system_prompt']}
                  onChange={(e) => updateField('ai.system_prompt', e.target.value)}
                  placeholder="시스템 프롬프트를 입력하세요..."
                />
                {errors['ai.system_prompt'] && (
                  <p className="text-sm text-destructive">{errors['ai.system_prompt']}</p>
                )}
                <p className="text-sm text-muted-foreground">AI 에이전트의 역할과 동작을 정의하는 시스템 프롬프트</p>
              </div>
            </CardContent>
          </Card>

          <div className="flex items-center gap-3">
            <Button onClick={handleSave} disabled={isSaving || !hasChanges}>
              <Save className="mr-2 h-4 w-4" />
              {isSaving ? '저장 중...' : '저장'}
            </Button>
            <Button variant="outline" onClick={handleReset} disabled={!hasChanges}>
              <RotateCcw className="mr-2 h-4 w-4" />
              되돌리기
            </Button>
          </div>
        </TabsContent>
      </Tabs>
    </div>
  );
}
