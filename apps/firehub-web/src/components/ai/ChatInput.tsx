import { File as FileIcon, Image, Paperclip, Send, StopCircle, X } from 'lucide-react';
import type { ClipboardEvent, DragEvent, KeyboardEvent, RefObject } from 'react';
import { useEffect, useId, useRef, useState } from 'react';
import { toast } from 'sonner';

import { MAX_CHAT_MESSAGE_LENGTH, messageTooLongHint } from '../../lib/ai-chat-limits';
import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';

const ACCEPT_ATTR = 'image/*,.pdf,.txt,.md,.json,.xml,.yaml,.yml,.csv,.xlsx,.docx';

const DOCX_MIME = 'application/vnd.openxmlformats-officedocument.wordprocessingml.document';
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

// 파일 타입별 첨부 사이즈 제한 — 서버 FileUploadService.CATEGORY_SIZE_LIMITS 와 동일하게 유지.
// DATA(CSV/XLSX) 만 대용량 적재를 위해 256MB, 그 외는 10MB.
const SIZE_LIMITS: Record<string, number> = {
  IMAGE: 10 * 1024 * 1024,
  PDF: 10 * 1024 * 1024,
  TEXT: 10 * 1024 * 1024,
  DATA: 256 * 1024 * 1024,
  DOCUMENT: 10 * 1024 * 1024,
};

function getCategory(mimeType: string): 'IMAGE' | 'PDF' | 'TEXT' | 'DATA' | 'DOCUMENT' | null {
  if (mimeType.startsWith('image/')) return 'IMAGE';
  if (mimeType === 'application/pdf') return 'PDF';
  if (mimeType === 'text/csv' || mimeType === XLSX_MIME) return 'DATA';
  if (mimeType === DOCX_MIME) return 'DOCUMENT';
  if (
    mimeType.startsWith('text/') ||
    mimeType === 'application/json' ||
    mimeType === 'application/xml' ||
    mimeType === 'application/x-yaml' ||
    mimeType === 'application/yaml'   // IANA 표준 YAML MIME 타입 추가 (refs #212)
  ) return 'TEXT';
  return null;
}

function formatFileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes}B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)}KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)}MB`;
}

interface PendingFile {
  file: File;
  previewUrl?: string;
  category: 'IMAGE' | 'PDF' | 'TEXT' | 'DATA' | 'DOCUMENT';
}

interface ChatInputProps {
  onSend: (message: string, files?: File[]) => void;
  onStop?: () => void;
  isStreaming?: boolean;
  isUploading?: boolean;
  disabled?: boolean;
  placeholder?: string;
  /** 패널 열림 여부(WD-579). false→true 로 바뀌면(열린 채 마운트 포함) 입력창에 포커스한다. 미지정이면 포커스하지 않는다. */
  focusOnOpen?: boolean;
  /** 패널이 열린 순간의 포커스 위치(WD-579). 포커스가 아직 그 자리(예: AI 상태 칩)면 옮겨도 된다. */
  focusOriginRef?: RefObject<Element | null>;
}

export function ChatInput({
  onSend,
  onStop,
  isStreaming = false,
  isUploading = false,
  disabled = false,
  placeholder = '메시지를 입력하세요...',
  focusOnOpen = false,
  focusOriginRef,
}: ChatInputProps) {
  const [message, setMessage] = useState('');
  const [pendingFiles, setPendingFiles] = useState<PendingFile[]>([]);
  const [isDragging, setIsDragging] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const addFiles = (newFiles: File[]) => {
    const valid: PendingFile[] = [];
    for (const file of newFiles) {
      const category = getCategory(file.type);
      if (!category) {
        toast.error(`지원하지 않는 파일 형식입니다: ${file.name}`);
        continue;
      }
      const limit = SIZE_LIMITS[category];
      if (file.size > limit) {
        toast.error(`파일이 너무 큽니다: ${file.name} (최대 ${formatFileSize(limit)})`);
        continue;
      }
      if (pendingFiles.length + valid.length >= 3) {
        toast.error('파일은 최대 3개까지 첨부할 수 있습니다.');
        break;
      }
      valid.push({
        file,
        category,
        previewUrl: category === 'IMAGE' ? URL.createObjectURL(file) : undefined,
      });
    }
    if (valid.length > 0) {
      setPendingFiles(prev => [...prev, ...valid]);
    }
  };

  const removeFile = (index: number) => {
    setPendingFiles(prev => {
      const next = [...prev];
      if (next[index].previewUrl) URL.revokeObjectURL(next[index].previewUrl!);
      next.splice(index, 1);
      return next;
    });
  };

  // 실제로 보내는 값(trim 결과)의 길이로 서버 상한(50,000자)을 미리 검사한다(WD-631).
  // 넘으면 잘라 보내지 않고 전송만 막는다 — 사용자가 직접 줄일 수 있게 글은 그대로 둔다.
  const trimmed = message.trim();
  const isTooLong = trimmed.length > MAX_CHAT_MESSAGE_LENGTH;
  // 패널 모드(사이드·전체화면)가 바뀌어도 id 가 겹치지 않게 useId 로 만든다
  const tooLongHintId = useId();
  // 보낼 수 있는 상태 — 글이나 첨부가 있고, 길이 초과·스트리밍·업로드 중이 아닐 때. 버튼 활성과 Enter 전송이 같은 조건을 쓴다.
  const canSend = (trimmed.length > 0 || pendingFiles.length > 0) && !isTooLong && !isStreaming && !isUploading;

  const handleSend = () => {
    if (!canSend) return;

    onSend(trimmed, pendingFiles.length > 0 ? pendingFiles.map(f => f.file) : undefined);
    setMessage('');
    setPendingFiles(prev => {
      prev.forEach(f => { if (f.previewUrl) URL.revokeObjectURL(f.previewUrl); });
      return [];
    });
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    // 한글/일본어/중국어 등 IME 조합 확정용 Enter는 전송 트리거로 처리하지 않는다.
    // CJK IME는 조합을 확정할 때도 네이티브 keydown에 key: 'Enter'를 실어 보내므로
    // e.nativeEvent.isComposing으로 "전송 의도 Enter"와 구분한다 (WD-54).
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInput = (e: React.InputEvent<HTMLTextAreaElement>) => {
    const target = e.currentTarget;
    target.style.height = 'auto';
    target.style.height = `${Math.min(target.scrollHeight, 150)}px`;
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files) {
      addFiles(Array.from(e.target.files));
      e.target.value = '';
    }
  };

  const handleDragOver = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(true);
  };

  const handleDragLeave = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
    if (e.dataTransfer.files) {
      addFiles(Array.from(e.dataTransfer.files));
    }
  };

  const handlePaste = (e: ClipboardEvent<HTMLTextAreaElement>) => {
    const items = Array.from(e.clipboardData.items);
    const imageItems = items.filter(item => item.type.startsWith('image/'));
    if (imageItems.length > 0) {
      e.preventDefault();
      const files = imageItems
        .map(item => item.getAsFile())
        .filter((f): f is File => f !== null);
      addFiles(files);
    }
  };

  const isDisabled = disabled || isStreaming || isUploading;

  // 열림 포커스(WD-579). 스트리밍·업로드 중엔 textarea 가 disabled 라 focus() 가 무시되므로
  // 열림 요청을 대기로 두었다가 입력창이 활성화되면 포커스한다. wasOpenRef 초기값 false 라 열린 채 마운트도 열림으로 본다.
  const pendingFocusRef = useRef(false);
  const wasOpenRef = useRef(false);
  useEffect(() => {
    if (focusOnOpen && !wasOpenRef.current) pendingFocusRef.current = true;
    if (!focusOnOpen) pendingFocusRef.current = false;
    wasOpenRef.current = focusOnOpen;
    if (!pendingFocusRef.current || isDisabled) return;
    const el = textareaRef.current;
    if (!el) return;
    pendingFocusRef.current = false;
    // 빼앗지 않기: <body>·같은 채팅 패널 안·열던 순간의 자리일 때만 옮긴다. 열린 뒤 사용자가 다른 곳으로 옮겼으면 존중.
    const active = document.activeElement;
    const panel = el.closest('[data-ai-chat-panel]');
    const free = active === document.body || !!panel?.contains(active) || active === focusOriginRef?.current;
    if (!free) return;
    // preventScroll: 폭 전환 중인 사이드 패널의 overflow-hidden 조상이 스크롤되지 않게 한다.
    el.focus({ preventScroll: true });
  }, [focusOnOpen, isDisabled, focusOriginRef]);

  return (
    <div
      className={`flex flex-col gap-2 rounded-lg border transition-colors ${isDragging ? 'border-primary bg-primary/5' : 'border-transparent'}`}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {pendingFiles.length > 0 && (
        <div className="flex flex-wrap gap-2 px-1 pt-1">
          {pendingFiles.map((pf, i) => (
            <div
              key={i}
              className="relative flex items-center gap-1.5 rounded-md border border-border bg-muted/50 px-2 py-1.5 text-xs"
            >
              {pf.category === 'IMAGE' && pf.previewUrl ? (
                <img
                  src={pf.previewUrl}
                  alt={pf.file.name}
                  className="h-10 w-10 rounded object-cover"
                />
              ) : (
                <div className="flex h-10 w-10 items-center justify-center rounded bg-muted">
                  {pf.category === 'IMAGE' ? (
                    <Image className="h-5 w-5 text-muted-foreground" />
                  ) : (
                    <FileIcon className="h-5 w-5 text-muted-foreground" />
                  )}
                </div>
              )}
              <div className="flex max-w-[120px] flex-col">
                <span className="truncate font-medium">{pf.file.name}</span>
                <span className="text-muted-foreground">{formatFileSize(pf.file.size)}</span>
              </div>
              <button
                type="button"
                onClick={() => removeFile(i)}
                className="ml-1 rounded-full p-0.5 hover:bg-destructive/10 hover:text-destructive"
              >
                <X className="h-3 w-3" />
              </button>
            </div>
          ))}
        </div>
      )}

      <div className="flex gap-2">
        <input
          ref={fileInputRef}
          type="file"
          multiple
          accept={ACCEPT_ATTR}
          className="hidden"
          onChange={handleFileChange}
        />
        <Button
          type="button"
          variant="ghost"
          size="icon"
          className="shrink-0 h-[44px] w-[44px] text-muted-foreground hover:text-foreground"
          disabled={isDisabled || pendingFiles.length >= 3}
          onClick={() => fileInputRef.current?.click()}
          title="파일 첨부"
        >
          <Paperclip className="h-4 w-4" />
        </Button>
        <Textarea
          ref={textareaRef}
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          onKeyDown={handleKeyDown}
          onInput={handleInput}
          onPaste={handlePaste}
          placeholder={placeholder}
          // placeholder 는 입력하면 사라져 이름이 될 수 없으므로 접근 가능한 이름을 따로 준다(WD-590).
          aria-label="메시지 입력"
          // 길이 초과 안내를 입력창과 잇는다 — 스크린리더가 입력창에서 바로 이유를 듣게(WD-631)
          aria-invalid={isTooLong || undefined}
          aria-describedby={isTooLong ? tooLongHintId : undefined}
          disabled={isDisabled}
          className="min-h-[44px] max-h-[150px] resize-none text-sm"
          rows={1}
        />
        {/* #341: 전송/중단은 같은 자리에서 토글되므로 서로 다른 key를 줘서
            스크린리더가 "이름이 바뀐 같은 버튼"이 아니라 교체된 컨트롤로 인지하게 한다. */}
        {isStreaming && onStop ? (
          <Button
            key="stop"
            onClick={onStop}
            variant="destructive"
            size="icon"
            className="shrink-0 h-[44px] w-[44px]"
            aria-label="응답 중단"
            title="응답 중단"
          >
            <StopCircle className="h-4 w-4" />
          </Button>
        ) : (
          <Button
            key="send"
            onClick={handleSend}
            disabled={!canSend}
            size="icon"
            className="shrink-0 h-[44px] w-[44px]"
            aria-label="메시지 전송"
            title="메시지 전송"
          >
            <Send className="h-4 w-4" />
          </Button>
        )}
      </div>
      {/* 메시지 길이 초과 안내(WD-631) — 서버 검증 문구와 같은 표현 + 현재 길이. 초과 동안만 보인다.
          role="alert" 를 두지 않는 이유: 글자마다 숫자가 바뀌어 매 입력마다 다시 읽히기 때문 — 입력창의 aria-describedby 로 잇는다. */}
      {isTooLong && (
        <p id={tooLongHintId} className="px-1 text-xs text-destructive">
          {messageTooLongHint(trimmed.length)}
        </p>
      )}
    </div>
  );
}
