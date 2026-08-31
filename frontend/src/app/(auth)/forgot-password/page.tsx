import type { Metadata } from "next";

import { ForgotPasswordForm } from "@/components/form/forgot-password-form";

export const metadata: Metadata = { title: "Forgot password" };

export default function ForgotPasswordPage() {
  return <ForgotPasswordForm />;
}
