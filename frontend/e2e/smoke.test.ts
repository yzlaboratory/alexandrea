import { afterAll, beforeAll, describe, it } from 'vitest';
import { chromium, type Browser, type Page } from 'playwright';
// Use @playwright/test's expect for its locator matchers (toBeVisible,
// toContainText, toBeDisabled, etc.) which auto-retry. Vitest's expect lacks
// these. The matcher-only import works inside a Vitest runner — we never
// invoke the @playwright/test runner itself.
import { expect } from '@playwright/test';

import { startStack, type Stack } from './fixtures';

let stack: Stack;
let browser: Browser;

beforeAll(async () => {
  stack = await startStack();
  browser = await chromium.launch();
}, 60_000);

afterAll(async () => {
  await browser?.close();
  await stack?.stop();
});

async function login(page: Page, username: string, password: string): Promise<void> {
  await page.goto(`${stack.baseUrl}/login`);
  await page.getByRole('textbox', { name: 'Username' }).fill(username);
  await page.getByRole('textbox', { name: 'Password' }).fill(password);
  await Promise.all([
    page.waitForURL(`${stack.baseUrl}/`),
    page.getByRole('button', { name: 'Sign in' }).click(),
  ]);
}

describe('smoke flow', () => {
  it('logs in, adds a title, rates it, removes the rating, then verifies the partner sees the shared library', async () => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();

    // 1. Login as the primary user.
    await login(page, stack.users.primary.username, stack.users.primary.password);
    await expect(page.getByRole('heading', { level: 1 })).toContainText(
      `Hello, ${stack.users.primary.displayName}`,
    );

    // 2. Search "The Matrix" and add the first hit.
    await page.getByRole('searchbox').fill('The Matrix');
    const addButtons = page.getByRole('button', { name: 'Add to library' });
    await expect(addButtons.first()).toBeVisible();
    await addButtons.first().click();
    await expect(page.getByRole('button', { name: 'In library' }).first()).toBeVisible();

    // The library card for The Matrix shows up under "On our plate" with status Want.
    const libraryRegion = page.getByRole('region', { name: 'Library' });
    await expect(
      libraryRegion.getByRole('link', { name: 'The Matrix', exact: true }),
    ).toBeVisible();
    await expect(libraryRegion.getByRole('button', { name: 'Want' })).toBeDisabled();

    // 3. Open the title page, rate 4 stars with a note.
    await libraryRegion.getByRole('link', { name: 'The Matrix', exact: true }).click();
    await page.waitForURL(/\/title\//);
    await page.getByRole('radio', { name: '4 of 5' }).check();
    await page.getByRole('textbox', { name: /one line/i }).fill('e2e note');
    await page.getByRole('button', { name: 'Save' }).click();

    // Status implicitly flips to Watched and the note is rendered.
    await expect(page.getByRole('button', { name: 'Watched', exact: true })).toBeDisabled();
    await expect(page.getByText('e2e note')).toBeVisible();
    await expect(page.getByLabel('4 out of 5')).toBeVisible();

    // 4. Verify it landed in the Watched tab on the home page.
    await page.getByRole('link', { name: 'Library' }).click();
    await page.waitForURL(`${stack.baseUrl}/`);
    await page.getByRole('tab', { name: 'Watched' }).click();
    await expect(page.getByRole('link', { name: 'The Matrix', exact: true })).toBeVisible();

    // 5. Remove the rating; the row stays.
    await page.getByRole('link', { name: 'The Matrix', exact: true }).click();
    await page.waitForURL(/\/title\//);
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(page.getByText('e2e note')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Watched', exact: true })).toBeDisabled();

    // 6. Verify the partner sees the shared library entry.
    await ctx.close();
    const partnerCtx = await browser.newContext();
    const partnerPage = await partnerCtx.newPage();
    await login(partnerPage, stack.users.partner.username, stack.users.partner.password);
    await expect(partnerPage.getByRole('heading', { level: 1 })).toContainText(
      `Hello, ${stack.users.partner.displayName}`,
    );
    await partnerPage.getByRole('tab', { name: 'Watched' }).click();
    await expect(
      partnerPage
        .getByRole('region', { name: 'Library' })
        .getByRole('link', { name: 'The Matrix', exact: true }),
    ).toBeVisible();
    await partnerCtx.close();

    // The TMDB stub really was the only path TMDB traffic took. If this
    // ever drops to zero, the env override regressed and the real upstream
    // was being called instead.
    expect(stack.tmdb.hits.search).toBeGreaterThan(0);
  }, 120_000);
});
