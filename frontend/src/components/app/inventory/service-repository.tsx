"use client";

import { type FormEvent, type RefObject, useId, useRef, useState } from "react";
import { ChevronRight, ExternalLink, GitBranch, Info, Loader2 } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { ApiError, inventoryApi } from "@/lib/api";
import type { BusinessService, SbomImportResult, SbomImportSource } from "@/lib/types";

type Phase = "idle" | "linking" | "importing" | "unlinking";

type Draft = { url: string; branch: string; token: string; username: string };

/** Where a submit stopped: a failed link changed nothing; a failed import leaves the repository linked. */
type Failure = { stage: "link" | "import"; message: string };

const SOURCE_LABEL: Record<SbomImportSource, string> = {
  PUBLIC: "public repository",
  INTEGRATION: "through the Aegis GitHub App",
  TOKEN: "with an access token",
  UPLOAD: "from an uploaded file",
};

function importedSummary(result: SbomImportResult): string {
  return (
    `SBOM imported — ${result.componentsCreated} new, ${result.componentsMatched} matched, ` +
    `${result.serviceComponentsLinked} linked, ` +
    (result.serviceComponentsUnlinked
      ? `${result.serviceComponentsUnlinked} unlinked (no longer in the repository), `
      : "") +
    (result.dependenciesRemoved
      ? `${result.dependenciesCreated} dependencies added, ${result.dependenciesRemoved} removed`
      : `${result.dependenciesCreated} dependencies`) +
    (result.componentsSkipped ? `, ${result.componentsSkipped} skipped (no version)` : "") +
    "."
  );
}

function message(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback;
}

function formatWhen(iso: string): string {
  const date = new Date(iso);
  return Number.isNaN(date.getTime())
    ? iso
    : date.toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

/**
 * The branch in a link copied from the browser, to pre-fill the branch field: GitHub `…/tree/<branch>`,
 * GitLab `…/-/tree/<branch>`, Bitbucket `…/src/<branch>`. Only a one-segment name: a longer path could be a
 * folder, so that is left for the person to type (the server reduces the link to the repository either way).
 */
export function branchFromUrl(url: string): string | null {
  const match = url.trim().match(/\/(?:-\/tree|tree|src)\/([^/?#]+)\/?(?:[?#].*)?$/);
  if (!match) return null;
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return null; // a malformed escape: leave the branch for the person to type
  }
}

/**
 * A service's repository and the SBOM imported from it. Linking a repository imports its SBOM straight
 * away. Afterwards one button, "Import SBOM", opens one dialog — the saved URL and branch filled in, to
 * import again or to change them first — so importing, re-linking and unlinking all happen in one place.
 * The dialog asks for an access token up front when the last import needed one, and any failure lands in
 * it with the reason and the token field open, rather than the import failing first and asking afterwards.
 */
export function ServiceRepository({
  service,
  canCompose,
  onChange,
  onImported,
}: {
  service: BusinessService;
  canCompose: boolean;
  onChange: (service: BusinessService) => void;
  onImported: () => void;
}) {
  const [phase, setPhase] = useState<Phase>("idle");
  // Kept while the dialog closes so its content doesn't reset mid-animation; `key` renews it per opening.
  const [dialog, setDialog] = useState<{ open: boolean; key: number; error: string | null }>({
    open: false,
    key: 0,
    error: null,
  });
  const initialFocusRef = useRef<HTMLElement | null>(null);
  const busy = phase !== "idle";

  function openDialog(error: string | null = null) {
    setDialog((current) => ({ open: true, key: current.key + 1, error }));
  }

  function closeDialog() {
    setDialog((current) => ({ ...current, open: false }));
  }

  /** Link first when asked to, then import. Resolves to what went wrong, or null once imported. */
  async function linkAndImport(draft: Draft, relink: boolean): Promise<Failure | null> {
    if (relink) {
      setPhase("linking");
      try {
        onChange(
          await inventoryApi.linkServiceRepository(service.id, draft.url.trim(), draft.branch.trim() || undefined),
        );
      } catch (error) {
        setPhase("idle");
        return { stage: "link", message: message(error, "Could not link the repository.") };
      }
    }
    setPhase("importing");
    try {
      const result = await inventoryApi.importSbomFromRepository(service.id, {
        token: draft.token,
        username: draft.username,
      });
      toast.success(importedSummary(result));
      onImported();
      try {
        onChange(await inventoryApi.getService(service.id)); // it now records when and how this import ran
      } catch {
        // The import stands; the status line catches up on the next load.
      }
      return null;
    } catch (error) {
      return { stage: "import", message: message(error, "The SBOM import failed. Please try again.") };
    } finally {
      setPhase("idle");
    }
  }

  /** The first link, from the inline form. A link error stays on the form; an import error opens the dialog. */
  async function linkFromForm(draft: Draft): Promise<string | null> {
    const failure = await linkAndImport(draft, true);
    if (failure?.stage === "link") return failure.message;
    if (failure) openDialog(failure.message);
    return null;
  }

  /** The dialog: re-link only if the URL or branch changed, then import. Errors stay in the dialog. */
  async function importFromDialog(draft: Draft): Promise<string | null> {
    const relink =
      draft.url.trim() !== service.repositoryUrl || (draft.branch.trim() || null) !== service.repositoryBranch;
    const failure = await linkAndImport(draft, relink);
    if (failure) return failure.message;
    closeDialog();
    return null;
  }

  async function unlink(): Promise<string | null> {
    setPhase("unlinking");
    try {
      await inventoryApi.unlinkServiceRepository(service.id);
      closeDialog();
      onChange({ ...service, repositoryUrl: null, repositoryBranch: null, sbomImportedAt: null, sbomImportSource: null });
      toast.success("Repository unlinked. Components already imported stay linked to the service.");
      return null;
    } catch (error) {
      return message(error, "Could not unlink the repository.");
    } finally {
      setPhase("idle");
    }
  }

  if (!service.repositoryUrl) {
    return (
      <section aria-label="Service repository" className="space-y-3 rounded-lg border p-4">
        <div className="space-y-1">
          <h2 className="text-sm font-medium">Repository</h2>
          <p className="text-xs text-muted-foreground">
            {canCompose
              ? "Link the service's repository to import its SBOM. Aegis shallow-clones it and catalogues its " +
                "dependencies with syft — statically; your code never runs. GitHub, GitLab, Bitbucket, or another " +
                "Git host your administrator allows."
              : "No repository linked."}
          </p>
        </div>
        {canCompose && <LinkForm phase={phase} onSubmit={linkFromForm} />}
      </section>
    );
  }

  return (
    <section aria-label="Service repository" className="space-y-2 rounded-lg border p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 space-y-1">
          <h2 className="text-sm font-medium">Repository</h2>
          <a
            href={service.repositoryUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex max-w-full items-center gap-1 break-all text-sm underline underline-offset-4"
          >
            {service.repositoryUrl}
            <ExternalLink className="size-3.5 shrink-0" />
          </a>
          <p className="flex items-center gap-1 text-xs text-muted-foreground">
            <GitBranch className="size-3.5" />
            {service.repositoryBranch ?? "Repository default branch"}
          </p>
        </div>
        {canCompose && (
          <Button disabled={busy} onClick={() => openDialog()}>
            Import SBOM
          </Button>
        )}
      </div>
      <ImportStatus service={service} importing={phase === "importing"} />

      {canCompose && (
        <Dialog
          open={dialog.open}
          onOpenChange={(open) => {
            if (!open && !busy) closeDialog();
          }}
          disablePointerDismissal={busy}
        >
          <DialogContent initialFocus={initialFocusRef} showCloseButton={!busy}>
            <ImportForm
              key={dialog.key}
              service={service}
              phase={phase}
              initialError={dialog.error}
              initialFocusRef={initialFocusRef}
              onSubmit={importFromDialog}
              onUnlink={unlink}
              onCancel={closeDialog}
            />
          </DialogContent>
        </Dialog>
      )}
    </section>
  );
}

/** When and how the SBOM was last imported — or that an import is running now. */
function ImportStatus({ service, importing }: { service: BusinessService; importing: boolean }) {
  if (importing) {
    return (
      <p role="status" className="flex items-center gap-1.5 text-xs text-muted-foreground">
        <Loader2 className="size-3.5 animate-spin" />
        Importing the SBOM — cloning and scanning the repository…
      </p>
    );
  }
  if (!service.sbomImportedAt || !service.sbomImportSource) {
    return <p className="text-xs text-muted-foreground">No SBOM imported from this repository yet.</p>;
  }
  return (
    <p className="text-xs text-muted-foreground">
      SBOM last imported {formatWhen(service.sbomImportedAt)} · {SOURCE_LABEL[service.sbomImportSource]}
    </p>
  );
}

/** The first link: inline, since it is the one thing to do on a service with no repository. */
function LinkForm({ phase, onSubmit }: { phase: Phase; onSubmit: (draft: Draft) => Promise<string | null> }) {
  const [draft, setDraft] = useState<Draft>({ url: "", branch: "", token: "", username: "" });
  const [accessOpen, setAccessOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const busy = phase !== "idle";

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !draft.url.trim()) return;
    setError(null);
    setError(await onSubmit(draft));
  }

  return (
    <form onSubmit={submit} className="space-y-3">
      <RepositoryFields draft={draft} onChange={setDraft} disabled={busy} />
      <AccessFields
        draft={draft}
        onChange={setDraft}
        disabled={busy}
        open={accessOpen}
        onOpen={() => setAccessOpen(true)}
      />
      {error && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-xs text-destructive">
          {error}
        </p>
      )}
      <Button type="submit" disabled={busy || !draft.url.trim()}>
        {phase === "linking" ? (
          <>
            <Loader2 className="animate-spin" />
            Linking…
          </>
        ) : phase === "importing" ? (
          <>
            <Loader2 className="animate-spin" />
            Importing SBOM…
          </>
        ) : (
          "Link & import SBOM"
        )}
      </Button>
    </form>
  );
}

/** The dialog's form: import from the saved repository, or change it first; unlink from here too. */
function ImportForm({
  service,
  phase,
  initialError,
  initialFocusRef,
  onSubmit,
  onUnlink,
  onCancel,
}: {
  service: BusinessService;
  phase: Phase;
  initialError: string | null;
  initialFocusRef: RefObject<HTMLElement | null>;
  onSubmit: (draft: Draft) => Promise<string | null>;
  onUnlink: () => Promise<string | null>;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<Draft>({
    url: service.repositoryUrl ?? "",
    branch: service.repositoryBranch ?? "",
    token: "",
    username: "",
  });
  const neededToken = service.sbomImportSource === "TOKEN";
  const [accessOpen, setAccessOpen] = useState(neededToken || initialError !== null);
  const [error, setError] = useState(initialError);
  const busy = phase !== "idle";
  const changed =
    draft.url.trim() !== service.repositoryUrl || (draft.branch.trim() || null) !== service.repositoryBranch;
  // Focus what the person most likely needs: the token when one is expected, otherwise the import button.
  const focusToken = accessOpen;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || !draft.url.trim()) return;
    setError(null);
    const failure = await onSubmit(draft);
    if (failure) {
      setError(failure);
      setAccessOpen(true);
    }
  }

  async function unlink() {
    setError(null);
    setError(await onUnlink());
  }

  return (
    <form onSubmit={submit} className="grid gap-4">
      <DialogHeader>
        <DialogTitle>Import SBOM</DialogTitle>
        <DialogDescription>
          Aegis clones the repository and catalogues its dependencies with syft — statically; your code never
          runs. Change the URL or branch to import from a different one.
        </DialogDescription>
      </DialogHeader>
      {error && (
        <p role="alert" className="rounded-md bg-destructive/10 px-3 py-2 text-xs text-destructive">
          {error}
        </p>
      )}
      <RepositoryFields draft={draft} onChange={setDraft} disabled={busy} stacked />
      <AccessFields
        draft={draft}
        onChange={setDraft}
        disabled={busy}
        open={accessOpen}
        onOpen={() => setAccessOpen(true)}
        hint={neededToken ? "This repository needed an access token last time." : undefined}
        tokenRef={
          focusToken
            ? (node) => {
                initialFocusRef.current = node;
              }
            : undefined
        }
      />
      {phase === "importing" && (
        <p role="status" className="text-xs text-muted-foreground">
          Cloning and scanning the repository — this can take a minute for a large one.
        </p>
      )}
      <DialogFooter className="sm:justify-between">
        <Button
          type="button"
          variant="ghost"
          className="text-destructive hover:text-destructive"
          disabled={busy}
          onClick={() => void unlink()}
        >
          {phase === "unlinking" ? "Unlinking…" : "Unlink repository"}
        </Button>
        <div className="flex flex-col-reverse gap-2 sm:flex-row">
          <Button type="button" variant="outline" disabled={busy} onClick={onCancel}>
            Cancel
          </Button>
          <Button
            type="submit"
            disabled={busy || !draft.url.trim()}
            ref={
              focusToken
                ? undefined
                : (node: HTMLButtonElement | null) => {
                    initialFocusRef.current = node;
                  }
            }
          >
            {phase === "linking" ? (
              <>
                <Loader2 className="animate-spin" />
                Linking…
              </>
            ) : phase === "importing" ? (
              <>
                <Loader2 className="animate-spin" />
                Importing SBOM…
              </>
            ) : changed ? (
              "Save & import SBOM"
            ) : (
              "Import SBOM"
            )}
          </Button>
        </div>
      </DialogFooter>
    </form>
  );
}

/** Repository URL and branch; a pasted branch link fills in the branch until the person types one. */
function RepositoryFields({
  draft,
  onChange,
  disabled,
  stacked = false,
}: {
  draft: Draft;
  onChange: (draft: Draft) => void;
  disabled: boolean;
  /** One field per row, for a narrow container such as the dialog. */
  stacked?: boolean;
}) {
  const id = useId();
  const [branchEdited, setBranchEdited] = useState(draft.branch !== "");

  return (
    <div className="space-y-1">
      <div className={stacked ? "grid gap-3" : "grid items-end gap-3 sm:grid-cols-[1fr_12rem]"}>
        <div className="space-y-1">
          <label htmlFor={`${id}-url`} className="text-xs font-medium">
            Repository URL
          </label>
          <Input
            id={`${id}-url`}
            required
            maxLength={400}
            placeholder="https://github.com/owner/repo"
            value={draft.url}
            disabled={disabled}
            onChange={(event) => {
              const url = event.target.value;
              onChange({ ...draft, url, branch: branchEdited ? draft.branch : (branchFromUrl(url) ?? "") });
            }}
          />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${id}-branch`} className="text-xs font-medium">
            Branch (optional)
          </label>
          <Input
            id={`${id}-branch`}
            maxLength={200}
            placeholder="Default branch"
            value={draft.branch}
            disabled={disabled}
            onChange={(event) => {
              setBranchEdited(event.target.value !== "");
              onChange({ ...draft, branch: event.target.value });
            }}
          />
        </div>
      </div>
      <p className="text-[11px] text-muted-foreground">
        Paste it from the address bar or the clone button — an SSH clone URL or a link to a branch or file works too.
      </p>
    </div>
  );
}

/** A read-only token (and, rarely, the username a host wants with it), folded away until needed. */
function AccessFields({
  draft,
  onChange,
  disabled,
  open,
  onOpen,
  hint,
  tokenRef,
}: {
  draft: Draft;
  onChange: (draft: Draft) => void;
  disabled: boolean;
  open: boolean;
  onOpen: () => void;
  hint?: string;
  tokenRef?: (node: HTMLInputElement | null) => void;
}) {
  const id = useId();

  if (!open) {
    return (
      <div>
        <button
          type="button"
          onClick={onOpen}
          className="inline-flex items-center gap-1 text-xs font-medium text-muted-foreground hover:text-foreground"
        >
          <ChevronRight className="size-3.5" />
          Private repository? Add an access token
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {hint && <p className="text-xs font-medium">{hint}</p>}
      <div className="grid items-end gap-3 sm:grid-cols-[1fr_10rem]">
        <div className="space-y-1">
          <div className="flex items-center gap-1">
            <label htmlFor={`${id}-token`} className="text-xs font-medium">
              Access token
            </label>
            <TokenHelp />
          </div>
          <Input
            id={`${id}-token`}
            ref={tokenRef}
            type="password"
            autoComplete="off"
            maxLength={500}
            placeholder="Read-only token"
            value={draft.token}
            disabled={disabled}
            onChange={(event) => onChange({ ...draft, token: event.target.value })}
          />
        </div>
        <div className="space-y-1">
          <label htmlFor={`${id}-username`} className="text-xs font-medium">
            Username (optional)
          </label>
          <Input
            id={`${id}-username`}
            autoComplete="off"
            maxLength={100}
            placeholder="Set for you"
            value={draft.username}
            disabled={disabled}
            onChange={(event) => onChange({ ...draft, username: event.target.value })}
          />
        </div>
      </div>
      <p className="text-[11px] text-muted-foreground">
        Not needed for a public repository, or a GitHub repository with the Aegis GitHub App installed. Used for
        this import only — never stored.
      </p>
    </div>
  );
}

function TokenHelp() {
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger
          render={
            <button
              type="button"
              aria-label="How to create a read-only access token"
              className="inline-flex text-muted-foreground hover:text-foreground"
            >
              <Info className="size-3.5" />
            </button>
          }
        />
        <TooltipContent side="top" align="start" className="max-w-sm">
          <div className="space-y-1.5 text-left">
            <p className="font-medium">A read-only token for a private repository</p>
            <p>
              <span className="font-medium">GitHub</span> — a fine-grained personal access token with{" "}
              <em>Contents: Read</em> on the repository. No token at all if the Aegis GitHub App is installed on it.
            </p>
            <p>
              <span className="font-medium">GitLab</span> — a project, group or personal access token with{" "}
              <code className="rounded bg-background/15 px-1 font-mono">read_repository</code>.
            </p>
            <p>
              <span className="font-medium">Bitbucket</span> — a repository access token with{" "}
              <em>Repositories: Read</em>, or an API token with{" "}
              <code className="rounded bg-background/15 px-1 font-mono">read:repository:bitbucket</code>.
            </p>
            <p>
              <span className="font-medium">Other Git hosts</span> — a read-only token; add your username if the
              host expects it with the token.
            </p>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}
