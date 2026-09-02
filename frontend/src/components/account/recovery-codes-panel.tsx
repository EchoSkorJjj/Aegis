"use client";

import { toast } from "sonner";

import { Button } from "@/components/ui/button";

/**
 * Shows a freshly-issued batch of single-use recovery codes, once. Used after both authenticator
 * and passkey enrollment (and on regeneration), so the wording is factor-agnostic.
 */
export function RecoveryCodesPanel({
  codes,
  onDone,
}: {
  codes: string[];
  onDone: () => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">
        Save these backup codes somewhere safe — each works once if you can’t use your usual
        sign-in method. They won’t be shown again.
      </p>
      <div className="grid grid-cols-2 gap-2 rounded-lg border p-4 font-mono text-sm">
        {codes.map((c) => (
          <span key={c}>{c}</span>
        ))}
      </div>
      <div className="flex gap-2">
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            navigator.clipboard?.writeText(codes.join("\n"));
            toast.success("Recovery codes copied");
          }}
        >
          Copy
        </Button>
        <Button size="sm" onClick={onDone}>
          Done
        </Button>
      </div>
    </div>
  );
}
