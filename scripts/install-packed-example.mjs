#!/usr/bin/env node
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const repoRoot = resolve(import.meta.dirname, '..');
const exampleDir = join(repoRoot, 'example-app');
const packageJson = JSON.parse(readFileSync(join(repoRoot, 'package.json'), 'utf8'));
const examplePackagePath = join(exampleDir, 'package.json');
const exampleLockPath = join(exampleDir, 'bun.lock');
const originalPackage = readFileSync(examplePackagePath, 'utf8');
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
