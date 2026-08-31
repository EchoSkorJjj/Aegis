"use client";

import { zodResolver } from "@hookform/resolvers/zod";
import { useEffect } from "react";
import { Controller, useForm } from "react-hook-form";
import { toast } from "sonner";
import { z } from "zod";

import { accountApi, ApiError } from "@/lib/api";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Field, FieldError, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { useAuth } from "@/providers/auth-provider";

const schema = z.object({
  displayName: z.string().max(100, "Must be 100 characters or fewer"),
});

export function AccountProfileCard() {
  const { user, refresh } = useAuth();
  const form = useForm<z.infer<typeof schema>>({
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    resolver: zodResolver(schema as any),
    defaultValues: { displayName: user?.displayName ?? "" },
  });

  // Populate once the user has loaded from the server.
  useEffect(() => {
    if (user) form.reset({ displayName: user.displayName ?? "" });
  }, [user, form]);

  async function onSubmit(data: z.infer<typeof schema>) {
    try {
      await accountApi.updateProfile({ displayName: data.displayName.trim() });
      await refresh();
      toast.success("Profile updated");
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : "Could not update profile");
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Profile</CardTitle>
        <CardDescription>Your account email and display name.</CardDescription>
      </CardHeader>
      <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
        <CardContent className="gap-4">
          <Field>
            <FieldLabel htmlFor="email">Email</FieldLabel>
            <Input id="email" value={user?.email ?? ""} readOnly disabled />
          </Field>
          <Controller
            name="displayName"
            control={form.control}
            render={({ field, fieldState }) => (
              <Field data-invalid={fieldState.invalid}>
                <FieldLabel htmlFor="displayName">Display name</FieldLabel>
                <Input
                  {...field}
                  id="displayName"
                  placeholder="Your name"
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
            {form.formState.isSubmitting ? "Saving…" : "Save changes"}
          </Button>
        </CardContent>
      </form>
    </Card>
  );
}
