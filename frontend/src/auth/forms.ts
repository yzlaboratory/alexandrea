// FormData.get returns string | File | null; our inputs are always text, so
// anything else becomes '' and the server rejects it.
export function textField(formData: FormData, name: string): string {
  const value = formData.get(name);
  return typeof value === 'string' ? value : '';
}
