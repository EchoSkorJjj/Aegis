import {
  browserSupportsWebAuthn,
  startAuthentication,
  startRegistration,
} from "@simplewebauthn/browser";

import { authApi, mfaApi } from "@/lib/api";

/** Whether this browser can run WebAuthn ceremonies at all (gates the passkey UI). */
export function passkeysSupported(): boolean {
  return browserSupportsWebAuthn();
}

/**
 * Enrollment ceremony: fetch creation options, have the authenticator create a credential, and
 * register it. `@simplewebauthn/browser` handles the ArrayBuffer↔base64url encoding both ways.
 */
export async function enrollPasskey(
  label: string,
): Promise<{ credentialId: string; label: string; recoveryCodes: string[] }> {
  const optionsJSON = await mfaApi.webauthnRegisterBegin();
  const credential = await startRegistration({ optionsJSON });
  return mfaApi.webauthnRegisterFinish({ label, credential });
}

/**
 * Assertion (login) ceremony for a pending WEBAUTHN challenge: fetch request options, sign them
 * with the passkey, and verify — which establishes the httpOnly session on success.
 */
export async function assertPasskey(challengeId: string): Promise<void> {
  const optionsJSON = await authApi.webauthnLoginOptions({ challengeId });
  const credential = await startAuthentication({ optionsJSON });
  await authApi.webauthnLoginVerify({ challengeId, credential });
}
