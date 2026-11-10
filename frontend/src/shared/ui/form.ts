/**
 * The text of a submitted form's field {@code name}, or {@code fallback} when the form has no such
 * field. A file field is never read as text here (its name, not its content, would come back).
 */
export function formText(form: FormData, name: string, fallback = ''): string {
  const value = form.get(name);
  return typeof value === 'string' ? value : fallback;
}
