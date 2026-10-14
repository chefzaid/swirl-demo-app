import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';

import { serveFixtures, turnOffTransitions } from './fixtures';

async function expectNoViolations(page: Page): Promise<void> {
  await turnOffTransitions(page);
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze();
  const findings = results.violations.flatMap(({ id, nodes }) =>
    nodes.map((node) => `${id} at ${node.target.join(' ')}: ${node.failureSummary}`));
  expect(findings).toEqual([]);
}

test.beforeEach(async ({ page }) => serveFixtures(page));

test('the login screen meets WCAG 2.2 AA', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: 'Login with SSO' })).toBeVisible();
  await expectNoViolations(page);
});

test('the user directory meets WCAG 2.2 AA', async ({ page }) => {
  await page.goto('/users');
  await expect(page.getByRole('heading', { level: 2, name: 'User directory' })).toBeVisible();
  await expect(page.getByText('Grace Hopper')).toBeVisible();
  await expectNoViolations(page);
});

test('the order stream meets WCAG 2.2 AA', async ({ page }) => {
  await page.goto('/orders');
  await expect(page.getByRole('heading', { level: 2, name: 'Order stream' })).toBeVisible();
  await expect(page.getByText('#11')).toBeVisible();
  await expectNoViolations(page);
});

test('the primary navigation and user form work from the keyboard', async ({ page }) => {
  await page.goto('/users');
  await expect(page.getByText('Grace Hopper')).toBeVisible();

  const reached: string[] = [];
  for (let step = 0; step < 12 && !reached.includes('Display name'); step++) {
    await page.keyboard.press('Tab');
    reached.push(await page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null;
      const label = element?.id ? document.querySelector(`label[for="${element.id}"]`)?.textContent : null;
      return (label ?? element?.getAttribute('aria-label') ?? element?.textContent ?? '').trim();
    }));
  }
  expect(reached).toEqual(expect.arrayContaining(['Users', 'Orders', 'Display name']));

  await page.getByRole('link', { name: 'Orders' }).focus();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.getByLabel('Product ID')).toBeVisible();
});
