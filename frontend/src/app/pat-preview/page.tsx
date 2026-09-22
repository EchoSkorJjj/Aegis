"use client";
// TEMPORARY preview. Delete before committing.
import { Info } from "lucide-react";
import { Input } from "@/components/ui/input";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
export default function PatPreview() {
  return (
    <div className="mx-auto mt-32 w-80 p-6">
      <div className="space-y-1">
        <div className="flex items-center gap-1">
          <label className="text-xs font-medium text-muted-foreground">Access token (private repos)</label>
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger render={<button type="button" aria-label="How to create a read-only access token" className="inline-flex text-muted-foreground hover:text-foreground"><Info className="size-3.5" /></button>} />
              <TooltipContent side="top" align="start" className="max-w-sm">
                <div className="space-y-1.5 text-left">
                  <p className="font-medium">Create a read-only token for a private repo</p>
                  <p><span className="font-medium">GitHub</span> — Settings → Developer settings → Personal access tokens. Fine-grained: set <em>Resource owner</em> to the repo&apos;s owner, select the repo, grant <em>Contents: Read</em>. Or a classic token with the <code className="rounded bg-background/15 px-1 font-mono">repo</code> scope.</p>
                  <p><span className="font-medium">GitLab</span> — Settings → Access tokens → scope <code className="rounded bg-background/15 px-1 font-mono">read_repository</code>.</p>
                  <p><span className="font-medium">Bitbucket</span> — Repo/Workspace settings → Access tokens → <em>Repositories: Read</em>.</p>
                  <p className="opacity-80">Paste it below — used only for this clone, never stored.</p>
                </div>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
        </div>
        <Input type="password" placeholder="Only for a private repository" />
      </div>
    </div>
  );
}
