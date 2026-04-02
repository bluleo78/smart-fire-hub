import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { json } from '@codemirror/lang-json';
import { type Diagnostic,linter } from '@codemirror/lint';
import { searchKeymap } from '@codemirror/search';
import { EditorState } from '@codemirror/state';
import { oneDark } from '@codemirror/theme-one-dark';
import { EditorView, keymap, lineNumbers } from '@codemirror/view';
import { useEffect, useRef } from 'react';

import type { TemplateSection } from '@/api/proactive';
import { Button } from '@/components/ui/button';
import { type SectionTypeDefinition, SECTION_TYPES } from '@/lib/template-section-types';

interface TemplateJsonEditorProps {
  value: string;
  onChange: (value: string) => void;
  readonly?: boolean;
}

function jsonLinter() {
  return linter((view) => {
    const diagnostics: Diagnostic[] = [];
    const doc = view.state.doc.toString();
    if (!doc.trim()) return diagnostics;
    try {
      JSON.parse(doc);
    } catch (e) {
      const message = e instanceof SyntaxError ? e.message : 'Invalid JSON';
      const posMatch = message.match(/position (\d+)/);
      const pos = posMatch ? Number(posMatch[1]) : 0;
      diagnostics.push({
        from: Math.min(pos, doc.length),
        to: Math.min(pos + 1, doc.length),
        severity: 'error',
        message,
      });
    }
    return diagnostics;
  });
}

export function TemplateJsonEditor({ value, onChange, readonly = false }: TemplateJsonEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const onChangeRef = useRef(onChange);
  onChangeRef.current = onChange;

  useEffect(() => {
    if (!containerRef.current) return;

    const extensions = [
      json(),
      oneDark,
      lineNumbers(),
      history(),
      keymap.of([...defaultKeymap, ...historyKeymap, ...searchKeymap]),
      EditorView.theme({
        '&': {
          fontSize: '13px',
          border: '1px solid hsl(var(--border))',
          borderRadius: '6px',
        },
        '.cm-scroller': {
          overflow: 'auto',
          minHeight: '200px',
          maxHeight: '500px',
        },
      }),
    ];

    if (readonly) {
      extensions.push(EditorView.editable.of(false), EditorState.readOnly.of(true));
    } else {
      extensions.push(
        jsonLinter(),
        EditorView.updateListener.of((update) => {
          if (update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
      );
    }

    const state = EditorState.create({ doc: value, extensions });
    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readonly]);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const currentDoc = view.state.doc.toString();
    if (currentDoc !== value) {
      view.dispatch({
        changes: { from: 0, to: currentDoc.length, insert: value },
      });
    }
  }, [value]);

  const handleInsertSection = (snippet: SectionTypeDefinition['snippet']) => {
    const view = viewRef.current;
    if (!view || readonly) return;

    const doc = view.state.doc.toString();
    try {
      const parsed = JSON.parse(doc);
      const sections = Array.isArray(parsed.sections) ? parsed.sections : [];

      let key = snippet.key;
      let counter = 1;
      while (sections.some((s: TemplateSection) => s.key === key)) {
        key = `${snippet.key}_${counter++}`;
      }

      sections.push({ ...snippet, key });
      const newDoc = JSON.stringify({ ...parsed, sections }, null, 2);
      onChangeRef.current(newDoc);
    } catch {
      // JSON invalid — user should fix before inserting
    }
  };

  return (
    <div className="flex flex-col">
      {!readonly && (
        <div className="flex items-center gap-1.5 p-2 bg-muted/50 border border-b-0 border-border rounded-t-md flex-wrap">
          <span className="text-xs text-muted-foreground mr-1">섹션 추가:</span>
          {SECTION_TYPES.map((st) => (
            <Button
              key={st.type}
              variant="outline"
              size="sm"
              className="h-6 px-2 text-xs gap-1"
              onClick={() => handleInsertSection(st.snippet)}
            >
              <span>{st.icon}</span>
              <span>{st.label}</span>
            </Button>
          ))}
        </div>
      )}
      <div ref={containerRef} className={!readonly ? '[&_.cm-editor]:rounded-t-none' : ''} />
    </div>
  );
}
