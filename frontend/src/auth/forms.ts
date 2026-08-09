// FormData.get returns string | File | null; our inputs are always text, so
// anything else becomes '' and the server rejects it.
export function textField(formData: FormData, name: string): string {
  const value = formData.get(name);
  return typeof value === 'string' ? value : '';
}

// Mirrors the server's PasswordPolicy (MIN_LENGTH/MAX_LENGTH); the server is
// the source of truth and re-validates regardless of this client-side hint.
export const MIN_PASSWORD_LENGTH = 12;
export const MAX_PASSWORD_LENGTH = 128;

export const PASSWORD_LENGTH_ERROR = `Password must be between ${String(MIN_PASSWORD_LENGTH)} and ${String(MAX_PASSWORD_LENGTH)} characters.`;

export const PASSWORD_LENGTH_HINT = `At least ${String(MIN_PASSWORD_LENGTH)} characters.`;
