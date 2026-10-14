import { expect, test } from '@playwright/test';

import { serveFixtures, turnOffTransitions } from './fixtures';

// Baselines exist only for the pinned Playwright Linux image, because fonts and
// rendering differ between systems; see "Visual regression" in docs/testing.md.
// The 0.02 color threshold ignores antialiasing noise of a few levels but fails
// a shift of about six levels per channel; Playwright's default 0.2 lets a
// page-wide background or text color change pass.
const threshold = 0.02;
const screens = [
  { name: 'login', path: '/login', ready: 'Login with SSO' },
  { name: 'users', path: '/users', ready: 'Grace Hopper' },
  { name: 'orders', path: '/orders', ready: '#11' },
];

test.beforeEach(async ({ page }) => serveFixtures(page));

for (const screen of screens) {
  test(`the ${screen.name} screen matches its baseline`, async ({ page }) => {
    await page.goto(screen.path);
    await expect(page.getByText(screen.ready)).toBeVisible();
    await turnOffTransitions(page);
    await expect(page).toHaveScreenshot(`${screen.name}.png`, { fullPage: true, threshold });
  });
}
