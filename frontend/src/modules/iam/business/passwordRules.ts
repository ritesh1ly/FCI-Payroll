/** Password form rules (pure; the server enforces the policy: length, history, common passwords). */

/** The new password and its confirmation differ. */
export function confirmationDiffers(password: string, confirmation: string): boolean {
  // Not a secret comparison: both values were typed by this user into this form.
  return password !== confirmation;
}
