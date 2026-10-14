import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { AppComponent } from './app.component';
import { RUNTIME_CONFIG, type RuntimeConfig } from './runtime-config';
import { AuthService } from './services/auth.service';

const identity = { keycloakUrl: 'https://login.example.test/auth', keycloakRealm: 'people', keycloakClientId: 'web' };

describe('AppComponent', () => {
  const authService = { isLoggedIn$: of(true), logout: vi.fn() };

  beforeEach(() => authService.logout.mockReset());

  function render(config: RuntimeConfig | null): HTMLElement {
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authService },
        { provide: RUNTIME_CONFIG, useValue: config }
      ]
    });
    const fixture = TestBed.createComponent(AppComponent);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('links to the API documentation when the environment publishes it', () => {
    expect(render({ ...identity, apiDocsEnabled: true }).querySelector('a.nav-docs')?.getAttribute('href')).toBe('/api/docs');
  });

  it('links to the API documentation in local development without runtime configuration', () => {
    expect(render(null).querySelector('a.nav-docs')).not.toBeNull();
  });

  it('hides the API documentation link when the environment disables it', () => {
    expect(render({ ...identity, apiDocsEnabled: false }).querySelector('a.nav-docs')).toBeNull();
  });

  it('signs out through the authentication service', () => {
    (render(null).querySelector('button.logout-button') as HTMLButtonElement).click();
    expect(authService.logout).toHaveBeenCalled();
  });
});
