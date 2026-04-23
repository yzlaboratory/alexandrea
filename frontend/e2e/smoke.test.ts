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
  it('walks the canonical journey: login, add, rate, partner-share, unrate, remove, logout', async () => {
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

    // 5. Verify the partner sees the shared library entry (rating included). Run
    //    this before the primary user mutates the entry further, so we observe
    //    the full "rated Watched" state across users.
    const partnerCtx = await browser.newContext();
    const partnerPage = await partnerCtx.newPage();
    try {
      await login(partnerPage, stack.users.partner.username, stack.users.partner.password);
      await expect(partnerPage.getByRole('heading', { level: 1 })).toContainText(
        `Hello, ${stack.users.partner.displayName}`,
      );
      await partnerPage.getByRole('tab', { name: 'Watched' }).click();
      const partnerLib = partnerPage.getByRole('region', { name: 'Library' });
      await expect(partnerLib.getByRole('link', { name: 'The Matrix', exact: true })).toBeVisible();
      // The row's average-score badge proves the partner sees the rating,
      // not just the bare entry. 4/5 from the primary → 4.0 average.
      await expect(partnerLib.getByLabel('Average score 4.0 of 5')).toBeVisible();
    } finally {
      await partnerCtx.close();
    }

    // 6. Primary removes the rating; the row stays on the Watched tab.
    await page.getByRole('link', { name: 'The Matrix', exact: true }).click();
    await page.waitForURL(/\/title\//);
    await page.getByRole('button', { name: 'Edit' }).click();
    await page.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(page.getByText('e2e note')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Watched', exact: true })).toBeDisabled();

    // 7. Primary removes the entry entirely from the title page. The title
    //    page re-renders without a library entry, so the rating widget
    //    disappears and "Add to library" comes back.
    await page.getByRole('button', { name: 'Remove from library' }).click();
    await expect(page.getByRole('button', { name: 'Add to library' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Watched', exact: true })).toHaveCount(0);

    // Back on the home page the row is gone from every tab.
    await page.getByRole('link', { name: 'Library' }).click();
    await page.waitForURL(`${stack.baseUrl}/`);
    for (const tab of ['On our plate', 'Watched', 'Abandoned'] as const) {
      await page.getByRole('tab', { name: tab }).click();
      await expect(
        libraryRegion.getByRole('link', { name: 'The Matrix', exact: true }),
      ).toHaveCount(0);
    }

    // 8. Primary logs out; the guard redirects back to /login and the form is
    //    visible again. /api/me no longer reports a session.
    await page.getByRole('button', { name: /sign out/i }).click();
    await page.waitForURL(`${stack.baseUrl}/login`);
    await expect(page.getByRole('textbox', { name: 'Username' })).toBeVisible();
    const meRes = await page.request.get(`${stack.baseUrl}/api/me`);
    expect(meRes.status()).toBe(401);

    await ctx.close();

    // The TMDB stub really was the only path TMDB traffic took. If this
    // ever drops to zero, the env override regressed and the real upstream
    // was being called instead.
    expect(stack.tmdb.hits.search).toBeGreaterThan(0);
  }, 120_000);
});
