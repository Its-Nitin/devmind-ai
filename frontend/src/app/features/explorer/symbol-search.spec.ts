import { TestBed } from '@angular/core/testing';
import { SEARCH_DEBOUNCE_MS, SymbolSearch } from './symbol-search';

describe('SymbolSearch', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  function render(): { type: (text: string) => void; queries: string[] } {
    const fixture = TestBed.createComponent(SymbolSearch);
    fixture.componentRef.setInput('results', []);
    const queries: string[] = [];
    fixture.componentInstance.query.subscribe((q) => queries.push(q));
    fixture.detectChanges();
    const input = (fixture.nativeElement as HTMLElement).querySelector('input');
    if (input === null) {
      throw new Error('no search input');
    }
    return {
      queries,
      type: (text: string) => {
        input.value = text;
        input.dispatchEvent(new Event('input'));
        TestBed.tick();
      },
    };
  }

  it('emits the query only once typing pauses for the debounce time', () => {
    const { type, queries } = render();
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS); // the initial empty value
    queries.length = 0;

    type('O');
    vi.advanceTimersByTime(100);
    type('Own');
    vi.advanceTimersByTime(100);
    type('Owner');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1);
    expect(queries).toEqual([]);

    vi.advanceTimersByTime(1);
    expect(queries).toEqual(['Owner']);
  });

  it('trims the query and skips repeats', () => {
    const { type, queries } = render();
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    queries.length = 0;

    type('Owner ');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);
    type(' Owner');
    vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS);

    expect(queries).toEqual(['Owner']);
  });
});
