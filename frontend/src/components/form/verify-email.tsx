"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import { authApi } from "@/lib/api";
import { cn } from "@/lib/utils";
import { buttonVariants } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

type Status = "verifying" | "success" | "error";

const COPY: Record<Status, { title: string; description: string }> = {
  verifying: {
    title: "Verifying your email…",
    description: "One moment while we confirm your address.",
  },
  success: {
    title: "Email verified",
    description: "Your email address has been confirmed. You can now sign in.",
  },
  error: {
    title: "Verification failed",
    description:
      "This link is invalid or has expired. Sign in to request a new one.",
  },
};

export function VerifyEmail({ token }: { token: string }) {
  const [status, setStatus] = useState<Status>("verifying");
  const ran = useRef(false);

  useEffect(() => {
    // Guard against React's double-invoke in development so the single-use token
    // isn't spent twice.
    if (ran.current) return;
    ran.current = true;

    authApi
      .verifyEmail(token)
      .then(() => setStatus("success"))
      .catch(() => setStatus("error"));
  }, [token]);

  const copy = COPY[status];

  return (
    <div className="flex w-full max-w-sm flex-col gap-6">
      <Card>
        <CardHeader className="text-center">
          <CardTitle className="text-xl">{copy.title}</CardTitle>
          <CardDescription>{copy.description}</CardDescription>
        </CardHeader>
        {status !== "verifying" && (
          <CardContent>
            <Link href="/login" className={cn(buttonVariants(), "w-full")}>
              Go to sign in
            </Link>
          </CardContent>
        )}
      </Card>
    </div>
  );
}
