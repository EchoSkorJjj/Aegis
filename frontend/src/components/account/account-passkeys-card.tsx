"use client";

import { useEffect, useState } from "react";
import { toast } from "sonner";

import { ApiError, authApi, mfaApi } from "@/lib/api";
import { RecoveryCodesPanel } from "@/components/account/recovery-codes-panel";
import { enrollPasskey, passkeysSupported } from "@/lib/webauthn";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import type { AuthConfig, Passkey } from "@/lib/types";

export function AccountPasskeysCard() {
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [passkeys, setPasskeys] = useState<Passkey[]>([]);
  const [loading, setLoading] = useState(true);
  const [adding, setAdding] = useState(false);
  const [label, setLabel] = useState("");
  const [busy, setBusy] = useState(false);
  const [removingId, setRemovingId] = useState<string | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);

  useEffect(() => {
    Promise.all([authApi.config(), mfaApi.webauthnList()])
      .then(([c, list]) => {
        setConfig(c);
        setPasskeys(list);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  async function refresh() {
    try {
      setPasskeys(await mfaApi.webauthnList());
    } catch {
      // Leave the current list in place on a transient error.
    }
  }

  async function addPasskey() {
    if (!passkeysSupported()) {
      toast.error("This browser does not support passkeys.");
      return;
    }
    setBusy(true);
    try {
      const name = label.trim() || "Passkey";
      const { recoveryCodes: codes } = await enrollPasskey(name);
      toast.success(`Passkey “${name}” added`);
      setAdding(false);
      setLabel("");
      await refresh();
      if (codes.length > 0) {
        setRecoveryCodes(codes); // first strong factor — show the backup codes once
      }
    } catch (error) {
      // A cancelled prompt throws a DOMException (not an ApiError); both land here.
      const message =
        error instanceof ApiError
          ? error.message || "Could not add the passkey."
          : "Passkey setup was cancelled or failed.";
      toast.error(message);
    } finally {
      setBusy(false);
    }
  }

  async function removePasskey(id: string) {
    setRemovingId(id);
    try {
      await mfaApi.webauthnDelete(id);
      toast.success("Passkey removed");
      await refresh();
    } catch (error) {
      toast.error(
        error instanceof ApiError ? error.message : "Could not remove the passkey.",
      );
    } finally {
      setRemovingId(null);
    }
  }

  if (loading) {
    return (
      <Card>
        <CardHeader>
          <Skeleton className="h-5 w-40" />
        </CardHeader>
        <CardContent>
          <Skeleton className="h-4 w-full" />
        </CardContent>
      </Card>
    );
  }

  // Hidden entirely when the deployment has not enabled the passkey factor.
  if (!config?.methods.webauthn) {
    return null;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Passkeys</CardTitle>
        <CardDescription>
          Sign in with your device’s fingerprint, face, or a security key — nothing to type.
        </CardDescription>
      </CardHeader>
      <CardContent className="gap-4">
        {recoveryCodes ? (
          <RecoveryCodesPanel
            codes={recoveryCodes}
            onDone={() => setRecoveryCodes(null)}
          />
        ) : (
          <>
            {passkeys.length > 0 && (
              <ul className="flex flex-col divide-y rounded-lg border">
                {passkeys.map((pk) => (
                  <li
                    key={pk.id}
                    className="flex items-center justify-between gap-4 p-3"
                  >
                    <div className="text-sm">
                      <div className="font-medium">{pk.label || "Passkey"}</div>
                      <div className="text-muted-foreground">
                        Added {new Date(pk.createdAt).toLocaleDateString()}
                      </div>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => removePasskey(pk.id)}
                      disabled={removingId === pk.id}
                    >
                      {removingId === pk.id ? "Removing…" : "Remove"}
                    </Button>
                  </li>
                ))}
              </ul>
            )}

            {adding ? (
              <div className="flex flex-col gap-3">
                <div className="flex flex-col gap-1.5">
                  <Label htmlFor="passkey-label">Name (optional)</Label>
                  <Input
                    id="passkey-label"
                    value={label}
                    onChange={(e) => setLabel(e.target.value)}
                    placeholder="e.g. MacBook Touch ID"
                    maxLength={100}
                    autoFocus
                  />
                </div>
                <div className="flex gap-2">
                  <Button onClick={addPasskey} disabled={busy}>
                    {busy ? "Waiting for your device…" : "Create passkey"}
                  </Button>
                  <Button
                    variant="ghost"
                    onClick={() => {
                      setAdding(false);
                      setLabel("");
                    }}
                    disabled={busy}
                  >
                    Cancel
                  </Button>
                </div>
              </div>
            ) : (
              <div className="flex items-center justify-between gap-4">
                <div className="text-sm text-muted-foreground">
                  {passkeys.length > 0
                    ? "Add another passkey (e.g. a second device)."
                    : "Add a passkey to this account as a sign-in factor."}
                </div>
                <Button variant="outline" size="sm" onClick={() => setAdding(true)}>
                  Add a passkey
                </Button>
              </div>
            )}
          </>
        )}
      </CardContent>
    </Card>
  );
}
