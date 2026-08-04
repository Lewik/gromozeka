import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

const manifest = JSON.parse(
  readFileSync(new URL('../manifest.json', import.meta.url), 'utf8'),
) as { key: string };

describe('manifest identity', () => {
  it('keeps the stable Gromozeka extension id separate from Playwright', () => {
    const digest = createHash('sha256')
      .update(Buffer.from(manifest.key, 'base64'))
      .digest()
      .subarray(0, 16);
    const extensionId = [...digest]
      .flatMap(byte => [byte >> 4, byte & 0x0f])
      .map(nibble => String.fromCharCode('a'.charCodeAt(0) + nibble))
      .join('');

    expect(extensionId).toBe('jiadoiohindhpbaahcahcbeokjiojlml');
    expect(extensionId).not.toBe('mmlmfjhmonkocbjadbfplnigmagldckm');
  });
});
