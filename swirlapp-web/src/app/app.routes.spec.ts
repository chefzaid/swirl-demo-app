import { describe, expect, it } from 'vitest';
import { routes } from './app.routes';
import { authGuard } from './guards/auth.guard';
import { LoginComponent } from './components/login/login.component';
import { UserComponent } from './user/user.component';
import { OrderComponent } from './order/order.component';

describe('app routes', () => {
  const route = (path: string) => routes.find(candidate => candidate.path === path)!;

  it('gives every screen its own tab title', () => {
    expect(route('login').title).toBe('Sign in · Swirl Demo App');
    expect(route('users').title).toBe('Users · Swirl Demo App');
    expect(route('orders').title).toBe('Orders · Swirl Demo App');
  });

  it('lazy-loads each screen and guards the signed-in ones', async () => {
    expect(await route('login').loadComponent!()).toBe(LoginComponent);
    expect(await route('users').loadComponent!()).toBe(UserComponent);
    expect(await route('orders').loadComponent!()).toBe(OrderComponent);
    expect(route('login').canActivate).toBeUndefined();
    expect(route('users').canActivate).toEqual([authGuard]);
    expect(route('orders').canActivate).toEqual([authGuard]);
  });

  it('opens the user directory by default', () => {
    expect(route('')).toMatchObject({ redirectTo: '/users', pathMatch: 'full' });
  });
});
