"use client";

import { QRCodeSVG } from "qrcode.react";
import { useEffect, useState } from "react";
import { toast } from "sonner";

import { ApiError, authApi, mfaApi } from "@/lib/api";
import { RecoveryCodesPanel } from "@/components/account/recovery-codes-panel";
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
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import type { AuthConfig, MfaStatus, TotpInit } from "@/lib/types";

export function AccountMfaCard() {
  const [config, setConfig] = useState<AuthConfig | null>(null);
  const [status, setStatus] = useState<MfaStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const [enrollment, setEnrollment] = useState<TotpInit | null>(null);
  const [disabling, setDisabling] = useState(false);
  const [regenerating, setRegenerating] = useState(false);
  const [recoveryCodes, setRecoveryCodes] = useState<string[] | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    Promise.all([authApi.config(), mfaApi.status()])
      .then(([c, s]) => {
        setConfig(c);
        setStatus(s);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  function reset() {
    setEnrollment(null);
    setDisabling(false);
    setRegenerating(false);
    setCode("");
  }

  async function startEnrollment() {
    setBusy(true);
    try {
      setEnrollment(await mfaApi.totpInit());
      setCode("");
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "Could not start setup");
    } finally {
      setBusy(false);
    }
  }

  async function confirmEnrollment() {
    setBusy(true);
    try {
      const { recoveryCodes: codes } = await mfaApi.totpConfirm(code.trim());
      toast.success("Authenticator app enabled");
      setStatus({ totpEnabled: true, recoveryCodesRemaining: codes.length });
      reset();
      setRecoveryCodes(codes);
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "That code is not valid");
    } finally {
      setBusy(false);
    }
  }

  async function confirmDisable() {
    setBusy(true);
    try {
      await mfaApi.totpDisable(code.trim());
      toast.success("Authenticator app removed");
      setStatus({ totpEnabled: false, recoveryCodesRemaining: 0 });
      reset();
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "That code is not valid");
    } finally {
      setBusy(false);
    }
  }

  async function confirmRegenerate() {
    setBusy(true);
    try {
      const { recoveryCodes: codes } = await mfaApi.regenerateRecoveryCodes(code.trim());
      toast.success("New recovery codes generated");
      setStatus((s) => (s ? { ...s, recoveryCodesRemaining: codes.length } : s));
      reset();
      setRecoveryCodes(codes);
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "That code is not valid");
    } finally {
      setBusy(false);
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

  // Hidden entirely when the deployment has not enabled the authenticator factor.
  if (!config?.methods.totp) {
    return null;
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Authenticator app</CardTitle>
        <CardDescription>
          Use a TOTP app (Google Authenticator, Microsoft Authenticator, 1Password, …) for sign-in codes.
        </CardDescription>
      </CardHeader>
      <CardContent className="gap-4">
        {recoveryCodes ? (
          <RecoveryCodesPanel codes={recoveryCodes} onDone={() => setRecoveryCodes(null)} />
        ) : status?.totpEnabled ? (
          disabling ? (
            <CodePrompt
              hint="Enter a current code to turn the authenticator off."
              action="Remove authenticator"
              variant="destructive"
              busy={busy}
              code={code}
              setCode={setCode}
              onConfirm={confirmDisable}
              onCancel={reset}
            />
          ) : regenerating ? (
            <CodePrompt
              hint="Enter a current code to generate a new set of recovery codes. This replaces the old ones."
              action="Regenerate codes"
              busy={busy}
              code={code}
              setCode={setCode}
              onConfirm={confirmRegenerate}
              onCancel={reset}
            />
          ) : (
            <>
              <div className="flex items-center justify-between gap-4">
                <div className="text-sm">
                  <div className="font-medium text-emerald-600 dark:text-emerald-400">Enabled</div>
                  <div className="text-muted-foreground">Your authenticator app is set up.</div>
                </div>
                <Button variant="outline" size="sm" onClick={() => setDisabling(true)}>
                  Remove
                </Button>
              </div>
              <Separator />
              <div className="flex items-center justify-between gap-4">
                <div className="text-sm">
                  <div className="font-medium">Recovery codes</div>
                  <div className="text-muted-foreground">
                    {status.recoveryCodesRemaining} unused — backups for when you can’t use your app.
                  </div>
                </div>
                <Button variant="outline" size="sm" onClick={() => setRegenerating(true)}>
                  Regenerate
                </Button>
              </div>
            </>
          )
        ) : enrollment ? (
          <div className="flex flex-col gap-4">
            <p className="text-sm text-muted-foreground">
              Scan this with your authenticator app, then enter the 6-digit code to finish.
            </p>
            <div className="flex flex-col items-center gap-3 rounded-lg border p-4">
              <div className="rounded bg-white p-3">
                <QRCodeSVG value={enrollment.otpauthUri} size={168} />
              </div>
              <div className="text-center text-xs text-muted-foreground">
                Can’t scan? Enter this key manually:
                <code className="mt-1 block break-all font-mono text-foreground">
                  {enrollment.secret}
                </code>
              </div>
            </div>
            <CodeField code={code} onChange={setCode} />
            <div className="flex gap-2">
              <Button onClick={confirmEnrollment} disabled={busy || code.trim().length < 6}>
                {busy ? "Verifying…" : "Verify & enable"}
              </Button>
              <Button variant="ghost" onClick={reset} disabled={busy}>
                Cancel
              </Button>
            </div>
          </div>
        ) : (
          <div className="flex items-center justify-between gap-4">
            <div className="text-sm text-muted-foreground">
              Not set up. Add an authenticator app as a sign-in factor.
            </div>
            <Button variant="outline" size="sm" onClick={startEnrollment} disabled={busy}>
              {busy ? "Starting…" : "Set up"}
            </Button>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function CodePrompt({
  hint,
  action,
  variant,
  busy,
  code,
  setCode,
  onConfirm,
  onCancel,
}: {
  hint: string;
  action: string;
  variant?: "default" | "destructive";
  busy: boolean;
  code: string;
  setCode: (v: string) => void;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      <p className="text-sm text-muted-foreground">{hint}</p>
      <CodeField code={code} onChange={setCode} />
      <div className="flex gap-2">
        <Button variant={variant} onClick={onConfirm} disabled={busy || code.trim().length < 6}>
          {busy ? "Working…" : action}
        </Button>
        <Button variant="ghost" onClick={onCancel} disabled={busy}>
          Cancel
        </Button>
      </div>
    </div>
  );
}

function CodeField({ code, onChange }: { code: string; onChange: (value: string) => void }) {
  return (
    <div className="flex flex-col gap-1.5">
      <Label htmlFor="totp-code">6-digit code</Label>
      <Input
        id="totp-code"
        value={code}
        onChange={(e) => onChange(e.target.value.replace(/\D/g, "").slice(0, 6))}
        inputMode="numeric"
        autoComplete="one-time-code"
        placeholder="123456"
        maxLength={6}
        className="max-w-40 text-center text-lg tracking-[0.4em]"
      />
    </div>
  );
}
