import { registerPlugin } from '@capacitor/core';

import type { WebviewVersionCheckerPlugin } from './definitions';

const WebviewVersionChecker = registerPlugin<WebviewVersionCheckerPlugin>('WebviewVersionChecker', {
  web: () => import('./web').then((m) => new m.WebviewVersionCheckerWeb()),
});

export * from './definitions';
export { WebviewVersionChecker };
