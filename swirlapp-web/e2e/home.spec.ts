import { expect, test } from '@playwright/test';

import { serveFixtures } from './fixtures';

test('loads the SSO login screen', async ({ page }) => {
  await page.goto('/login');

  await expect(page).toHaveTitle('Sign in · Swirl Demo App');
  await expect(page.getByText('One small app.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Login with SSO' })).toBeVisible();
  await expect(page.getByText('This is the reference flow of a service template')).toBeVisible();
  await expect(page.getByRole('link', { name: 'read what it is for' }))
    .toHaveAttribute('href', 'https://github.com/chefzaid/swirl-demo-app#readme');
});

test('names the app consistently and drops the fixed footer status', async ({ page }) => {
  await page.goto('/login');

  await expect(page.getByRole('link', { name: /^Swirl Demo App/ })).toBeVisible();
  await expect(page.getByText('Live reference environment')).toHaveCount(0);
});

test('gives each signed-in screen its own title and explains order statuses', async ({ page }) => {
  await serveFixtures(page);

  await page.goto('/users');
  await expect(page.getByText('Grace Hopper')).toBeVisible();
  await expect(page).toHaveTitle('Users · Swirl Demo App');

  await page.getByRole('link', { name: 'Orders' }).click();
  await expect(page).toHaveTitle('Orders · Swirl Demo App');
  await expect(page.getByText('Approved: the user service found the owner.')).toBeVisible();
  await expect(page.getByText('Waiting for the user service to check that the owner exists.')).toBeVisible();
  await expect(page.getByText('Event pipeline connected')).toHaveCount(0);
});
