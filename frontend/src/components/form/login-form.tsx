"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import Link from "next/link";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { ApiError, authApi } from "@/lib/api";
import type { MfaMethod } from "@/lib/types";
import { cn } from "@/lib/utils";
import { useAuth } from "@/providers/auth-provider";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  Field,
  FieldDescription,
  FieldError,
  FieldGroup,
  FieldLabel,
} from "@/components/ui/field";
import { Input } from "@/components/ui/input";

const credentialsSchema = z.object({
  email: z.email("Invalid email address"),
  password: z.string().min(1, "Password is required"),
});

// Accepts a 6-digit OTP/TOTP or a longer recovery code; the backend does the real check.
const otpSchema = z.object({
  code: z.string().min(1, "Enter your code"),
});

/** Set once the password is accepted and a second factor is pending. */
interface PendingChallenge {
  challengeId: string;
  email: string;
  method: MfaMethod; // the armed factor (which input to show)
  methods: MfaMethod[]; // every factor the user may switch to
}

const METHOD_LABEL: Record<MfaMethod, string> = {
  EMAIL_OTP: "email code",
  TOTP: "authenticator app",
  WEBAUTHN: "passkey",
};

export function LoginForm({
  className,
  registrationEnabled = true,
  ...props
}: React.ComponentProps<"div"> & { registrationEnabled?: boolean }) {
  const { login, verifyOtp } = useAuth();
  const [challenge, setChallenge] = useState<PendingChallenge | null>(null);
  const [useRecovery, setUseRecovery] = useState(false);

  const credentialsForm = useForm<z.infer<typeof credentialsSchema>>({
    // zodResolver's overload trips on Zod v4 branded types — see react-hook-form/resolvers#842.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(credentialsSchema as any),
    defaultValues: { email: "", password: "" },
  });

  const otpForm = useForm<z.infer<typeof otpSchema>>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(otpSchema as any),
    defaultValues: { code: "" },
  });

  async function onCredentials(data: z.infer<typeof credentialsSchema>) {
    try {
      const result = await login(data);
      if (result.mfaRequired && result.challengeId && result.method && result.methods) {
        setChallenge({
          challengeId: result.challengeId,
          email: data.email,
          method: result.method,
          methods: result.methods,
        });
        otpForm.reset({ code: "" });
        if (result.method === "EMAIL_OTP") {
          toast.message("Check your email", {
            description: `We sent a 6-digit code to ${data.email}.`,
          });
        }
      } else {
        toast.success("Welcome back!");
      }
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 401) {
          credentialsForm.setError("root", {
            message: "Invalid email or password",
          });
        } else if (error.status === 423) {
          toast.error("Account locked", {
            description:
              "Too many failed attempts. Please try again in a few minutes.",
          });
        } else {
          toast.error(error.message || "Something went wrong. Please try again.");
        }
      } else {
        toast.error("Could not reach the server. Please try again.");
      }
    }
  }

  async function onVerify(data: z.infer<typeof otpSchema>) {
    if (!challenge) return;
    try {
      await verifyOtp({ challengeId: challenge.challengeId, code: data.code });
      toast.success("Welcome back!");
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        otpForm.setError("code", { message: "Invalid or expired code" });
      } else if (error instanceof ApiError) {
        toast.error(error.message || "Something went wrong. Please try again.");
      } else {
        toast.error("Could not reach the server. Please try again.");
      }
    }
  }

  async function useMethod(method: MfaMethod) {
    if (!challenge || method === challenge.method) return;
    try {
      const updated = await authApi.selectMethod({
        challengeId: challenge.challengeId,
        method,
      });
      setChallenge({ ...challenge, method: updated.method, methods: updated.methods });
      setUseRecovery(false);
      otpForm.reset({ code: "" });
      if (updated.method === "EMAIL_OTP") {
        toast.message("Check your email", {
          description: `We sent a 6-digit code to ${challenge.email}.`,
        });
      }
    } catch {
      toast.error("Could not switch method. Please try again.");
    }
  }

  function restart() {
    setChallenge(null);
    setUseRecovery(false);
    otpForm.reset({ code: "" });
  }

  return (
    <div
      className={cn("flex w-full max-w-sm flex-col gap-6", className)}
      {...props}
    >
      <Card>
        {challenge ? (
          <>
            <CardHeader className="text-center">
              <CardTitle className="text-xl">Enter your code</CardTitle>
              <CardDescription>
                {useRecovery
                  ? "Enter one of your saved recovery codes."
                  : challenge.method === "TOTP"
                    ? "Open your authenticator app and enter the 6-digit code."
                    : `We sent a 6-digit code to ${challenge.email}. It expires shortly.`}
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form onSubmit={otpForm.handleSubmit(onVerify)} noValidate>
                <FieldGroup>
                  <Controller
                    name="code"
                    control={otpForm.control}
                    render={({ field, fieldState }) => (
                      <Field data-invalid={fieldState.invalid}>
                        <FieldLabel htmlFor="otp-code">
                          {useRecovery ? "Recovery code" : "Verification code"}
                        </FieldLabel>
                        <Input
                          {...field}
                          id="otp-code"
                          inputMode={useRecovery ? "text" : "numeric"}
                          autoComplete="one-time-code"
                          maxLength={useRecovery ? 14 : 6}
                          autoFocus
                          aria-invalid={fieldState.invalid}
                          placeholder={useRecovery ? "xxxxx-xxxxx" : "123456"}
                          className={
                            useRecovery
                              ? "text-center tracking-wider"
                              : "text-center text-lg tracking-[0.5em]"
                          }
                        />
                        {fieldState.invalid && (
                          <FieldError errors={[fieldState.error]} />
                        )}
                      </Field>
                    )}
                  />
                  <Button
                    type="submit"
                    className="w-full"
                    disabled={otpForm.formState.isSubmitting}
                  >
                    {otpForm.formState.isSubmitting ? "Verifying..." : "Verify"}
                  </Button>
                  {challenge.methods.includes("TOTP") && (
                    <FieldDescription className="text-center">
                      <button
                        type="button"
                        onClick={() => {
                          setUseRecovery((v) => !v);
                          otpForm.reset({ code: "" });
                        }}
                        className="underline-offset-4 hover:underline"
                      >
                        {useRecovery ? "Use your authenticator code" : "Use a recovery code"}
                      </button>
                    </FieldDescription>
                  )}
                  {!useRecovery && challenge.methods.length > 1 && (
                    <FieldDescription className="text-center">
                      Or use{" "}
                      {challenge.methods
                        .filter((m) => m !== challenge.method)
                        .map((m, i, arr) => (
                          <span key={m}>
                            <button
                              type="button"
                              onClick={() => useMethod(m)}
                              className="underline-offset-4 hover:underline"
                            >
                              {METHOD_LABEL[m]}
                            </button>
                            {i < arr.length - 1 ? " or " : ""}
                          </span>
                        ))}
                    </FieldDescription>
                  )}
                  <FieldDescription className="text-center">
                    <button
                      type="button"
                      onClick={restart}
                      className="underline-offset-4 hover:underline"
                    >
                      Start over
                    </button>
                  </FieldDescription>
                </FieldGroup>
              </form>
            </CardContent>
          </>
        ) : (
          <>
            <CardHeader className="text-center">
              <CardTitle className="text-xl">Welcome back</CardTitle>
              <CardDescription>
                Enter your email below to sign in to your account.
              </CardDescription>
            </CardHeader>
            <CardContent>
              <form
                onSubmit={credentialsForm.handleSubmit(onCredentials)}
                noValidate
              >
                <FieldGroup>
                  <Controller
                    name="email"
                    control={credentialsForm.control}
                    render={({ field, fieldState }) => (
                      <Field data-invalid={fieldState.invalid}>
                        <FieldLabel htmlFor="email">Email</FieldLabel>
                        <Input
                          {...field}
                          id="email"
                          type="email"
                          autoComplete="email"
                          aria-invalid={fieldState.invalid}
                          placeholder="m@example.com"
                        />
                        {fieldState.invalid && (
                          <FieldError errors={[fieldState.error]} />
                        )}
                      </Field>
                    )}
                  />
                  <Controller
                    name="password"
                    control={credentialsForm.control}
                    render={({ field, fieldState }) => (
                      <Field data-invalid={fieldState.invalid}>
                        <div className="flex items-center justify-between">
                          <FieldLabel htmlFor="password">Password</FieldLabel>
                          <Link
                            href="/forgot-password"
                            className="text-xs text-muted-foreground underline-offset-4 transition-colors hover:text-foreground hover:underline"
                          >
                            Forgot password?
                          </Link>
                        </div>
                        <Input
                          {...field}
                          id="password"
                          type="password"
                          autoComplete="current-password"
                          aria-invalid={fieldState.invalid}
                          placeholder="********"
                        />
                        {fieldState.invalid && (
                          <FieldError errors={[fieldState.error]} />
                        )}
                      </Field>
                    )}
                  />
                  {credentialsForm.formState.errors.root && (
                    <FieldError
                      errors={[credentialsForm.formState.errors.root]}
                    />
                  )}
                  <Button
                    type="submit"
                    className="w-full"
                    disabled={credentialsForm.formState.isSubmitting}
                  >
                    {credentialsForm.formState.isSubmitting
                      ? "Signing in..."
                      : "Sign in"}
                  </Button>
                  {registrationEnabled && (
                    <FieldDescription className="text-center">
                      Don&apos;t have an account?{" "}
                      <Link href="/signup">Sign up</Link>
                    </FieldDescription>
                  )}
                </FieldGroup>
              </form>
            </CardContent>
          </>
        )}
      </Card>
      <FieldDescription className="px-6 text-center">
        By continuing, you agree to our <Link href="#">Terms of Service</Link>{" "}
        and <Link href="#">Privacy Policy</Link>.
      </FieldDescription>
    </div>
  );
}
