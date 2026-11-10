import type { Page } from '@playwright/test';

/**
 * I-01 and I-02 are drawn as Wireframes v6.1 has them (OD-121): the month grid and the slip entry
 * stay whole in a closed section. A functional spec opens the section before it picks a gang.
 */
export async function openCapture(page: Page, screen: 'I-01' | 'I-02') {
  const section = page.getByTestId(screen === 'I-01' ? 'i01-grid' : 'i02-capture');
  if (!(await section.evaluate((d) => (d as HTMLDetailsElement).open)))
    await section.locator('summary').first().click();
}
