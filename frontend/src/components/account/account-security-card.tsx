"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { CircleCheck } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { accountApi, ApiError, authApi } from "@/lib/api";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Field, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { useAuth } from "@/providers/auth-provider";

const schema = z
  .object({
    currentPassword: z.string().min(1, "Enter your current password"),
    newPassword: z.string().min(8, "Must be at least 8 characters"),
    confirmPassword: z.string(),
  })
  .refine((d) => d.newPassword === d.confirmPassword, {
    path: ["confirmPassword"],
    message: "Passwords do not match",
  });

export function AccountSecurityCard() {
  const { user, logout } = useAuth();
  const [resending, setResending] = useState(false);
  const [signingOut, setSigningOut] = useState(false);

  const form = useForm<z.infer<typeof schema>>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema as any),
    defaultValues: { currentPassword: "", newPassword: "", confirmPassword: "" },
  });

  async function onChangePassword(data: z.infer<typeof schema>) {
    try {
      await accountApi.changePassword({
        currentPassword: data.currentPassword,
        newPassword: data.newPassword,
      });
      // The backend revokes every session on a password change, so sign back in.
      toast.success("Password changed — please sign in again");
      await logout();
    } catch (error) {
      if (error instanceof ApiError && error.status === 400) {
        form.setError("currentPassword", {
          message: error.message || "Current password is incorrect",
        });
      } else {
        toast.error("Could not change your password");
      }
    }
  }

  async function onResend() {
    setResending(true);
    try {
      await accountApi.resendVerification();
      toast.success("Verification email sent");
    } catch {
      toast.error("Could not send the verification email");
    } finally {
      setResending(false);
    }
  }

  async function onLogoutEverywhere() {
    setSigningOut(true);
    try {
      await authApi.logoutAll();
      toast.success("Signed out of all devices");
      await logout();
    } catch {
      toast.error("Could not sign out everywhere");
      setSigningOut(false);
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Security</CardTitle>
        <CardDescription>
          Change your password, verify your email, and manage sessions.
        </CardDescription>
      </CardHeader>
      <CardContent className="gap-6">
        {/* Change password */}
        <form onSubmit={form.handleSubmit(onChangePassword)} noValidate>
          <FieldGroup>
            <Controller
              name="currentPassword"
              control={form.control}
              render={({ field, fieldState }) => (
                <Field data-invalid={fieldState.invalid}>
                  <FieldLabel htmlFor="currentPassword">Current password</FieldLabel>
                  <Input
                    {...field}
                    id="currentPassword"
                    type="password"
                    autoComplete="current-password"
                    aria-invalid={fieldState.invalid}
                  />
                  {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
                </Field>
              )}
            />
            <Controller
              name="newPassword"
              control={form.control}
              render={({ field, fieldState }) => (
                <Field data-invalid={fieldState.invalid}>
                  <FieldLabel htmlFor="newPassword">New password</FieldLabel>
                  <Input
                    {...field}
                    id="newPassword"
                    type="password"
                    autoComplete="new-password"
                    aria-invalid={fieldState.invalid}
                  />
                  {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
                </Field>
              )}
            />
            <Controller
              name="confirmPassword"
              control={form.control}
              render={({ field, fieldState }) => (
                <Field data-invalid={fieldState.invalid}>
                  <FieldLabel htmlFor="confirmPassword">Confirm new password</FieldLabel>
                  <Input
                    {...field}
                    id="confirmPassword"
                    type="password"
                    autoComplete="new-password"
                    aria-invalid={fieldState.invalid}
                  />
                  {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
                </Field>
              )}
            />
            <Button
              type="submit"
              className="w-fit"
              disabled={form.formState.isSubmitting}
            >
              {form.formState.isSubmitting ? "Updating…" : "Update password"}
            </Button>
          </FieldGroup>
        </form>

        <Separator />

        {/* Email verification */}
        <div className="flex items-center justify-between gap-4">
          <div className="text-sm">
            <div className="font-medium">Email verification</div>
            {user?.emailVerified ? (
              <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-600 dark:text-emerald-400">
                <CircleCheck className="size-3.5" />
                Verified
              </span>
            ) : (
              <div className="text-muted-foreground">
                Your email address is not verified yet.
              </div>
            )}
          </div>
          {!user?.emailVerified && (
            <Button variant="outline" size="sm" onClick={onResend} disabled={resending}>
              {resending ? "Sending…" : "Resend email"}
            </Button>
          )}
        </div>

        <Separator />

        {/* Sessions */}
        <div className="flex items-center justify-between gap-4">
          <div className="text-sm">
            <div className="font-medium">Sign out everywhere</div>
            <div className="text-muted-foreground">
              End every active session on all devices.
            </div>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={onLogoutEverywhere}
            disabled={signingOut}
          >
            {signingOut ? "Signing out…" : "Sign out everywhere"}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
