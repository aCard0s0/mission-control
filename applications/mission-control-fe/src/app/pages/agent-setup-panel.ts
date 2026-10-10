import {
  ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AgentSetupStore } from '../core/store/agent-setup-store';
import { CredentialStore } from '../core/store/credential-store';
import { TerminalRequestStore } from '../core/store/terminal-request-store';
import { ApiCodexLogin } from '../core/api/api-types';
import { AgentProfile, Credential } from '../core/models';
import { StatusDot } from '../shared/status-dot';

/**
 * The profile's Setup tab: what its `.env` holds, which auth providers are
 * logged in, and which messaging platforms are wired up.
 *
 * The setup itself is cached in the store, because reading it runs
 * `hermes status` inside the container and takes seconds — so re-entering the
 * tab renders the last answer immediately, and only an explicit refresh (or a
 * write, which answers with the new state) goes back to the container.
 *
 * The two editable panels also offer the credential library: any row whose variable a saved
 * credential holds gets a picker beside its box. Choosing one posts the credential's *id* and
 * the server resolves it, so this component never handles key material — which is also why
 * the picker fills every row that credential covers at once rather than making an operator
 * pick a messaging platform's token and home channel separately.
 */
@Component({
  selector: 'mc-agent-setup-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, StatusDot],
  templateUrl: './agent-setup-panel.html',
  styleUrl: './agent-setup-panel.scss',
})
export class AgentSetupPanel {
  readonly agent = input.required<AgentProfile>();

  protected readonly setup = inject(AgentSetupStore);
  protected readonly terminal = inject(TerminalRequestStore);
  private readonly credentials = inject(CredentialStore);

  protected readonly profileSetup = computed(() => this.setup.setupOf(this.agent().id));
  protected readonly setupLoading = computed(() => this.setup.isSetupLoading(this.agent().id));

  /** env var (or '.env' for the init call) with a write in flight. */
  protected readonly envBusy = signal<string | null>(null);
  /** tokenVar of the expanded messaging row. */
  protected readonly msgOpen = signal<string | null>(null);
  /** env var → the credential picked for it, or '' for none. A signal, unlike
   *  {@link envDrafts}: the picker writes it from a `(change)` handler, not `ngModel`. */
  protected readonly envPicks = signal<Record<string, string>>({});
  protected envDrafts: Record<string, string> = {};
  /** The ChatGPT device login this tab started, while it is worth showing. */
  protected readonly codex = signal<ApiCodexLogin | null>(null);
  protected readonly codexBusy = signal(false);

  constructor() {
    // read on open, and whenever the tab is showing a different profile; a
    // cached setup answers without touching the container
    effect(() => {
      const id = this.agent().id;
      untracked(() => {
        this.msgOpen.set(null);
        this.envDrafts = {};
        this.envPicks.set({});
        this.codex.set(null);
        void this.setup.setup(id);
      });
    });
    // while a login waits on the operator's browser, read it back until hermes settles it;
    // each read is one `cat` in the container, not a `hermes status`
    effect(onCleanup => {
      if (this.codex()?.state !== 'pending') return;
      const id = this.agent().id;
      const timer = setInterval(() => void this.pollCodexLogin(id), 3_000);
      onCleanup(() => clearInterval(timer));
    });
  }

  protected startCodexLogin(): void {
    this.codexBusy.set(true);
    this.setup.startCodexLogin(this.agent().id)
      .then(login => this.codex.set(login))
      .finally(() => this.codexBusy.set(false));
  }

  private async pollCodexLogin(id: string): Promise<void> {
    const login = await this.setup.codexLogin(id);
    if (!login || login.state === 'pending' || id !== this.agent().id) return;
    this.codex.set(login);
    if (login.state === 'succeeded') this.refresh();
  }

  protected refresh(): void {
    void this.setup.setup(this.agent().id, true);
  }

  protected initEnv(): void {
    this.envBusy.set('.env');
    this.setup.initEnv(this.agent().id)
      .catch(() => null)
      .finally(() => this.envBusy.set(null));
  }

  protected setEnv(key: string): void {
    const value = (this.envDrafts[key] ?? '').trim();
    if (!value) return;
    this.applyEnv(key, value);
  }

  protected clearEnv(key: string): void {
    this.envPicks.update(picks => ({ ...picks, [key]: '' }));
    this.applyEnv(key, null);
  }

  // ── the credential picker ─────────────────────────────────────────────────

  /** Saved credentials holding a variable named `envVar`. Empty hides the picker entirely, so
   *  the feature is invisible until the library has something to offer. */
  protected credentialsFor(envVar: string): Credential[] {
    return this.credentials.providing(envVar);
  }

  /** The credential picked for a variable, or '' for none. */
  protected pickedFor(envVar: string): string {
    return this.envPicks()[envVar] ?? '';
  }

  /**
   * Records a pick and pre-fills every other variable the same credential covers.
   *
   * A credential is a bundle, so picking `TELEGRAM_BOT_TOKEN` from "Telegram ops" is also the
   * answer for `TELEGRAM_HOME_CHANNEL`. Filling the siblings is a marker only — each row is
   * still applied by its own button, and each carries the id rather than a value.
   */
  protected pickCredential(envVar: string, credentialId: string): void {
    if (!credentialId) {
      this.envPicks.update(picks => ({ ...picks, [envVar]: '' }));
      return;
    }
    const picked = this.credentials.credentials().find(c => c.id === credentialId);
    const covers = picked ? picked.entries.map(e => e.key) : [envVar];
    this.envPicks.update(picks => {
      const next = { ...picks };
      for (const key of covers) {
        // never overwrite a different credential the operator chose deliberately
        if (!next[key] || key === envVar) next[key] = credentialId;
      }
      return next;
    });
  }

  /** Applies the picked credential's value to one variable. Blank drafts and picks cannot both
   *  be live: the button is only offered while a pick stands. */
  protected applyPick(key: string): void {
    const credentialId = this.pickedFor(key);
    if (!credentialId) return;
    this.envBusy.set(key);
    this.setup.setEnv(this.agent().id, [{ key, value: null, credentialId }])
      .catch(() => null)
      .then(saved => {
        if (saved) {
          delete this.envDrafts[key];
          this.envPicks.update(picks => ({ ...picks, [key]: '' }));
        }
      })
      .finally(() => this.envBusy.set(null));
  }

  protected toggleMsg(tokenVar: string): void {
    this.msgOpen.update(v => v === tokenVar ? null : tokenVar);
  }

  /** An empty value removes the key; the store caches whatever comes back. */
  private applyEnv(key: string, value: string | null): void {
    this.envBusy.set(key);
    this.setup.setEnv(this.agent().id, [{ key, value }])
      .catch(() => null)
      .then(saved => { if (saved) delete this.envDrafts[key]; })
      .finally(() => this.envBusy.set(null));
  }
}
