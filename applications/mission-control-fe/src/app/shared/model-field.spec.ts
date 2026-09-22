import '@angular/compiler';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { ModelField } from './model-field';
import { ModelPicker } from './model-picker';

@Component({
  imports: [ModelField],
  template: `<div class="field"><label for="m">model</label><mc-model-field [picker]="picker" controlId="m" /></div>`,
})
class Host {
  picker = new ModelPicker();
}

/** `model` is handed in as the load's preferred selection — how a template's model arrives. */
const render = async (models: string[], model?: string) => {
  TestBed.resetTestingModule();
  const fixture = TestBed.createComponent(Host);
  const picker = fixture.componentInstance.picker;
  await picker.load(Promise.resolve({ models, source: 'catalog' }), { preferred: model });
  fixture.detectChanges();
  await fixture.whenStable();
  fixture.detectChanges();
  const root = fixture.nativeElement as HTMLElement;
  return { fixture, picker, root };
};

const options = (root: HTMLElement): string[] =>
  Array.from(root.querySelectorAll<HTMLOptionElement>('option')).map(o => o.value);

describe('ModelField', () => {
  it('shows every suggestion in a dropdown, whatever the current value is', async () => {
    // the `<datalist>` this replaces was filtered by the preselected first entry, so an
    // operator saw one model and read it as the whole catalog
    const { root, picker } = await render(['gpt-5.2', 'gpt-5.2-mini', 'gpt-5.1']);

    expect(picker.model).toBe('gpt-5.2');
    expect(options(root)).toEqual(['gpt-5.2', 'gpt-5.2-mini', 'gpt-5.1', '']);
    expect(root.querySelector('.input')).toBeNull();
    // one element carries the id, and it is the control the label points at — not the host
    expect(Array.from(root.querySelectorAll('#m')).map(e => e.tagName)).toEqual(['SELECT']);
  });

  it('keeps a model the list does not carry as the leading option', async () => {
    const { root } = await render(['claude-opus-5'], 'claude-sonnet-4');

    expect(options(root)).toEqual(['claude-sonnet-4', 'claude-opus-5', '']);
    expect(root.querySelector<HTMLSelectElement>('.select')!.value).toBe('claude-sonnet-4');
  });

  it('switches to free text on "other…", and back on request', async () => {
    const { fixture, root, picker } = await render(['gpt-5.2']);

    const select = root.querySelector<HTMLSelectElement>('.select')!;
    select.value = '';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();

    expect(picker.custom()).toBe(true);
    expect(root.querySelector('.select')).toBeNull();
    expect(root.querySelector<HTMLInputElement>('.input')!.value).toBe('');

    root.querySelector<HTMLButtonElement>('button')!.click();
    fixture.detectChanges();
    await fixture.whenStable();   // `[ngModel]` writes the select's value a tick later
    fixture.detectChanges();

    // back on the list, and on a model — not on the "other…" entry just left
    expect(root.querySelector<HTMLSelectElement>('.select')!.value).toBe('gpt-5.2');
    expect(picker.model).toBe('gpt-5.2');
  });

  it('is a plain input when there is nothing to suggest', async () => {
    const { root } = await render([], 'some-local-build');

    expect(root.querySelector('.select')).toBeNull();
    expect(root.querySelector<HTMLInputElement>('.input')!.value).toBe('some-local-build');
    // nothing to go back to, so no way-back button either
    expect(root.querySelector('button')).toBeNull();
  });
});
