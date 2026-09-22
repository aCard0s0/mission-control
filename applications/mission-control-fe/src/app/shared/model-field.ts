import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ModelPicker } from './model-picker';

/**
 * The control behind a {@link ModelPicker}: a dropdown while there is a list to pick from, a
 * text input otherwise.
 *
 * <p>It used to be one `<input list>` over a `<datalist>`. The picker preselects the first
 * suggestion, and a browser filters a datalist by what the input already holds — so an
 * operator opening the list for OpenAI saw `gpt-5.2` and nothing else, and read that as the
 * whole catalog. A `<select>` shows every entry regardless of the current value. Free text
 * stays reachable through the trailing "other…" entry, because a shipped list can lag the
 * provider and a blueprint may name a model no catalog has answered with yet.
 *
 * <p>A model the list does not carry — one a template names, say — is kept as the first
 * option rather than silently replaced, the same way the picker keeps it.
 */
@Component({
  selector: 'mc-model-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    @if (picker().suggestions().length && !picker().custom()) {
      <select [id]="controlId()" class="select" [ngModel]="picker().model"
              (ngModelChange)="picker().pick($event)">
        @if (picker().model && !picker().suggestions().includes(picker().model)) {
          <option [value]="picker().model">{{ picker().model }}</option>
        }
        @for (m of picker().suggestions(); track m) { <option [value]="m">{{ m }}</option> }
        <option value="">other…</option>
      </select>
    } @else {
      <input [id]="controlId()" class="input" [(ngModel)]="picker().model"
             placeholder="model id" autocomplete="off" />
      @if (picker().custom() && picker().suggestions().length) {
        <button type="button" class="btn ghost" (click)="picker().backToList()">pick from the list</button>
      }
    }
  `,
})
export class ModelField {
  readonly picker = input.required<ModelPicker>();
  /** The `id` the surrounding `<label for>` points at. Not `id`: an input by that name is also
   *  the host element's attribute, and the label would land on the host, not the control. */
  readonly controlId = input.required<string>();
}
