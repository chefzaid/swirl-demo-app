import { Routes } from '@angular/router';
import { authGuard } from './guards/auth.guard';

const APP_NAME = 'Swirl Demo App';

export const routes: Routes = [
  {
    path: 'login',
    title: `Sign in · ${APP_NAME}`,
    loadComponent: () => import('./components/login/login.component').then(module => module.LoginComponent)
  },
  {
    path: 'users',
    title: `Users · ${APP_NAME}`,
    loadComponent: () => import('./user/user.component').then(module => module.UserComponent),
    canActivate: [authGuard]
  },
  {
    path: 'orders',
    title: `Orders · ${APP_NAME}`,
    loadComponent: () => import('./order/order.component').then(module => module.OrderComponent),
    canActivate: [authGuard]
  },
  { path: '', redirectTo: '/users', pathMatch: 'full' }
];
