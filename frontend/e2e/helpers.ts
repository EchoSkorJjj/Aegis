import { expect, type Page } from "@playwright/test";

const MAILHOG_API = process.env.MAILHOG_API ?? "http://localhost:8025";

interface MailhogMessage {
  Content: { Headers: Record<string, string[]>; Body: string };
}

/** Polls Mailhog for the most recent login one-time-code sent to `email`. */
async function getLoginOtp(email: string): Promise<string> {
  for (let attempt = 0; attempt < 25; attempt++) {
    const res = await fetch(`${MAILHOG_API}/api/v2/messages`);
    if (res.ok) {
      const body = (await res.json()) as { items?: MailhogMessage[] };
      // Mailhog returns newest-first, so the first match is the freshest code.
      for (const item of body.items ?? []) {
        const to = item.Content?.Headers?.To ?? [];
        const subject = item.Content?.Headers?.Subject?.[0] ?? "";
        if (to.some((t) => t.includes(email)) && /login code/i.test(subject)) {
          const match = item.Content.Body.match(/(?<!\d)(\d{6})(?!\d)/);
          if (match) return match[1];
        }
      }
    }
    await new Promise((resolve) => setTimeout(resolve, 300));
  }
  throw new Error(`No login OTP email arrived for ${email}`);
}

/**
 * Signs in through the UI, completing the email-OTP second factor when it is required
 * (2FA on, the default) and passing straight through when it is not.
 */
export async function signIn(page: Page, email: string, password: string): Promise<void> {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Password", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();

  const otpField = page.getByPlaceholder("123456");
  const emailCodeButton = page.getByRole("button", { name: /email code/i });

  await expect
    .poll(
      async () => {
        if (page.url().includes("/dashboard")) return "done";
        if (await emailCodeButton.isVisible()) return "chooser";
        if (await otpField.isVisible()) return "otp";
        return "pending";
      },
      { timeout: 15_000 },
    )
    .not.toBe("pending");

  if (await emailCodeButton.isVisible()) {
    await emailCodeButton.click();
    await otpField.waitFor({ state: "visible" });
  }

  if (await otpField.isVisible()) {
    await otpField.fill(await getLoginOtp(email));
    await page.getByRole("button", { name: "Verify" }).click();
  }

  await page.waitForURL(/\/dashboard/);
  await expect(page).toHaveURL(/\/dashboard/);
}
