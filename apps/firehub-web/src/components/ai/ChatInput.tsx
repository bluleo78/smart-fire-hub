import { File as FileIcon, Image, Paperclip, Send, StopCircle, X } from 'lucide-react';
import type { ClipboardEvent, DragEvent, KeyboardEvent } from 'react';
import { useRef, useState } from 'react';
import { toast } from 'sonner';

import { Button } from '../ui/button';
import { Textarea } from '../ui/textarea';

const ACCEPT_ATTR = 'image/*,.pdf,.txt,.md,.json,.xml,.yaml,.yml,.csv';

const SIZE_LIMITS: Record<string, number> = {
  IMAGE: 5 * 1024 * 1024,
  PDF: 10 * 1024 * 1024,
  TEXT: 1 * 1024 * 1024,
  DATA: 5 * 1024 * 1024,
};

function getCategory(mimeType: string): 'IMAGE' | 'PDF' | 'TEXT' | 'DATA' | null {
  if (mimeType.startsWith('image/')) return 'IMAGE';
  if (mimeType === 'application/pdf') return 'PDF';
  if (mimeType === 'text/csv') return 'DATA';
  if (
    mimeType.startsWith('text/') ||
    mimeType === 'application/json' ||
    mimeType === 'application/xml' ||
    mimeType === 'application/x-yaml'
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
  category: 'IMAGE' | 'PDF' | 'TEXT' | 'DATA';
}

interface ChatInputProps {
  onSend: (message: string, files?: File[]) => void;
  onStop?: () => void;
  isStreaming?: boolean;
  isUploading?: boolean;
  disabled?: boolean;
  placeholder?: string;
}

export function ChatInput({
  onSend,
  onStop,
  isStreaming = false,
  isUploading = false,
  disabled = false,
  placeholder = '메시지를 입력하세요...',
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

  const handleSend = () => {
    const hasMessage = message.trim().length > 0;
    const hasFiles = pendingFiles.length > 0;
    if ((!hasMessage && !hasFiles) || isStreaming || isUploading) return;

    onSend(message.trim(), pendingFiles.length > 0 ? pendingFiles.map(f => f.file) : undefined);
    setMessage('');
    setPendingFiles(prev => {
      prev.forEach(f => { if (f.previewUrl) URL.revokeObjectURL(f.previewUrl); });
      return [];
    });
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
  };

  const handleKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  const handleInput = (e: React.FormEvent<HTMLTextAreaElement>) => {
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
  const canSend = (message.trim().length > 0 || pendingFiles.length > 0) && !isStreaming && !isUploading;

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
          disabled={isDisabled}
          className="min-h-[44px] max-h-[150px] resize-none text-sm"
          rows={1}
        />
        {isStreaming && onStop ? (
          <Button onClick={onStop} variant="destructive" size="icon" className="shrink-0 h-[44px] w-[44px]">
            <StopCircle className="h-4 w-4" />
          </Button>
        ) : (
          <Button onClick={handleSend} disabled={!canSend} size="icon" className="shrink-0 h-[44px] w-[44px]">
            <Send className="h-4 w-4" />
          </Button>
        )}
      </div>
    </div>
  );
}
