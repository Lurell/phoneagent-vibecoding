// Minimal ELF64 inspector: program headers (PT_LOAD alignment, PT_INTERP) + dynamic section.
// Written because this machine has no readelf/objdump. Node only.
import { readFileSync } from 'fs';

const PT_LOAD = 1, PT_DYNAMIC = 2, PT_INTERP = 3;
const DT_NEEDED = 1, DT_STRTAB = 5, DT_STRSZ = 10, DT_SONAME = 14, DT_RPATH = 15, DT_RUNPATH = 29;
const ET = { 0: 'NONE', 1: 'REL', 2: 'ET_EXEC', 3: 'ET_DYN' };

function cstr(buf, off) {
  let end = off;
  while (end < buf.length && buf[end] !== 0) end++;
  return buf.subarray(off, end).toString('utf8');
}

function inspect(path) {
  const b = readFileSync(path);
  const out = { path };

  if (b.subarray(0, 4).toString('binary') !== '\x7fELF') throw new Error(`${path}: not an ELF file`);
  out.class = b[4] === 2 ? 'ELF64' : 'ELF32';
  out.endian = b[5] === 1 ? 'little' : 'big';
  if (out.class !== 'ELF64' || out.endian !== 'little') throw new Error('only ELF64 LE supported');

  const e_type = b.readUInt16LE(16);
  const e_machine = b.readUInt16LE(18);
  const e_phoff = Number(b.readBigUInt64LE(32));
  const e_phentsize = b.readUInt16LE(54);
  const e_phnum = b.readUInt16LE(56);

  out.type = ET[e_type] ?? e_type;
  out.machine = e_machine === 183 ? 'aarch64' : `0x${e_machine.toString(16)}`;

  const phdrs = [];
  for (let i = 0; i < e_phnum; i++) {
    const o = e_phoff + i * e_phentsize;
    phdrs.push({
      type: b.readUInt32LE(o),
      offset: Number(b.readBigUInt64LE(o + 8)),
      vaddr: Number(b.readBigUInt64LE(o + 16)),
      filesz: Number(b.readBigUInt64LE(o + 32)),
      memsz: Number(b.readBigUInt64LE(o + 40)),
      align: Number(b.readBigUInt64LE(o + 48)),
    });
  }

  // PT_INTERP -> the program interpreter the kernel must load
  const interp = phdrs.find(p => p.type === PT_INTERP);
  out.interp = interp ? cstr(b, interp.offset) : '(none - static or ET_EXEC)';

  // PT_LOAD alignment — this is the Android 15+ 16 KB page-size check
  out.loads = phdrs.filter(p => p.type === PT_LOAD).map(p => ({ vaddr: '0x' + p.vaddr.toString(16), align: '0x' + p.align.toString(16) }));
  out.minAlign = Math.min(...phdrs.filter(p => p.type === PT_LOAD).map(p => p.align));

  // Dynamic section
  const dyn = phdrs.find(p => p.type === PT_DYNAMIC);
  if (dyn) {
    const map = v => { const s = phdrs.find(p => p.type === PT_LOAD && v >= p.vaddr && v < p.vaddr + p.memsz); return s ? s.offset + (v - s.vaddr) : -1; };
    const entries = [];
    for (let o = dyn.offset; o < dyn.offset + dyn.filesz; o += 16) {
      entries.push([Number(b.readBigInt64LE(o)), Number(b.readBigInt64LE(o + 8))]);
    }
    const strtabV = entries.find(e => e[0] === DT_STRTAB)?.[1];
    const strOff = strtabV != null ? map(strtabV) : -1;
    const dtStr = (v) => (strOff >= 0 ? cstr(b, strOff + v) : `#${v}`);
    out.needed = entries.filter(e => e[0] === DT_NEEDED).map(e => dtStr(e[1]));
    const rp = entries.find(e => e[0] === DT_RPATH);
    const rn = entries.find(e => e[0] === DT_RUNPATH);
    out.rpath = rp ? dtStr(rp[1]) : null;
    out.runpath = rn ? dtStr(rn[1]) : null;
    const sn = entries.find(e => e[0] === DT_SONAME);
    out.soname = sn ? dtStr(sn[1]) : null;
  } else {
    out.needed = [];
    out.note = 'static (no PT_DYNAMIC)';
  }

  out.page16kOK = out.minAlign >= 0x4000;
  return out;
}

for (const p of process.argv.slice(2)) {
  try {
    const r = inspect(p);
    console.log(`\n=== ${r.path}`);
    console.log(`  ${r.class} ${r.endian}  ${r.type}  ${r.machine}`);
    console.log(`  PT_INTERP   : ${r.interp}`);
    console.log(`  DT_NEEDED   : ${r.needed.length ? r.needed.join(', ') : '(none)'}`);
    if (r.soname) console.log(`  DT_SONAME   : ${r.soname}`);
    if (r.runpath) console.log(`  DT_RUNPATH  : ${r.runpath}`);
    if (r.rpath) console.log(`  DT_RPATH    : ${r.rpath}`);
    console.log(`  LOAD aligns : ${r.loads.map(l => l.align).join(' ')}`);
    console.log(`  16KB check  : ${r.page16kOK ? 'PASS' : '*** FAIL ***'} (min align ${'0x' + r.minAlign.toString(16)})`);
  } catch (e) {
    console.log(`\n=== ${p}\n  ERROR: ${e.message}`);
  }
}
