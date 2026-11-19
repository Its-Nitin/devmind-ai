import { Component, DestroyRef, ElementRef, afterRenderEffect, inject, input, viewChild } from '@angular/core';
import { java } from '@codemirror/lang-java';
import { defaultHighlightStyle, syntaxHighlighting } from '@codemirror/language';
import { Compartment, EditorState, Extension, RangeSetBuilder, StateEffect, StateField } from '@codemirror/state';
import { Decoration, DecorationSet, EditorView, lineNumbers } from '@codemirror/view';

/** Lines to highlight, as numbered in the file (1-based, inclusive). */
export interface LineRange {
  readonly from: number;
  readonly to: number;
}

const setHighlight = StateEffect.define<LineRange | null>();
const highlightedLine = Decoration.line({ class: 'dm-highlighted-line' });

/** The highlighted lines, as relative line numbers of the document. */
const highlightField = StateField.define<DecorationSet>({
  create: () => Decoration.none,
  update(decorations, transaction) {
    let next = decorations.map(transaction.changes);
    for (const effect of transaction.effects) {
      if (effect.is(setHighlight)) {
        next = decorate(transaction.state, effect.value);
      }
    }
    return next;
  },
  provide: (field) => EditorView.decorations.from(field),
});

function decorate(state: EditorState, range: LineRange | null): DecorationSet {
  const builder = new RangeSetBuilder<Decoration>();
  if (range !== null) {
    for (let line = range.from; line <= range.to; line++) {
      const start = state.doc.line(line).from;
      builder.add(start, start, highlightedLine);
    }
  }
  return builder.finish();
}

/**
 * Read-only source viewer on CodeMirror 6 (ADR-0014). Presentational (FE-6): it takes the text and what to show, and
 * nothing else. The content goes in as editor text, never as HTML, so repository content can't inject markup (SEC-9,
 * FE-12). Line numbers start at `firstLine`, so a slice of a long file shows its real numbers.
 */
@Component({
  selector: 'dm-code-viewer',
  template: `<div #host class="code-viewer" role="region" [attr.aria-label]="label()"></div>`,
  styles: `
    .code-viewer {
      height: 100%;
      overflow: hidden;
      border: 1px solid var(--p-content-border-color, #ddd);
      border-radius: 4px;
    }
    :host {
      display: block;
      height: 100%;
    }
  `,
})
export class CodeViewer {
  readonly content = input.required<string>();
  /** A language ID from the backend, e.g. `java`; anything else shows as plain text. */
  readonly language = input('text');
  readonly firstLine = input(1);
  /** File line numbers to highlight and scroll to, or `null`. */
  readonly highlight = input<LineRange | null>(null);
  readonly label = input('Source code');

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('host');
  readonly #language = new Compartment();
  readonly #numbers = new Compartment();
  #view: EditorView | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.#view?.destroy());
    afterRenderEffect({
      write: () => {
        const content = this.content();
        const language = this.language();
        const firstLine = this.firstLine();
        const highlight = this.highlight();
        const view = (this.#view ??= this.#createView());
        view.dispatch({
          changes: view.state.doc.toString() === content ? [] : { from: 0, to: view.state.doc.length, insert: content },
          effects: [
            this.#language.reconfigure(languageSupport(language)),
            this.#numbers.reconfigure(numbering(firstLine)),
          ],
        });
        this.#showHighlight(view, firstLine, highlight);
      },
    });
  }

  #createView(): EditorView {
    return new EditorView({
      parent: this.host().nativeElement,
      state: EditorState.create({
        extensions: [
          EditorState.readOnly.of(true),
          EditorView.editable.of(false),
          this.#numbers.of(numbering(1)),
          this.#language.of([]),
          syntaxHighlighting(defaultHighlightStyle),
          highlightField,
          EditorView.theme({
            '&': { height: '100%', fontSize: '0.875rem' },
            '.cm-scroller': { fontFamily: 'ui-monospace, SFMono-Regular, Consolas, monospace' },
            '.dm-highlighted-line': { backgroundColor: 'rgba(255, 214, 0, 0.28)' },
          }),
        ],
      }),
    });
  }

  /** Highlights the range (clipped to the shown lines) and scrolls its first line into the middle of the view. */
  #showHighlight(view: EditorView, firstLine: number, highlight: LineRange | null): void {
    const lines = view.state.doc.lines;
    const from = highlight === null ? 0 : Math.max(1, highlight.from - firstLine + 1);
    const to = highlight === null ? 0 : Math.min(lines, highlight.to - firstLine + 1);
    const visible = highlight !== null && from <= to;
    view.dispatch({
      effects: [
        setHighlight.of(visible ? { from, to } : null),
        ...(visible ? [EditorView.scrollIntoView(view.state.doc.line(from).from, { y: 'center' })] : []),
      ],
    });
  }
}

function languageSupport(language: string): Extension {
  return language === 'java' ? java() : [];
}

function numbering(firstLine: number): Extension {
  return lineNumbers({ formatNumber: (line) => String(line + firstLine - 1) });
}
