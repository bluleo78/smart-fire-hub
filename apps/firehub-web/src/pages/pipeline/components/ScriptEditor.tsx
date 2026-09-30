import { defaultKeymap, history, historyKeymap } from '@codemirror/commands';
import { python } from '@codemirror/lang-python';
import { sql } from '@codemirror/lang-sql';
import { bracketMatching } from '@codemirror/language';
import { Compartment,EditorState } from '@codemirror/state';
import { Decoration, type DecorationSet, EditorView, keymap,lineNumbers, MatchDecorator, ViewPlugin,type ViewUpdate } from '@codemirror/view';
import { useTheme } from 'next-themes';
import { useEffect, useRef } from 'react';

const stepRefMatcher = new MatchDecorator({
  regexp: /\{\{#\d+\}\}/g,
  decoration: Decoration.mark({ class: 'cm-step-ref' }),
});

const stepRefHighlighter = ViewPlugin.fromClass(
  class {
    decorations: DecorationSet;
    constructor(view: EditorView) {
      this.decorations = stepRefMatcher.createDeco(view);
    }
    update(update: ViewUpdate) {
      this.decorations = stepRefMatcher.updateDeco(update, this.decorations);
    }
  },
  { decorations: (v) => v.decorations },
);

interface ScriptEditorProps {
  value: string;
  onChange: (value: string) => void;
  language: 'SQL' | 'PYTHON';
  readOnly?: boolean;
  insertTextRef?: React.MutableRefObject<((text: string) => void) | null>;
}

function buildStepRefTheme(isDark: boolean) {
  return EditorView.theme({
    '&': { minHeight: '200px', height: '100%', ...(isDark ? { backgroundColor: 'oklch(0.13 0.015 280)' } : {}) },
    '.cm-scroller': { overflow: 'auto' },
    '.cm-content': { fontFamily: 'monospace', fontSize: '13px' },
    '.cm-gutters': isDark ? { backgroundColor: 'oklch(0.15 0.015 280)' } : {},
    '.cm-activeLineGutter': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-activeLine': isDark ? { backgroundColor: 'oklch(1 0 0 / 5%)' } : {},
    '.cm-step-ref': {
      background: isDark ? 'rgba(167, 139, 250, 0.22)' : 'rgba(124, 58, 237, 0.15)',
      borderRadius: '3px',
      padding: '1px 2px',
      fontWeight: '600',
    },
  });
}

export default function ScriptEditor({ value, onChange, language, readOnly = false, insertTextRef }: ScriptEditorProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const viewRef = useRef<EditorView | null>(null);
  const languageCompartment = useRef(new Compartment());
  const readOnlyCompartment = useRef(new Compartment());
  const themeCompartment = useRef(new Compartment());
  const onChangeRef = useRef(onChange);
  // 에디터는 마운트 시 한 번만 만들어지므로, updateListener 가 readOnly 를 클로저로 잡으면
  // 보기 모드(readOnly=true)로 마운트된 뒤 수정 모드로 바뀌어도 계속 true 로 판단해 onChange 를 삼킨다(#740).
  // onChange 와 마찬가지로 ref 로 최신 값을 읽게 한다.
  const readOnlyRef = useRef(readOnly);
  // readOnly 전환 시 뷰 문서를 편집 상태(value)와 맞추기 위해 최신 value 를 ref 로 둔다(#751).
  const valueRef = useRef(value);
  const { resolvedTheme } = useTheme();

  // Keep onChange ref current without recreating editor
  onChangeRef.current = onChange;
  readOnlyRef.current = readOnly;
  valueRef.current = value;

  // Create editor on mount
  useEffect(() => {
    if (!containerRef.current) return;

    const langExtension = language === 'SQL' ? sql() : python();

    const state = EditorState.create({
      doc: value,
      extensions: [
        lineNumbers(),
        history(),
        bracketMatching(),
        keymap.of([...defaultKeymap, ...historyKeymap]),
        languageCompartment.current.of(langExtension),
        readOnlyCompartment.current.of([
          EditorView.editable.of(!readOnly),
          EditorState.readOnly.of(!!readOnly),
        ]),
        stepRefHighlighter,
        EditorView.updateListener.of((update) => {
          // 마운트 시점 값이 아니라 현재 readOnly 로 판정한다 — 보기→수정 전환 후 입력도 편집 상태에 반영된다
          if (!readOnlyRef.current && update.docChanged) {
            onChangeRef.current(update.state.doc.toString());
          }
        }),
        themeCompartment.current.of(buildStepRefTheme(resolvedTheme === 'dark')),
      ],
    });

    const view = new EditorView({ state, parent: containerRef.current });
    viewRef.current = view;

    return () => {
      view.destroy();
      viewRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Bind insertText function to ref
  useEffect(() => {
    if (!insertTextRef) return;
    insertTextRef.current = (text: string) => {
      const view = viewRef.current;
      if (!view) return;
      // EditorState.readOnly/editable 은 사용자 입력만 막고 프로그램적 dispatch 는 막지 않는다.
      // 읽기 전용에서 삽입하면 updateListener 가 onChange 를 부르지 않아 뷰 문서만 바뀌고 편집 상태와 어긋나,
      // 수정 모드 전환 후 첫 입력 때 유령 텍스트까지 저장된다(#751). 읽기 전용이면 아무것도 하지 않는다.
      if (readOnlyRef.current) return;
      // changes 만 dispatch 하면 CodeMirror 가 커서를 삽입 "앞"에 매핑해, 이어서 입력한 글자가
      // 삽입한 {{#N}} 앞에 들어간다(#743). replaceSelection 은 선택 영역을 교체(선택 없으면 커서에 삽입)하고
      // 커서를 삽입 끝으로 옮긴 트랜잭션 스펙을 만들어 준다. 버튼 클릭으로 잃은 포커스도 돌려준다.
      view.dispatch({
        ...view.state.replaceSelection(text),
        scrollIntoView: true,
      });
      view.focus();
    };
    return () => {
      if (insertTextRef) insertTextRef.current = null;
    };
  }, [insertTextRef]);

  // Reconfigure language when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const langExtension = language === 'SQL' ? sql() : python();
    view.dispatch({ effects: languageCompartment.current.reconfigure(langExtension) });
  }, [language]);

  // Reconfigure readOnly when it changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({
      effects: readOnlyCompartment.current.reconfigure([
        EditorView.editable.of(!readOnly),
        EditorState.readOnly.of(!!readOnly),
      ]),
    });
    // 방어적 동기화(#751): 읽기 전용 동안 프로그램적 경로로 뷰 문서가 편집 상태와 어긋났다면,
    // 모드가 바뀌는 시점에 편집 상태(value) 기준으로 되돌려 유령 텍스트가 저장으로 이어지지 않게 한다.
    const current = view.state.doc.toString();
    if (current !== valueRef.current) {
      view.dispatch({ changes: { from: 0, to: current.length, insert: valueRef.current } });
    }
  }, [readOnly]);

  // Reconfigure theme when dark/light changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    view.dispatch({ effects: themeCompartment.current.reconfigure(buildStepRefTheme(resolvedTheme === 'dark')) });
  }, [resolvedTheme]);

  // Sync external value changes
  useEffect(() => {
    const view = viewRef.current;
    if (!view) return;
    const current = view.state.doc.toString();
    if (current !== value) {
      view.dispatch({
        changes: { from: 0, to: current.length, insert: value },
      });
    }
  }, [value]);

  return (
    <div
      ref={containerRef}
      className="border rounded-md overflow-hidden w-full min-w-0"
      style={{ minHeight: '200px', maxWidth: '100%' }}
    />
  );
}
