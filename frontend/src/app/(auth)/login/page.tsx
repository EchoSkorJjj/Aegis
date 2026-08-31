import type { Metadata } from "next";

import { LoginForm } from "@/components/form/login-form";
import { getAuthConfig } from "@/lib/server/config";

export const metadata: Metadata = { title: "Log in" };

export default async function LoginPage() {
  const { registrationEnabled } = await getAuthConfig();
  return <LoginForm registrationEnabled={registrationEnabled} />;
}
