import { useState, useEffect, useCallback } from 'react';
import { Card, CardContent, CardHeader, CardTitle } from '../../components/ui/card';
import { Button } from '../../components/ui/button';
import { Input } from '../../components/ui/input';
import { Label } from '../../components/ui/label';
import { Textarea } from '../../components/ui/textarea';
import { Skeleton } from '../../components/ui/skeleton';
import { Separator } from '../../components/ui/separator';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select';
import { toast } from 'sonner';
import { settingsApi } from '../../api/settings';
import { Bot, Save, RotateCcw } from 'lucide-react';
import type { SettingResponse } from '../../types/settings';

const MODEL_OPTIONS = [
  { value: 'claude-sonnet-4-6', label: 'Claude Sonnet 4.6' },
  { value: 'claude-opus-4-6', label: 'Claude Opus 4.6' },
  { value: 'claude-haiku-4-5-20251001', label: 'Claude Haiku 4.5' },
];

interface AISettingsForm {
  'ai.model': string;
  'ai.max_turns': string;
  'ai.system_prompt': string;
  'ai.temperature': string;
  'ai.max_tokens': string;
}

const DEFAULT_VALUES: AISettingsForm = {
  'ai.model': 'claude-sonnet-4-6',
  'ai.max_turns': '10',
  'ai.system_prompt': '',
  'ai.temperature': '1.0',
  'ai.max_tokens': '16384',
};

export default function AISettingsPage() {
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [form, setForm] = useState<AISettingsForm>(DEFAULT_VALUES);
  const [original, setOriginal] = useState<AISettingsForm>(DEFAULT_VALUES);
  const [errors, setErrors] = useState<Partial<Record<keyof AISettingsForm, string>>>({});

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
      toast.error('AI 설정을 불러오는데 실패했습니다.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchSettings();
  }, [fetchSettings]);

  const validate = (): boolean => {
    const newErrors: Partial<Record<keyof AISettingsForm, string>> = {};

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
      await settingsApi.update({ settings: form });
      setOriginal({ ...form });
      toast.success('AI 설정이 저장되었습니다.');
    } catch {
      toast.error('AI 설정 저장에 실패했습니다.');
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
      <div className="space-y-6 p-6">
        <Skeleton className="h-8 w-48" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      <div className="flex items-center gap-3">
        <Bot className="h-6 w-6" />
        <h1 className="text-2xl font-bold">AI 설정</h1>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>모델 설정</CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
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
    </div>
  );
}
