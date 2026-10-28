// Runs the face harness: the phone's own Kotlin face engine on a JVM, with the browser for a camera.
//
//   node tools/face-harness/run.mjs              compile if needed, then serve http://localhost:8765
//   node tools/face-harness/run.mjs --rebuild     compile again even if the jar looks current
//   node tools/face-harness/run.mjs --check       compile only, print the result, exit
//
// Nothing here touches the network except fetching the compiler jars, and the frames the page sends
// stay in this process's memory.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(here, '..', '..');
const cache = path.join(here, '.cache');
const engine = path.join(repoRoot, 'app/src/main/java/com/vsk/attendance/face');
const jar = path.join(cache, 'face-harness.jar');
const stampFile = path.join(cache, 'compiled-sources.json');
const portArgIndex = process.argv.indexOf('--port');
const port = (portArgIndex >= 0 ? process.argv[portArgIndex + 1] : process.env.FACE_HARNESS_PORT) || '8765';
if (!/^\d+$/.test(port)) {
  console.error(`--port wants a number, got ${JSON.stringify(port)}`);
  process.exit(1);
}

// The same JDK the app's JVM test validators use. Change it with JAVA_HOME if this machine moves.
const javaHome = process.env.FACE_HARNESS_JDK || 'C:/Program Files/Digital Signer Service/7.0.6/jre64';
const java = path.join(javaHome, 'bin/java.exe');

// kotlin-compiler-embeddable needs its own helpers on the classpath; -cp for the sources only needs
// stdlib and the serialization runtime, because the harness deliberately excludes the two files that
// import Android and MediaPipe.
const TOOLS = [
  ['org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.0.21', 65_000_000],
  ['org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21', 3_000_000],
  ['org.jetbrains.kotlin', 'kotlin-script-runtime', '2.0.21', 200_000],
  ['org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10', 4_000_000],
  ['org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.0.21', 1_000_000],
  ['org.jetbrains.intellij.deps', 'trove4j', '1.0.20200330', 1_000_000],
  ['org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.6.4', 2_000_000],
  ['org.jetbrains', 'annotations', '13.0', 100_000],
  ['org.jetbrains.kotlin', 'kotlin-serialization-compiler-plugin-embeddable', '2.0.21', 2_000_000],
];
const LIBS = [
  ['org.jetbrains.kotlin', 'kotlin-stdlib', '2.0.21', 3_000_000],
  ['org.jetbrains.kotlinx', 'kotlinx-serialization-core-jvm', '1.7.3', 1_000_000],
  ['org.jetbrains.kotlinx', 'kotlinx-serialization-json-jvm', '1.7.3', 1_000_000],
  ['org.jetbrains', 'annotations', '13.0', 100_000],
];
// The seven engine files are the ones with no Android imports, so they compile and run here exactly
// as they do in the APK. FaceGuide.kt and FaceCaptureCard.kt are the browser's job instead.
const SOURCES = [
  'FaceTemplate.kt', 'FaceInstruction.kt', 'FaceGeometry.kt', 'BlinkTracker.kt',
  'FaceCheckSession.kt', 'FaceMeshSignature.kt', 'FaceFrameQuality.kt',
].map(f => path.join(engine, f)).concat(path.join(here, 'FaceHarness.kt'));

const mavenBase = 'https://repo.maven.apache.org/maven2/';

async function get(url, limit) {
  const res = await fetch(url, { signal: AbortSignal.timeout(180_000) });
  if (!res.ok) throw new Error(`${res.status} ${url}`);
  if (Number(res.headers.get('content-length')) > limit) throw new Error(`Oversize ${url}`);
  const parts = [];
  let count = 0;
  for await (const part of res.body) {
    count += part.length;
    if (count > limit) throw new Error(`Exceeded limit ${url}`);
    parts.push(part);
  }
  return Buffer.concat(parts);
}

/** Prefer the already-verified jars from the JVM test validators; download only what is missing. */
async function ensure([group, artifact, version, limit]) {
  const name = `${artifact}-${version}.jar`;
  const target = path.join(cache, name);
  if (fs.existsSync(target)) return target;
  const borrowed = path.join(
    process.env.TEMP || process.env.TMP || '', 'vsk-face-tools/kotlin-jvm-validation', name
  );
  if (borrowed.includes('vsk-face-tools') && fs.existsSync(borrowed)) {
    fs.copyFileSync(borrowed, target);
  } else {
    const url = mavenBase + `${group.replaceAll('.', '/')}/${artifact}/${version}/${name}`;
    fs.writeFileSync(target, await get(url, limit));
  }
  const sha1 = (await get(mavenBase + `${group.replaceAll('.', '/')}/${artifact}/${version}/${name}.sha1`, 2000))
    .toString().trim().split(/\s+/)[0];
  const actual = createHash('sha1').update(fs.readFileSync(target)).digest('hex');
  if (sha1 && actual !== sha1) throw new Error(`Checksum mismatch: ${name}`);
  console.log(`jar ${name}: ${actual} Maven SHA-1 verified`);
  return target;
}

function sourceStamp() {
  return Object.fromEntries(SOURCES.map(f => [
    path.basename(f),
    createHash('sha256').update(fs.readFileSync(f)).digest('hex'),
  ]));
}

async function compile() {
  fs.mkdirSync(cache, { recursive: true });
  const toolCp = (await Promise.all(TOOLS.map(ensure))).join(path.delimiter);
  const libCp = (await Promise.all(LIBS.map(ensure))).join(path.delimiter);
  const plugin = path.join(cache, 'kotlin-serialization-compiler-plugin-embeddable-2.0.21.jar');
  const args = [
    '-Xmx768m', '-cp', toolCp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-classpath', libCp,
    `-Xplugin=${plugin}`, '-d', jar, ...SOURCES,
  ];
  const shown = args.map(a => a.split(cache + path.sep).join('.cache' + path.sep));
  console.log(`${path.basename(java)} ${shown.join(' ')}`);
  const built = spawnSync(java, args, { encoding: 'utf8', timeout: 420_000, maxBuffer: 8_000_000 });
  const output = `${built.stdout ?? ''}${built.stderr ?? ''}`;
  // Warnings that matter here are the ones that mean an engine file was not what it looked like.
  const noisy = output.split(/\r?\n/).filter(l =>
    l.trim() && !/warning: /i.test(l) && !/^logging: /i.test(l)
  );
  if (noisy.length) console.log(noisy.join('\n'));
  if (built.status !== 0) throw new Error(`Kotlin compilation failed (exit ${built.status})`);
  fs.writeFileSync(stampFile, JSON.stringify({ stamp: sourceStamp(), compiledAt: new Date().toISOString() }, null, 2));
  console.log(`compiled ${path.relative(repoRoot, jar)} (${fs.statSync(jar).size} bytes)`);
}

function needsCompile() {
  if (process.argv.includes('--rebuild') || !fs.existsSync(jar)) return true;
  try {
    const previous = JSON.parse(fs.readFileSync(stampFile, 'utf8')).stamp;
    const now = sourceStamp();
    return JSON.stringify(previous) !== JSON.stringify(now);
  } catch {
    return true;
  }
}

if (!fs.existsSync(java)) {
  console.error(`No JDK at ${javaHome}. Run with FACE_HARNESS_JDK=<path to a JDK 17+ home>.`);
  process.exit(1);
}
const missing = SOURCES.filter(f => !fs.existsSync(f));
if (missing.length) {
  console.error(`Missing sources:\n${missing.join('\n')}`);
  process.exit(1);
}

if (needsCompile()) await compile();
else console.log(`jar is current (${path.relative(repoRoot, jar)}); use --rebuild to force`);

if (process.argv.includes('--check')) {
  console.log('Compile check only. No server started.');
  process.exit(0);
}

const runtimeCp = [jar, ...(await Promise.all(LIBS.map(ensure)))].join(path.delimiter);
console.log(`Face harness: http://localhost:${port}`);
const served = spawnSync(java, ['-cp', runtimeCp, 'com.vsk.attendance.face.FaceHarnessKt', port, repoRoot], {
  stdio: 'inherit',
});
process.exit(served.status ?? 1);
