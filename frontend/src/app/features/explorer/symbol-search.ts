import { Component, input, output, signal } from '@angular/core';
import { outputFromObservable, toObservable } from '@angular/core/rxjs-interop';
import { InputText } from 'primeng/inputtext';
import { Tag } from 'primeng/tag';
import { debounceTime, distinctUntilChanged, map } from 'rxjs';
import { SymbolHit } from './explorer-api';

/** How long typing must pause before a search runs. */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * Presentational symbol search (FE-6): a search box whose input is debounced before it's emitted, and the results,
 * each a button that asks to open the symbol's lines. Kind is shown as text, not just colour (FE-11).
 */
@Component({
  selector: 'dm-symbol-search',
  imports: [InputText, Tag],
  template: `
    <div class="search">
      <label for="symbol-query" class="visually-hidden">Search symbols</label>
      <input
        pInputText
        id="symbol-query"
        type="search"
        placeholder="Search symbols, e.g. OwnerService"
        maxlength="200"
        [value]="text()"
        (input)="onInput($event)"
      />
      @if (searching()) {
        <p role="status">Searching…</p>
      } @else if (searched() && results().length === 0) {
        <p role="status">No symbols match.</p>
      }
      <ul class="results">
        @for (symbol of results(); track symbol.symbolId + symbol.path) {
          <li>
            <button type="button" class="result" (click)="selectSymbol.emit(symbol)">
              <p-tag [value]="symbol.kind" severity="secondary" />
              <span class="name">{{ symbol.qualifiedName }}</span>
              <span class="where">{{ symbol.path }}:{{ symbol.startLine }}–{{ symbol.endLine }}</span>
            </button>
          </li>
        }
      </ul>
    </div>
  `,
  styles: `
    .search {
      display: grid;
      gap: 0.5rem;
    }
    .results {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 16rem;
      overflow: auto;
    }
    .result {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 0.1rem 0.5rem;
      width: 100%;
      text-align: left;
      background: none;
      border: 0;
      padding: 0.35rem;
      cursor: pointer;
    }
    .result:hover,
    .result:focus-visible {
      background: var(--p-highlight-background, #eef);
    }
    .where {
      grid-column: 2;
      font-size: 0.8rem;
      opacity: 0.8;
      word-break: break-all;
    }
  `,
})
export class SymbolSearch {
  readonly results = input.required<readonly SymbolHit[]>();
  readonly searching = input(false);
  /** Whether a non-blank query has been searched, to tell "no results" from "not searched yet". */
  readonly searched = input(false);
  readonly selectSymbol = output<SymbolHit>();

  protected readonly text = signal('');

  protected onInput(event: Event): void {
    if (event.target instanceof HTMLInputElement) {
      this.text.set(event.target.value);
    }
  }

  /** The query, once typing has paused for {@link SEARCH_DEBOUNCE_MS} and only if it changed. */
  readonly query = outputFromObservable(
    toObservable(this.text).pipe(
      debounceTime(SEARCH_DEBOUNCE_MS),
      map((text) => text.trim()),
      distinctUntilChanged(),
    ),
  );
}
