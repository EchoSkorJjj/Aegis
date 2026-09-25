"use client";

import { type FormEvent, useId, useState } from "react";
import { ExternalLink, GitBranch } from "lucide-react";
import { toast } from "sonner";

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { ApiError, inventoryApi } from "@/lib/api";
import type { BusinessService } from "@/lib/types";

export function ServiceRepository({
  service,
  canCompose,
  onChange,
}: {
  service: BusinessService;
  canCompose: boolean;
  onChange: (service: BusinessService) => void;
}) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [url, setUrl] = useState("");
  const [branch, setBranch] = useState("");
  const [saving, setSaving] = useState(false);

  function changeOpen(next: boolean) {
    if (saving) return;
    if (next) {
      setUrl(service.repositoryUrl ?? "");
      setBranch(service.repositoryBranch ?? "");
    }
    setOpen(next);
  }

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (saving || !canCompose) return;
    setSaving(true);
    try {
      const updated = await inventoryApi.linkServiceRepository(
        service.id, url.trim(), branch.trim() || undefined,
      );
      onChange(updated);
      setOpen(false);
      toast.success("Repository saved.");
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "Could not save the repository.");
    } finally {
      setSaving(false);
    }
  }

  async function unlink() {
    if (saving || !canCompose) return;
    setSaving(true);
    try {
      await inventoryApi.unlinkServiceRepository(service.id);
      onChange({ ...service, repositoryUrl: null, repositoryBranch: null });
      toast.success("Repository unlinked.");
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "Could not unlink the repository.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <section aria-label="Service repository" className="flex flex-wrap items-center justify-between gap-3 rounded-lg border p-4">
      <div className="min-w-0 space-y-1">
        <h2 className="text-sm font-medium">Repository</h2>
        {service.repositoryUrl ? (
          <>
            <a href={service.repositoryUrl} target="_blank" rel="noopener noreferrer"
              className="inline-flex max-w-full items-center gap-1 break-all text-sm underline underline-offset-4">
              {service.repositoryUrl}<ExternalLink className="size-3.5 shrink-0" />
            </a>
            <p className="flex items-center gap-1 text-xs text-muted-foreground">
              <GitBranch className="size-3.5" />
              {service.repositoryBranch ?? "Repository default branch"}
            </p>
          </>
        ) : (
          <p className="text-xs text-muted-foreground">No repository linked.</p>
        )}
      </div>
      {canCompose && (
        <div className="flex items-center gap-2">
          <Popover open={open} onOpenChange={changeOpen}>
            <PopoverTrigger render={<Button variant="outline" disabled={saving} />}>
              {service.repositoryUrl ? "Edit repository" : "Link repository"}
            </PopoverTrigger>
            <PopoverContent align="end" className="w-80">
              <form onSubmit={save} className="space-y-3">
                <p className="text-sm font-medium">Service repository</p>
                <p className="text-xs text-muted-foreground">
                  Save the repository and branch used for this service&apos;s SBOM imports.
                </p>
                <div className="space-y-1">
                  <label htmlFor={`${id}-url`} className="text-xs font-medium">Repository URL</label>
                  <Input id={`${id}-url`} type="url" required maxLength={400} autoFocus
                    placeholder="https://github.com/owner/repo" value={url} disabled={saving}
                    onChange={(event) => setUrl(event.target.value)} />
                </div>
                <div className="space-y-1">
                  <label htmlFor={`${id}-branch`} className="text-xs font-medium">Branch (optional)</label>
                  <Input id={`${id}-branch`} maxLength={200} placeholder="Repository default branch"
                    value={branch} disabled={saving} onChange={(event) => setBranch(event.target.value)} />
                </div>
                <Button type="submit" className="w-full" disabled={saving || !url.trim()}>
                  {saving ? "Saving…" : "Save repository"}
                </Button>
              </form>
            </PopoverContent>
          </Popover>
          {service.repositoryUrl && (
            <Button variant="ghost" disabled={saving} onClick={unlink}>Unlink repository</Button>
          )}
        </div>
      )}
    </section>
  );
}
