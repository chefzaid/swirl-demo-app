import { expect, type Locator, type Page, test } from '@playwright/test';

const target = new URL(process.env['WEB_URL'] ?? 'http://127.0.0.1:4200');
const localDemo = ['localhost', '127.0.0.1', '[::1]', 'web'].includes(target.hostname);
const username = process.env['OIDC_USERNAME'] ?? (localDemo ? 'user' : '');
const password = process.env['OIDC_PASSWORD'] ?? (localDemo ? 'password' : '');
const exerciseWrites = process.env['E2E_EXERCISE_WRITES'] === 'true';

interface CreatedOrder {
  id: number;
}

interface CreatedUser {
  id: number;
  name: string;
  username: string;
}

// Refreshes the order list until Kafka validation has set the card's status.
async function expectOrderStatus(page: Page, orderCard: Locator, status: string): Promise<void> {
  await expect
    .poll(
      async () => {
        const refreshedOrders = page.waitForResponse(
          (response) =>
            response.url().endsWith('/api/orders') && response.request().method() === 'GET',
        );
        await page.getByRole('button', { name: 'Refresh' }).click();
        expect((await refreshedOrders).status()).toBe(200);
        return (await orderCard.locator('.order-status').textContent())?.trim();
      },
      { timeout: 30_000 },
    )
    .toBe(status);
}

test('authenticates through Keycloak and loads both secured workflows', async (
  { page },
  testInfo,
) => {
  if (!username || !password) {
    throw new Error('OIDC_USERNAME and OIDC_PASSWORD are required outside the local demo');
  }
  const configuration = await page.request.get('/runtime-config.json');
  expect(configuration.ok()).toBeTruthy();
  const { keycloakRealm: realm } = await configuration.json();
  expect(typeof realm).toBe('string');
  expect(realm).not.toBe('');
  const discoveryResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname.endsWith(
        `/realms/${realm}/.well-known/openid-configuration`,
      ),
    { timeout: 15_000 },
  );

  await page.goto('/login');
  expect((await discoveryResponse).status()).toBe(200);

  // Only builds with environment.prod.ts enable OIDC, and they start the code
  // flow as soon as discovery is ready. The login button is enabled for that
  // same moment, so clicking it would race the redirect; wait for it instead.
  await page.waitForURL((url) => url.pathname.includes(`/auth/realms/${realm}/`));

  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  await Promise.all([
    page.waitForURL((url) => url.pathname === '/users'),
    page.locator('#kc-login').click(),
  ]);

  await expect(
    page.getByRole('heading', { level: 1, name: 'People behind the requests.' }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: 'User directory' })).toBeVisible();

  let createdUser: CreatedUser | undefined;
  let createdUserRow = page.locator('.user-item').filter({ hasText: '__not-created__' });
  if (exerciseWrites) {
    const userKey = `e2e-${testInfo.project.name}-${Date.now()}`;
    await page.getByLabel('Display name').fill(`Playwright ${testInfo.project.name}`);
    await page.getByLabel('Username').fill(userKey);
    await page.getByLabel('Email address').fill(`${userKey}@example.test`);

    const createdUserResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/users') && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Create User' }).click();
    const userResponse = await createdUserResponse;
    expect(userResponse.status()).toBe(201);
    createdUser = (await userResponse.json()) as CreatedUser;
    createdUserRow = page.locator('.user-item').filter({ hasText: userKey });
    await expect(createdUserRow).toBeVisible();

    await createdUserRow.getByRole('button', { name: `Edit ${createdUser.name}` }).click();
    await expect(
      page.getByRole('heading', { level: 2, name: `Update user #${createdUser.id}` }),
    ).toBeVisible();
    const updatedName = `Updated ${createdUser.name}`;
    await page.locator('#editDisplayName').fill(updatedName);
    const updatedUserResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/users/${createdUser?.id}`) &&
        response.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: 'Save changes' }).click();
    expect((await updatedUserResponse).status()).toBe(200);
    createdUser.name = updatedName;
    await expect(createdUserRow).toContainText(updatedName);
  }

  await expect(page.locator('.user-item').first()).toBeVisible();

  await page.getByRole('link', { name: 'Orders' }).click();
  await expect(page).toHaveURL((url) => url.pathname === '/orders');
  await expect(
    page.getByRole('heading', { level: 1, name: 'Orders that travel the stack.' }),
  ).toBeVisible();
  await expect(page.getByRole('heading', { level: 2, name: 'Create an order' })).toBeVisible();

  if (exerciseWrites) {
    const ownerSelect = page.getByLabel('Order owner');
    await expect.poll(() => ownerSelect.locator('option').count()).toBeGreaterThan(1);
    if (!createdUser) {
      throw new Error('The write journey requires its disposable user');
    }
    await ownerSelect.selectOption({ label: `${createdUser.name} (@${createdUser.username})` });
    await page.getByLabel('Product ID').fill('1001');

    const createdOrderResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/orders') && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Create Order' }).click();
    const orderResponse = await createdOrderResponse;
    expect(orderResponse.status()).toBe(201);
    const createdOrder = (await orderResponse.json()) as CreatedOrder;
    const orderCard = page.locator('.order-card').filter({
      has: page.getByRole('heading', { level: 3, name: `#${createdOrder.id}` }),
    });

    await expect(orderCard).toBeVisible();
    await expectOrderStatus(page, orderCard, 'APPROVED');

    await orderCard.getByRole('button', { name: `Edit order #${createdOrder.id}` }).click();
    await expect(
      page.getByRole('heading', { level: 2, name: `Update order #${createdOrder.id}` }),
    ).toBeVisible();
    await page.locator('#editProductInput').fill('1002');
    const updatedOrderResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/orders/${createdOrder.id}`) &&
        response.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: 'Save changes' }).click();
    expect((await updatedOrderResponse).status()).toBe(200);
    await expect(orderCard).toContainText('#1002');
    await expectOrderStatus(page, orderCard, 'APPROVED');

    await expect(orderCard).toContainText('Approved: the user service found the owner.');

    // Rejection path: once the owner is deleted, editing the order sends it for
    // validation again and the user service rejects it.
    await page.getByRole('link', { name: 'Users' }).click();
    await expect(createdUserRow).toBeVisible();
    await createdUserRow.getByRole('button', { name: `Delete ${createdUser.name}` }).click();
    const deletedUserResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/users/${createdUser?.id}`) &&
        response.request().method() === 'DELETE',
    );
    await createdUserRow.getByRole('button', { name: `Confirm delete ${createdUser.name}` }).click();
    expect((await deletedUserResponse).status()).toBe(204);
    await expect(createdUserRow).toHaveCount(0);

    await page.getByRole('link', { name: 'Orders' }).click();
    await orderCard.getByRole('button', { name: `Edit order #${createdOrder.id}` }).click();
    await page.locator('#editProductInput').fill('1003');
    const rejectedOrderResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/orders/${createdOrder.id}`) &&
        response.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: 'Save changes' }).click();
    expect((await rejectedOrderResponse).status()).toBe(200);
    await expect(orderCard).toContainText('#1003');
    await expectOrderStatus(page, orderCard, 'REJECTED');
    await expect(orderCard).toContainText('Rejected: the user service found no user with this ID.');

    await orderCard.getByRole('button', { name: `Delete order #${createdOrder.id}` }).click();
    const deletedOrderResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/orders/${createdOrder.id}`) &&
        response.request().method() === 'DELETE',
    );
    await orderCard.getByRole('button', { name: `Confirm delete order #${createdOrder.id}` }).click();
    expect((await deletedOrderResponse).status()).toBe(204);
    await expect(orderCard).toHaveCount(0);
  }
});
