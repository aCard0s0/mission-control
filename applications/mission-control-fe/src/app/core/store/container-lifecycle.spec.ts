import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { HERMES_BASELINE } from '../container-resources';
import { NO_HOST_ACCESS } from '../host-access';
import { apiContainer, liveError, liveNotice, testSlices } from '../../testing/store';

/** The three slices a lifecycle action touches, sharing one stubbed backend. */
const loaded = async (containersApi: Record<string, unknown>, images: Record<string, unknown> = {}) => {
  const slices = testSlices({
    containers: { list: vi.fn().mockResolvedValue([apiContainer()]), ...containersApi, ...images },
  });
  await slices.containers.refresh();
  slices.containers.select('c-1');
  return slices;
};

describe('ContainerLifecycle deploy', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('resolves only once the refreshed inventory holds the new container', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([apiContainer(), apiContainer({ id: 'c-2', name: 'hermes-lab' })]);
    const { lifecycle, containers } = await loaded({
      list, deploy: vi.fn().mockResolvedValue({ id: 'c-2' }),
    });

    const deployed = lifecycle.deploy('hermes-lab', 'v2026.8.3', ['ops'], 'dh-local');
    await vi.advanceTimersByTimeAsync(600);

    expect(await deployed).toBe('c-2');
    expect(containers.byId('c-2')).not.toBeNull();
    expect(containers.selectedContainerId()).toBe('c-2');
  });

  it('defaults to the local daemon, and to what Hermes recommends, when neither is named',
    async () => {
      const deploy = vi.fn().mockResolvedValue({ id: 'c-2' });
      const { lifecycle } = await loaded({ deploy });

      const deployed = lifecycle.deploy('hermes-lab', 'v1', []);
      await vi.advanceTimersByTimeAsync(600);
      await deployed;

      // a caller that says nothing about size gets the recommendation, never no limit
      expect(deploy).toHaveBeenCalledWith('dh-local', 'hermes-lab', 'v1', [], HERMES_BASELINE, NO_HOST_ACCESS, null);
    });

  it('sends a raised ceiling through to the backend', async () => {
    const deploy = vi.fn().mockResolvedValue({ id: 'c-2' });
    const { lifecycle } = await loaded({ deploy });

    const deployed = lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local', { memoryMb: 8192, cpus: 4 });
    await vi.advanceTimersByTimeAsync(600);
    await deployed;

    expect(deploy).toHaveBeenCalledWith(
      'dh-local', 'hermes-lab', 'v1', [], { memoryMb: 8192, cpus: 4 }, NO_HOST_ACCESS, null);
  });

  it('answers an empty id and says why a deploy failed', async () => {
    const { lifecycle, ctx } = await loaded({
      deploy: vi.fn().mockRejectedValue(new Error('name already in use')),
    });

    expect(await lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local')).toBe('');
    expect(liveError(ctx)).toBe('deploy failed: name already in use');
  });

  it('confirms a deploy that worked — success used to say nothing at all', async () => {
    const { lifecycle, ctx } = await loaded({ deploy: vi.fn().mockResolvedValue({ id: 'c-2' }) });

    const deployed = lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local');
    await vi.advanceTimersByTimeAsync(600);
    await deployed;

    expect(liveNotice(ctx)).toBe('container hermes-lab deployed');
  });

  it('is visible as running for the whole deploy, including the image pull', async () => {
    const { lifecycle, activity } = await loaded({
      deploy: vi.fn().mockResolvedValue({ id: 'c-2' }),
    });

    const deployed = lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local');
    expect(activity.active().map(a => a.label)).toEqual(['deploying hermes-lab']);

    await vi.advanceTimersByTimeAsync(600);
    await deployed;

    expect(activity.active()).toEqual([]);
  });

  it('stops advertising a deploy that failed', async () => {
    const { lifecycle, activity } = await loaded({
      deploy: vi.fn().mockRejectedValue(new Error('name already in use')),
    });

    await lifecycle.deploy('hermes-lab', 'v1', [], 'dh-local');

    expect(activity.active()).toEqual([]);
  });
});

describe('ContainerLifecycle start and stop', () => {
  beforeEach(() => vi.useFakeTimers());

  afterEach(() => {
    vi.clearAllTimers();
    vi.useRealTimers();
  });

  it('asks the daemon to start, then re-reads what actually happened', async () => {
    const start = vi.fn().mockResolvedValue(undefined);
    const list = vi.fn().mockResolvedValue([apiContainer({ status: 'running' })]);
    const { lifecycle } = await loaded({ start, list });

    lifecycle.setStatus('c-1', 'running');
    await vi.advanceTimersByTimeAsync(700);

    expect(start).toHaveBeenCalledWith('dh-local', 'c-1');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('asks the daemon to stop for any state that is not running', async () => {
    const stop = vi.fn().mockResolvedValue(undefined);
    const { lifecycle } = await loaded({ stop });

    lifecycle.setStatus('c-1', 'stopped');
    await vi.advanceTimersByTimeAsync(700);

    expect(stop).toHaveBeenCalledWith('dh-local', 'c-1');
  });

  it('names the verb that failed, not a generic error', async () => {
    const { lifecycle, ctx } = await loaded({
      start: vi.fn().mockRejectedValue(new Error('port bound')),
    });

    lifecycle.setStatus('c-1', 'running');
    await vi.advanceTimersByTimeAsync(0);

    expect(liveError(ctx)).toBe('start failed: port bound');
  });

  it('says so rather than going quiet on a container it does not hold', async () => {
    const start = vi.fn();
    const { lifecycle, ctx } = await loaded({ start });

    lifecycle.setStatus('c-missing', 'running');

    expect(start).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('container is no longer available');
  });

  it('tracks the start on the shell, so leaving the page does not lose it', async () => {
    const start = vi.fn().mockResolvedValue(undefined);
    const { lifecycle, activity, ctx } = await loaded({ start });

    lifecycle.setStatus('c-1', 'running');
    expect(activity.active().map(a => a.label)).toEqual(['starting hermes-prod']);

    await vi.advanceTimersByTimeAsync(700);

    expect(activity.active()).toEqual([]);
    expect(liveNotice(ctx)).toBe('hermes-prod started');
  });

  it('names a stop in the words a stop deserves', async () => {
    const { lifecycle, activity, ctx } = await loaded({
      stop: vi.fn().mockResolvedValue(undefined),
    });

    lifecycle.setStatus('c-1', 'stopped');
    expect(activity.active().map(a => a.label)).toEqual(['stopping hermes-prod']);

    await vi.advanceTimersByTimeAsync(700);

    expect(liveNotice(ctx)).toBe('hermes-prod stopped');
  });

  it('clears the entry when the daemon refuses, rather than showing it forever', async () => {
    const { lifecycle, activity } = await loaded({
      start: vi.fn().mockRejectedValue(new Error('port bound')),
    });

    lifecycle.setStatus('c-1', 'running');
    await vi.advanceTimersByTimeAsync(700);

    expect(activity.active()).toEqual([]);
  });
});

describe('ContainerLifecycle update', () => {
  it('follows the selection onto the replacement, whose id is new', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([apiContainer({ id: 'c-new', version: 'v2026.8.4' })]);
    const { lifecycle, containers } = await loaded({
      list, update: vi.fn().mockResolvedValue({ id: 'c-new' }),
      imageTags: vi.fn().mockResolvedValue({ repository: 'r', tags: [] }),
    });

    expect(await lifecycle.update('c-1', 'v2026.8.4')).toBe('c-new');
    expect(containers.selectedContainerId()).toBe('c-new');
  });

  it('leaves the selection alone when the updated container was not the selected one', async () => {
    const list = vi.fn().mockResolvedValue([
      apiContainer(), apiContainer({ id: 'c-2', name: 'hermes-lab' })]);
    const { lifecycle, containers } = await loaded({
      list, update: vi.fn().mockResolvedValue({ id: 'c-2-new' }),
      imageTags: vi.fn().mockResolvedValue({ repository: 'r', tags: [] }),
    });
    await containers.refresh();

    await lifecycle.update('c-2', 'v2026.8.4');

    expect(containers.selectedContainerId()).toBe('c-1');
  });

  it('refuses an update that would be a no-op, quietly — nothing failed', async () => {
    const update = vi.fn();
    const { lifecycle, ctx } = await loaded({ update });

    expect(await lifecycle.update('c-1', 'v2026.8.3')).toBe('');   // already on this tag
    expect(await lifecycle.update('c-1', '')).toBe('');
    expect(update).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBeNull();
  });

  it('recreates on the tag it already runs once host access comes with it', async () => {
    // the recreate is the point then: a port, variable or mount is create-time, and an
    // update onto the same image is the one way to add one to an existing Agent
    const update = vi.fn().mockResolvedValue({ id: 'c-new' });
    const list = vi.fn().mockResolvedValue([apiContainer()]);
    const { lifecycle } = await loaded({
      list, update, imageTags: vi.fn().mockResolvedValue({ repository: 'r', tags: [] }),
    });
    const access = { ports: [{ containerPort: 8644, hostPort: 8644, hostIp: '127.0.0.1' }], env: [], mounts: [] };

    expect(await lifecycle.update('c-1', 'v2026.8.3', access)).toBe('c-new');
    expect(update).toHaveBeenCalledWith('dh-local', 'c-1', 'v2026.8.3', access);
  });

  it('says so when the container to update is no longer there', async () => {
    const update = vi.fn();
    const { lifecycle, ctx } = await loaded({ update });

    expect(await lifecycle.update('c-missing', 'v2026.8.4')).toBe('');
    expect(update).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('container is no longer available');
  });

  it('re-reads the inventory after a failed update, which may have half landed', async () => {
    const list = vi.fn().mockResolvedValue([apiContainer()]);
    const { lifecycle, ctx } = await loaded({
      list, update: vi.fn().mockRejectedValue(new Error('pull timed out')),
    });

    expect(await lifecycle.update('c-1', 'v2026.8.4')).toBe('');
    expect(liveError(ctx)).toBe('update failed: pull timed out');
    expect(list).toHaveBeenCalledTimes(2);
  });
});

describe('ContainerLifecycle remove', () => {
  it('clears the selection when the removed container was the selected one', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([]);
    const { lifecycle, containers } = await loaded({
      list, remove: vi.fn().mockResolvedValue(undefined),
    });

    expect(await lifecycle.remove('c-1')).toBe(true);
    expect(containers.selectedContainerId()).toBe('');
  });

  it('tells the caches keyed to the removed container that it is gone', async () => {
    const list = vi.fn()
      .mockResolvedValueOnce([apiContainer()])
      .mockResolvedValue([]);
    const { lifecycle, containers } = await loaded({
      list, remove: vi.fn().mockResolvedValue(undefined),
    });
    const listener = vi.fn();
    containers.onSelect(listener);

    await lifecycle.remove('c-1');

    // this cleared the signal directly, so the jobs, logs and webhooks of a container the
    // operator had just deleted stayed on screen until each next poll
    expect(listener).toHaveBeenCalledWith('');
  });

  it('re-reads after a failure, because the delete may have got past the container', async () => {
    const list = vi.fn().mockResolvedValue([apiContainer()]);
    const { lifecycle, ctx, containers } = await loaded({
      list, remove: vi.fn().mockRejectedValue(new Error('volume busy')),
    });

    expect(await lifecycle.remove('c-1')).toBe(false);
    expect(containers.byId('c-1')).not.toBeNull();
    expect(liveError(ctx)).toBe('remove failed: volume busy');
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('says so rather than going quiet on a container it does not hold', async () => {
    const remove = vi.fn();
    const { lifecycle, ctx } = await loaded({ remove });

    expect(await lifecycle.remove('c-missing')).toBe(false);
    expect(remove).not.toHaveBeenCalled();
    expect(liveError(ctx)).toBe('container is no longer available');
  });
});
