"use client";

import { useEffect, useState } from "react";
import { Hexagon } from "lucide-react";

import { authApi } from "@/lib/api";
import { useAuth } from "@/providers/auth-provider";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { CreateOrgForm } from "@/components/app/create-org-form";

/**
 * Shown (instead of the app shell) to a signed-in user who belongs to no organization yet
 * (ADR 0001). They can create one — when self-serve org creation is enabled — or wait for an
 * administrator to invite them.
 */
export function NoOrgScreen() {
  const { logout } = useAuth();
  // null = still loading the config flag.
  const [orgSignupEnabled, setOrgSignupEnabled] = useState<boolean | null>(null);

  useEffect(() => {
    let active = true;
    authApi
      .config()
      .then((config) => {
        if (active) setOrgSignupEnabled(config.organizationSignupEnabled);
      })
      .catch(() => {
        if (active) setOrgSignupEnabled(false);
      });
    return () => {
      active = false;
    };
  }, []);

  return (
    <div className="flex min-h-dvh items-center justify-center p-4">
      <div className="flex w-full max-w-md flex-col gap-4">
        <Card>
          <CardHeader className="text-center">
            <span className="mx-auto mb-2 flex size-10 items-center justify-center rounded-md bg-primary text-primary-foreground">
              <Hexagon className="size-5" />
            </span>
            <CardTitle className="text-xl">Create or join an organization</CardTitle>
            <CardDescription>
              Your account isn&apos;t part of an organization yet. Create one to get started, or ask
              an administrator to invite you to theirs.
            </CardDescription>
          </CardHeader>
          <CardContent>
            {orgSignupEnabled === null ? (
              <p className="text-center text-sm text-muted-foreground">Loading…</p>
            ) : orgSignupEnabled ? (
              <CreateOrgForm />
            ) : (
              <p className="rounded-lg border border-dashed p-4 text-center text-sm text-muted-foreground">
                Self-serve organization creation is turned off. You&apos;ll get access once an
                administrator invites you to their organization.
              </p>
            )}
          </CardContent>
        </Card>
        <div className="text-center">
          <Button variant="ghost" size="sm" onClick={() => logout()}>
            Sign out
          </Button>
        </div>
      </div>
    </div>
  );
}
