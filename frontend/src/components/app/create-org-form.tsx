"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { ApiError } from "@/lib/api";
import { useAuth } from "@/providers/auth-provider";
import { Button } from "@/components/ui/button";
import { Field, FieldDescription, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";

const schema = z.object({
  name: z
    .string()
    .min(2, "Name must be at least 2 characters")
    .max(255, "Must be 255 characters or fewer"),
});

/**
 * Creates an organization the caller will own, then switches the session into it (ADR 0001) and
 * lands on the dashboard. Used by the no-org screen and the "create another org" page.
 */
export function CreateOrgForm() {
  const router = useRouter();
  const { createOrg } = useAuth();
  const form = useForm<z.infer<typeof schema>>({
    // zodResolver's overload trips on Zod v4 branded types — see react-hook-form/resolvers#842.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema as any),
    defaultValues: { name: "" },
  });

  async function onSubmit(values: z.infer<typeof schema>) {
    try {
      await createOrg(values.name.trim());
      toast.success("Organization created");
      router.push("/dashboard");
    } catch (error) {
      toast.error(
        error instanceof ApiError ? error.message : "Could not create the organization",
      );
    }
  }

  return (
    <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
      <Controller
        name="name"
        control={form.control}
        render={({ field, fieldState }) => (
          <Field data-invalid={fieldState.invalid}>
            <FieldLabel htmlFor="org-name">Organization name</FieldLabel>
            <Input
              {...field}
              id="org-name"
              placeholder="Acme Bank"
              autoComplete="organization"
              aria-invalid={fieldState.invalid}
              autoFocus
            />
            <FieldDescription>You&apos;ll be its owner and can invite your team.</FieldDescription>
            {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
          </Field>
        )}
      />
      <Button type="submit" className="mt-4 w-full" disabled={form.formState.isSubmitting}>
        {form.formState.isSubmitting ? "Creating…" : "Create organization"}
      </Button>
    </form>
  );
}
