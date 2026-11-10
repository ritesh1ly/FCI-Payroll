/**
 * SRS-G-48 / SRS-G-21, Module 02 D-2: a screen on which the acting role holds a sensitive action needs
 * a recent OTP step-up; the server decides (AUTH-403-STEP-UP), the workspace asks first so the user
 * is not shown a refused screen.
 */
/** Whether a screen of the session needs a step-up now (sensitive and no valid confirmation). */
export function needsStepUp(
  me: { sensitiveScreens: string[]; stepUpValidUntil: string | null },
  screen: string | null,
  now: number = Date.now(),
): boolean {
  if (!screen || !me.sensitiveScreens.includes(screen)) return false;
  // Valid only while a readable expiry lies ahead: no confirmation, an expired one, or one that
  // cannot be read all ask for the OTP (fail closed; the server refuses the same).
  const validUntil = me.stepUpValidUntil ? Date.parse(me.stepUpValidUntil) : Number.NaN;
  return !(validUntil > now);
}
