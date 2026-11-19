import { TestBed } from '@angular/core/testing';
import { CodeViewer } from './code-viewer';

describe('CodeViewer', () => {
  function render(inputs: Record<string, unknown>): HTMLElement {
    const fixture = TestBed.createComponent(CodeViewer);
    for (const [name, value] of Object.entries(inputs)) {
      fixture.componentRef.setInput(name, value);
    }
    fixture.detectChanges();
    TestBed.tick();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows content as text, never as markup (SEC-9)', () => {
    const element = render({ content: '<img src=x onerror=alert(1)>\nline 2' });

    expect(element.querySelector('img')).toBeNull();
    expect(element.querySelector('.cm-content')?.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  it('is read-only', () => {
    const element = render({ content: 'class A {}' });

    expect(element.querySelector('.cm-content')?.getAttribute('contenteditable')).toBe('false');
  });

  it('numbers lines from the first line of the slice', () => {
    const element = render({ content: 'a\nb\nc', firstLine: 41 });

    const numbers = [...element.querySelectorAll('.cm-lineNumbers .cm-gutterElement')].map((n) => n.textContent);
    expect(numbers).toEqual(expect.arrayContaining(['41', '42', '43']));
  });

  it('highlights the requested lines of the slice', () => {
    const element = render({ content: 'a\nb\nc\nd', firstLine: 10, highlight: { from: 11, to: 12 } });

    const highlighted = [...element.querySelectorAll('.cm-line.dm-highlighted-line')].map((l) => l.textContent);
    expect(highlighted).toEqual(['b', 'c']);
  });
});
