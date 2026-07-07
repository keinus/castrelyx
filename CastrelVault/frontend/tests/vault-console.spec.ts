import { expect, test } from '@playwright/test';
import { setupVaultRoutes } from './fixtures';

test.beforeEach(async ({ page }) => {
  await setupVaultRoutes(page);
});

test('admin can manage secrets and reveal plaintext only in the reveal modal', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('ready-password-123');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();
  await page.getByRole('button', { name: 'Secrets' }).click();
  await expect(page.getByRole('heading', { name: 'Secrets' })).toBeVisible();
  await expect(page.getByText('known-api-token-value-987654')).toHaveCount(0);

  await page.getByLabel('Path').fill('/apps/reporting/api-token');
  await page.getByLabel('Display name').fill('Reporting API token');
  await page.getByLabel('Secret value').fill('created-secret-value-123');
  await page.getByRole('button', { name: 'Create secret' }).click();
  await expect(page.getByRole('table').getByText('/apps/reporting/api-token', { exact: true })).toBeVisible();
  await expect(page.getByText('created-secret-value-123')).toHaveCount(0);

  await page.getByRole('button', { name: 'Reveal' }).click();
  await page.getByLabel('Admin password').fill('ready-password-123');
  await page.getByLabel('Reason').fill('maintenance');
  await page.locator('.modal').getByRole('button', { name: 'Reveal' }).click();
  await expect(page.getByText('known-api-token-value-987654')).toBeVisible();
  await page.getByRole('button', { name: 'Close' }).click();

  await page.getByRole('button', { name: 'Audit' }).click();
  await expect(page.getByRole('heading', { name: 'Audit', exact: true })).toBeVisible();
  await expect(page.getByText('REVEAL_SECRET ALLOWED')).toBeVisible();
  await expect(page.getByText('known-api-token-value-987654')).toHaveCount(0);
});

test('admin can manage application principals and migration from the Vault console', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('Username').fill('admin');
  await page.getByLabel('Password').fill('ready-password-123');
  await page.getByRole('button', { name: 'Sign in' }).click();

  await page.getByRole('button', { name: 'Applications' }).click();
  await expect(page.getByRole('heading', { name: 'Applications' })).toBeVisible();
  await expect(page.getByRole('table').getByText('manager-app')).toBeVisible();
  await page.getByRole('button', { name: 'One-use token' }).click();
  await expect(page.getByText('one-use-token')).toBeVisible();

  await page.getByRole('button', { name: 'Migration' }).click();
  await expect(page.getByRole('heading', { name: 'Manager Migration' })).toBeVisible();
  await page.getByRole('button', { name: 'Dry-run' }).click();
  await expect(page.getByText('/manager/integrations/castrelsign/secret')).toBeVisible();
  await page.getByRole('button', { name: 'Run migration' }).click();
  await expect(page.getByText('Migrated 1 integration and 1 SNMP secrets')).toBeVisible();
});
