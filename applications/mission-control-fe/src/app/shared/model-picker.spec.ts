import { describe, expect, it } from 'vitest';
import { ModelPicker } from './model-picker';
import { ModelCatalog, ModelSource } from '../core/models';

/** A catalog answer — these specs are about the picker, not about provenance. */
const cat = (models: string[], source: ModelSource = 'catalog'): ModelCatalog => ({ models, source });

/** A promise plus the handle to settle it, so a load can be left in flight. */
const deferred = () => {
  let resolve!: (answer: ModelCatalog) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<ModelCatalog>((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};

describe('ModelPicker', () => {
  it('hands the field to free text on the empty "other…" pick, and back on the next load', async () => {
    const picker = new ModelPicker();
    await picker.load(Promise.resolve(cat(['claude-opus-5'])));

    picker.pick('');
    expect(picker.custom()).toBe(true);
    expect(picker.model).toBe('');

    picker.pick('claude-opus-5');
    expect(picker.model).toBe('claude-opus-5');

    picker.custom.set(true);
    await picker.load(Promise.resolve(cat(['claude-sonnet-5'])));
    // a new list is a new set of choices; the typed one is re-checked against it
    expect(picker.custom()).toBe(false);
  });

  it('lists what the catalog answered and selects the first of them', async () => {
    const picker = new ModelPicker();

    await picker.load(Promise.resolve(cat(['claude-opus-5', 'claude-sonnet-5'])));

    expect(picker.suggestions()).toEqual(['claude-opus-5', 'claude-sonnet-5']);
    expect(picker.model).toBe('claude-opus-5');
    expect(picker.loading()).toBe(false);
  });

  it('keeps a selection the new list still offers', async () => {
    const picker = new ModelPicker();
    picker.model = 'claude-sonnet-5';

    await picker.load(Promise.resolve(cat(['claude-opus-5', 'claude-sonnet-5'])));

    expect(picker.model).toBe('claude-sonnet-5');
  });

  it('leaves a typed model alone when the provider has no catalog', async () => {
    const picker = new ModelPicker();
    picker.model = 'some-local-build';

    await picker.load(Promise.resolve(cat([])));

    expect(picker.model).toBe('some-local-build');
    expect(picker.suggestions()).toEqual([]);
  });

  it('takes a preferred model over the first suggestion', async () => {
    const picker = new ModelPicker();

    await picker.load(Promise.resolve(cat(['a', 'b'])), { preferred: 'b' });

    expect(picker.model).toBe('b');
  });

  it('reports that it is loading until the catalog answers', async () => {
    const picker = new ModelPicker();
    const catalog = deferred();

    const load = picker.load(catalog.promise);
    expect(picker.loading()).toBe(true);

    catalog.resolve(cat(['a']));
    await load;
    expect(picker.loading()).toBe(false);
  });

  it('clears the list when a provider switch fails, so no stale models are offered', async () => {
    const picker = new ModelPicker();
    await picker.load(Promise.resolve(cat(['from-provider-a'])));

    await picker.load(Promise.reject(new Error('provider unreachable')));

    expect(picker.suggestions()).toEqual([]);
  });

  it('keeps the list when a plain refresh fails', async () => {
    const picker = new ModelPicker();
    await picker.load(Promise.resolve(cat(['a', 'b'])));

    await picker.load(Promise.reject(new Error('bad key')), { keepOnError: true });

    expect(picker.suggestions()).toEqual(['a', 'b']);
  });

  it('never lets a superseded load land on the provider that replaced it', async () => {
    const picker = new ModelPicker();
    const slow = deferred();

    const first = picker.load(slow.promise, { preferred: 'slow-model' });
    await picker.load(Promise.resolve(cat(['fast-model'])));

    slow.resolve(cat(['slow-model']));
    await first;

    expect(picker.suggestions()).toEqual(['fast-model']);
    expect(picker.model).toBe('fast-model');
  });

  it('labels where the list came from, and says nothing when there is nothing to say', async () => {
    const picker = new ModelPicker();

    await picker.load(Promise.resolve(cat(['m-1'], 'catalog')));
    expect(picker.sourceLabel()).toBe('from the provider');

    // the operator supplied the key for a live read, so the label would be telling them
    // what they just did; an empty list has no provenance worth reporting either
    await picker.load(Promise.resolve(cat(['m-1'], 'live')));
    expect(picker.sourceLabel()).toBeNull();
    await picker.load(Promise.resolve(cat([], 'catalog')));
    expect(picker.sourceLabel()).toBeNull();
  });

  it('keeps the label describing the list it kept, when a failed load keeps it', async () => {
    const picker = new ModelPicker();
    await picker.load(Promise.resolve(cat(['m-1'], 'config')));

    await picker.load(Promise.reject(new Error('bad key')), { keepOnError: true });

    expect(picker.suggestions()).toEqual(['m-1']);
    expect(picker.sourceLabel()).toBe('shipped list');
  });

  it('abandons a load in flight when it is reset', async () => {
    const picker = new ModelPicker();
    const slow = deferred();

    const load = picker.load(slow.promise);
    picker.reset();
    slow.resolve(cat(['late']));
    await load;

    expect(picker.suggestions()).toEqual([]);
    expect(picker.model).toBe('');
    expect(picker.loading()).toBe(false);
  });
});
