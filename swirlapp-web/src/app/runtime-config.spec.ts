import { describe, expect, it, vi } from 'vitest';
import { loadRuntimeConfig, validateRuntimeConfig } from './runtime-config';

const identity = {
  keycloakUrl: 'https://login.example.test/auth',
  keycloakRealm: 'people',
  keycloakClientId: 'swirlapp-web'
};
const published = { ...identity, apiDocsEnabled: true };

describe('runtime configuration', () => {
  it('accepts another installation without changing the application code', () => {
    expect(validateRuntimeConfig(identity, 'https://portal.example.test')).toEqual(published);
    const next = { ...identity, keycloakUrl: 'https://identity.example.org/auth', keycloakRealm: 'staff' };
    expect(validateRuntimeConfig(next, 'https://app.example.org')).toEqual({ ...next, apiDocsEnabled: true });
  });

  it('lets an environment hide the API documentation link', () => {
    expect(validateRuntimeConfig({ ...identity, apiDocsEnabled: false }, 'https://portal.example.test'))
      .toEqual({ ...identity, apiDocsEnabled: false });
  });

  it('resolves the local Compose identity against the browser origin', () => {
    expect(validateRuntimeConfig({ ...identity, keycloakUrl: '/auth' }, 'http://web:8080'))
      .toEqual({ ...published, keycloakUrl: 'http://web:8080/auth' });
  });

  it.each([
    null,
    [],
    {},
    { ...identity, authEnabled: false },
    { ...identity, apiDocsEnabled: 'false' },
    { ...identity, keycloakRealm: '' },
    { ...identity, keycloakRealm: '../other' },
    { ...identity, keycloakClientId: 17 },
    { ...identity, keycloakUrl: 'javascript:alert(1)' },
    { ...identity, keycloakUrl: 'https://user:password@login.example.test/auth' },
    { ...identity, keycloakUrl: 'https://login.example.test/auth?realm=other' },
    { ...identity, keycloakUrl: 'https://login.example.test/auth#other' },
    { ...identity, keycloakUrl: 'http://login.example.test/auth' }
  ])('rejects invalid or insecure public configuration: %j', value => {
    expect(() => validateRuntimeConfig(value, 'https://portal.example.test')).toThrow();
  });

  it('loads configuration without cache or redirect fallback', async () => {
    const fetchConfig = vi.fn<typeof fetch>().mockResolvedValue(new Response(JSON.stringify(identity)));
    await expect(loadRuntimeConfig('https://portal.example.test', fetchConfig)).resolves.toEqual(published);
    expect(fetchConfig).toHaveBeenCalledWith('/runtime-config.json', expect.objectContaining({
      cache: 'no-store', credentials: 'same-origin', redirect: 'error', signal: expect.any(AbortSignal)
    }));
  });

  it('does not substitute defaults when configuration is missing', async () => {
    const fetchConfig = vi.fn<typeof fetch>().mockResolvedValue(new Response('', { status: 404 }));
    await expect(loadRuntimeConfig('https://portal.example.test', fetchConfig)).rejects.toThrow('(404)');
  });

  it('rejects a successful HTML response instead of bootstrapping from the SPA fallback', async () => {
    const fetchConfig = vi.fn<typeof fetch>().mockResolvedValue(new Response('<html></html>'));
    await expect(loadRuntimeConfig('https://portal.example.test', fetchConfig)).rejects.toThrow();
  });
});
