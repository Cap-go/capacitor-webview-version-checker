#!/usr/bin/env node
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const repoRoot = resolve(import.meta.dirname, '..');
const exampleDir = join(repoRoot, 'example-app');
function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    throw new Error(command + ' ' + args.join(' ') + ' failed with status ' + (result.status ?? 'unknown'));
  }
}
const originalLock = existsSync(exampleLockPath) ? readFileSync(exampleLockPath, 'utf8') : undefined;
const packDir = mkdtempSync(join(tmpdir(), 'capgo-example-pack-'));

function run(command, args, cwd) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.status !== 0) process.exit(result.status ?? 1);
}

try {
  run('bun', ['pm', 'pack', '--destination', packDir, '--quiet'], repoRoot);
  const tarball = readdirSync(packDir).find((file) => file.endsWith('.tgz'));
  if (!tarball) throw new Error('No package tarball was created.');
  run('bun', ['install'], exampleDir);
  run('bun', ['remove', packageJson.name], exampleDir);
  run('bun', ['add', join(packDir, tarball)], exampleDir);
} finally {
  writeFileSync(examplePackagePath, originalPackage);
  if (originalLock !== undefined) writeFileSync(exampleLockPath, originalLock);
  rmSync(packDir, { recursive: true, force: true });
}
