"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { UserPlus } from "lucide-react";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { adminApi, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";

const schema = z.object({
  email: z.email("Enter a valid email"),
  displayName: z.string().max(100, "Must be 100 characters or fewer"),
});

/** "Invite user" button that expands into an inline provisioning form. */
export function InviteUserForm({ onInvited }: { onInvited: () => void }) {
  const [open, setOpen] = useState(false);
  const form = useForm<z.infer<typeof schema>>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema as any),
    defaultValues: { email: "", displayName: "" },
  });

  function close() {
    setOpen(false);
    form.reset({ email: "", displayName: "" });
  }

  async function onSubmit(data: z.infer<typeof schema>) {
    try {
      await adminApi.inviteUser({
        email: data.email,
        displayName: data.displayName.trim() || undefined,
      });
      toast.success(`Invite sent to ${data.email}`);
      close();
      onInvited();
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        form.setError("email", { message: "An account with this email already exists" });
      } else {
        toast.error(error instanceof ApiError ? error.message : "Could not send the invite");
      }
    }
  }

  if (!open) {
    return (
      <Button onClick={() => setOpen(true)}>
        <UserPlus />
        Invite user
      </Button>
    );
  }

  return (
    <form
      onSubmit={form.handleSubmit(onSubmit)}
      noValidate
      className="w-full rounded-lg border p-4"
    >
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
        <Controller
          name="email"
          control={form.control}
          render={({ field, fieldState }) => (
            <Field data-invalid={fieldState.invalid} className="flex-1">
              <FieldLabel htmlFor="invite-email">Email</FieldLabel>
              <Input
                {...field}
                id="invite-email"
                type="email"
                placeholder="person@example.com"
                aria-invalid={fieldState.invalid}
                autoFocus
              />
              {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
            </Field>
          )}
        />
        <Controller
          name="displayName"
          control={form.control}
          render={({ field, fieldState }) => (
            <Field data-invalid={fieldState.invalid} className="flex-1">
              <FieldLabel htmlFor="invite-name">Name (optional)</FieldLabel>
              <Input
                {...field}
                id="invite-name"
                placeholder="Their name"
                aria-invalid={fieldState.invalid}
              />
              {fieldState.invalid && <FieldError errors={[fieldState.error]} />}
            </Field>
          )}
        />
      </div>
      <div className="mt-3 flex items-center gap-2">
        <Button type="submit" size="sm" disabled={form.formState.isSubmitting}>
          {form.formState.isSubmitting ? "Sending…" : "Send invite"}
        </Button>
        <Button type="button" variant="ghost" size="sm" onClick={close}>
          Cancel
        </Button>
        <p className="ml-auto text-xs text-muted-foreground">
          They&apos;ll get an email to set their password.
        </p>
      </div>
    </form>
  );
}
