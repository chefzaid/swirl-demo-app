import { bootstrapApplication } from '@angular/platform-browser';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors, withXhr } from '@angular/common/http';
import { provideOAuthClient } from 'angular-oauth2-oidc';
import { AppComponent } from './app/app.component';
import { routes } from './app/app.routes';
import { authInterceptor } from './app/interceptors/auth.interceptor';
import { loadRuntimeConfig, RUNTIME_CONFIG } from './app/runtime-config';
import { environment } from './environments/environment';

async function startApplication(): Promise<void> {
  const runtimeConfig = environment.authEnabled
    ? await loadRuntimeConfig(window.location.origin)
    : null;
  await bootstrapApplication(AppComponent, {
    providers: [
      { provide: RUNTIME_CONFIG, useValue: runtimeConfig },
      provideRouter(routes),
      provideHttpClient(withXhr(), withInterceptors([authInterceptor])),
      provideOAuthClient()
    ]
  });
}

startApplication().catch((error: unknown) => {
  console.error('Swirl Demo App startup failed', error);
  const root = document.querySelector('app-root');
  if (root) {
    root.setAttribute('role', 'alert');
    root.textContent = 'Swirl Demo App could not start. Please reload or contact your administrator.';
  }
});
