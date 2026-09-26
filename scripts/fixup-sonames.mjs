#!/usr/bin/env node
//
// 给一个目录里的共享库按 DT_SONAME 补齐文件名。
//
// 为什么需要：Linux 发行版的包里，一个库通常有两份名字 ——
//   libz.so.1.3.2   （真实文件）
//   libz.so.1       （指向它的符号链接，SONAME）
// 而动态链接器只认 SONAME。在 Windows 上解包时符号链接会丢失，
// 结果就是 "library libz.so.1 not found"。
//
// 这里直接把真实文件复制成 SONAME 名字（容器里只有我们自己的库，
// 空间换简单，不做软链 —— 软链在跨文件系统搬运时还会再丢一次）。
//
// 用法: node fixup-sonames.mjs <库目录>
//
import { readdirSync, readFileSync, copyFileSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2];
if (!dir) {
  console.error('用法: node fixup-sonames.mjs <库目录>');
  process.exit(2);
}

function sonameOf(path) {
  const b = readFileSync(path);
  if (b.subarray(0, 4).toString('binary') !== '\x7fELF') return null;
  if (b[4] !== 2) return null; // 只处理 ELF64

  const e_phoff = Number(b.readBigUInt64LE(32));
  const e_phentsize = b.readUInt16LE(54);
  const e_phnum = b.readUInt16LE(56);

  const phdrs = [];
  for (let i = 0; i < e_phnum; i++) {
    const o = e_phoff + i * e_phentsize;
    phdrs.push({
      type: b.readUInt32LE(o),
      offset: Number(b.readBigUInt64LE(o + 8)),
      vaddr: Number(b.readBigUInt64LE(o + 16)),
      filesz: Number(b.readBigUInt64LE(o + 32)),
      memsz: Number(b.readBigUInt64LE(o + 40)),
    });
  }

  const dyn = phdrs.find((p) => p.type === 2); // PT_DYNAMIC
  if (!dyn) return null;

  const map = (v) => {
    const s = phdrs.find((p) => p.type === 1 && v >= p.vaddr && v < p.vaddr + p.memsz);
    return s ? s.offset + (v - s.vaddr) : -1;
  };

  const entries = [];
  for (let o = dyn.offset; o < dyn.offset + dyn.filesz; o += 16) {
    entries.push([Number(b.readBigInt64LE(o)), Number(b.readBigInt64LE(o + 8))]);
  }

  const strtabV = entries.find((e) => e[0] === 5)?.[1];      // DT_STRTAB
  const sonameV = entries.find((e) => e[0] === 14)?.[1];     // DT_SONAME
  if (strtabV == null || sonameV == null) return null;

  const strOff = map(strtabV);
  if (strOff < 0) return null;

  let end = strOff + sonameV;
  while (end < b.length && b[end] !== 0) end++;
  return b.subarray(strOff + sonameV, end).toString('utf8');
}

let fixed = 0;
let skipped = 0;
for (const name of readdirSync(dir)) {
  const path = join(dir, name);
  let soname;
  try {
    soname = sonameOf(path);
  } catch {
    continue;
  }
  if (!soname || soname === name) {
    skipped++;
    continue;
  }
  const target = join(dir, soname);
  if (existsSync(target)) {
    skipped++;
    continue;
  }
  copyFileSync(path, target);
  console.log(`  ${name}  ->  ${soname}`);
  fixed++;
}

console.log(`\n补齐 ${fixed} 个 SONAME 名字，跳过 ${skipped} 个`);
