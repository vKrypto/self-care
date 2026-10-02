import {createHash} from 'node:crypto';
import {constants, createReadStream} from 'node:fs';
import {copyFile, lstat, mkdir, mkdtemp, readFile, rename, rm, writeFile} from 'node:fs/promises';
import {basename, dirname, join, resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

const nativeAppDirectory = dirname(dirname(fileURLToPath(import.meta.url)));
const supportedAbis = new Set(['arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64']);

async function sha256(path) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(path)) { hash.update(chunk); }
  return hash.digest('hex');
}

/** Gradle metadata determines which APKs exist; directory overrides support isolated verification. */
export async function exportApks({variant, apkDirectory, destinationDirectory} = {}) {
  if (variant !== 'lan' && variant !== 'preview') {
    throw new Error('Choose the APK variant: lan or preview.');
  }
  const sourceDirectory = resolve(apkDirectory ?? join(nativeAppDirectory, 'android/app/build/outputs/apk', variant));
  const exportDirectory = resolve(destinationDirectory ?? join(nativeAppDirectory, '../apk'));
  const metadataPath = join(sourceDirectory, 'output-metadata.json');
  let metadata;
  try {
    const file = await lstat(metadataPath);
    if (!file.isFile() || file.isSymbolicLink()) { throw new Error('Not a metadata file.'); }
    metadata = JSON.parse(await readFile(metadataPath, 'utf8'));
  } catch {
    throw new Error(`Cannot read Gradle APK metadata at ${metadataPath}. Build the ${variant} APK first.`);
  }
  if (metadata?.artifactType?.type !== 'APK' || metadata.variantName !== variant ||
      metadata.applicationId !== 'com.forma.datasync.preview' || !Array.isArray(metadata.elements) || metadata.elements.length === 0) {
    throw new Error('Gradle metadata does not describe APKs for the requested preview variant.');
  }

  const filenames = new Set();
  const destinations = new Set();
  const prefix = `forma-data-sync-${variant === 'lan' ? 'lan-' : ''}preview`;
  const planned = [];
  for (const element of metadata.elements) {
    const filename = element?.outputFile;
    if (typeof filename !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$/.test(filename) || filenames.has(filename)) {
      throw new Error('Gradle metadata contains an invalid or duplicate APK filename.');
    }
    filenames.add(filename);
    if (!Array.isArray(element.filters) || element.filters.length > 1) {
      throw new Error('Only universal and single ABI APK outputs are supported.');
    }
    let abi = null;
    if (element.filters.length === 1) {
      const filter = element.filters[0];
      if (filter?.filterType !== 'ABI' || !supportedAbis.has(filter.value)) {
        throw new Error('Gradle metadata contains an unsupported APK filter or ABI.');
      }
      abi = filter.value;
    }
    const destinationName = `${prefix}${abi ? `-${abi}` : ''}.apk`;
    if (destinations.has(destinationName)) { throw new Error('Gradle metadata contains duplicate universal or ABI APK outputs.'); }
    destinations.add(destinationName);
    const sourcePath = join(sourceDirectory, filename);
    let source;
    try { source = await lstat(sourcePath); }
    catch { throw new Error(`The APK listed by Gradle is missing: ${filename}. Rebuild the ${variant} APK.`); }
    if (!source.isFile() || source.isSymbolicLink() || source.size === 0) {
      throw new Error(`The APK listed by Gradle is not a regular nonempty file: ${filename}.`);
    }
    planned.push({abi, sourcePath, destinationName, bytes: source.size});
  }

  // Validate every source before touching published files, then stage and verify
  // all copies on the destination filesystem so each rename is atomic.
  await mkdir(exportDirectory, {recursive: true});
  const stagingDirectory = await mkdtemp(join(exportDirectory, '.export-apks-'));
  try {
    for (const entry of planned) {
      entry.sha256 = await sha256(entry.sourcePath);
      const stagedApk = join(stagingDirectory, entry.destinationName);
      await copyFile(entry.sourcePath, stagedApk, constants.COPYFILE_EXCL);
      if (await sha256(stagedApk) !== entry.sha256) { throw new Error(`APK copy verification failed: ${entry.destinationName}.`); }
      await writeFile(`${stagedApk}.sha256`, `${entry.sha256}  ${entry.destinationName}\n`, {flag: 'wx'});
    }
    for (const entry of planned) {
      entry.file = join(exportDirectory, entry.destinationName);
      await rename(join(stagingDirectory, entry.destinationName), entry.file);
      await rename(join(stagingDirectory, `${entry.destinationName}.sha256`), `${entry.file}.sha256`);
    }
    return planned.map(({abi, file, bytes, sha256: digest}) => ({abi, file, bytes, sha256: digest}));
  } finally {
    await rm(stagingDirectory, {recursive: true, force: true});
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    if (process.argv.length !== 3) { throw new Error('Usage: node scripts/export-apks.mjs <lan|preview>'); }
    for (const result of await exportApks({variant: process.argv[2]})) {
      console.log(`Exported ${basename(result.file)} (${(result.bytes / 1_048_576).toFixed(1)} MiB) with SHA-256 checksum.`);
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
