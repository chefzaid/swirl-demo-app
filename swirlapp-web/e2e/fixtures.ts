import type { Page } from '@playwright/test';

// The development build runs without authentication; these fixtures answer the
// APIs so browser checks stay hermetic and their screens stay identical.
export const users = [
  { id: 1, name: 'Ada Lovelace', username: 'ada', email: 'ada@example.test' },
  { id: 2, name: 'Grace Hopper', username: 'grace', email: 'grace@example.test' },
];
export const orders = [
  { id: 10, userId: 1, userName: 'Ada Lovelace', productId: 1001, status: 'APPROVED' },
  { id: 11, userId: 2, userName: 'Grace Hopper', productId: 1002, status: 'PENDING' },
];

export async function serveFixtures(page: Page): Promise<void> {
  await page.route(/\/api\/users(\?.*)?$/, (route) => route.fulfill({ json: users }));
  await page.route(/\/api\/orders(\?.*)?$/, (route) => route.fulfill({ json: orders }));
}

// Checks read settled colors, but a fade may still be running, and headless
// WebKit can leave a transition unadvanced, so turn transitions off first.
export async function turnOffTransitions(page: Page): Promise<void> {
  await page.addStyleTag({ content: '*, *::before, *::after { transition: none !important; }' });
}
