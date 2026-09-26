#!/usr/bin/env node
//
// 从 .deb 里取出一个成员（例如 data.tar.xz）。
//
// 为什么要自己写：.deb 是 Unix 的 ar 归档，标准工具是 `ar`（binutils）。
// 但 Windows 上的 Git Bash 不带 binutils，`ar`、`readelf`、`objdump` 全部缺失。
// ar 格式非常简单且固定（8 字节魔数 + 每成员 60 字节头 + 2 字节对齐），
// 直接解析比让用户去装 binutils 更可靠。
//
// 用法: node deb-extract.mjs <输入.deb> <成员名> <输出文件>
//   成员名支持精确匹配，也支持前缀匹配（如 "data.tar" 会匹配 data.tar.xz）
//
import { readFileSync, writeFileSync } from 'node:fs';

const [debPath, memberName, outPath] = process.argv.slice(2);

if (!debPath || !memberName || !outPath) {
  console.error('用法: node deb-extract.mjs <输入.deb> <成员名> <输出文件>');
  process.exit(2);
}

const buf = readFileSync(debPath);
const MAGIC = '!<arch>\n';

if (buf.subarray(0, MAGIC.length).toString('binary') !== MAGIC) {
  console.error(`错误: ${debPath} 不是 ar 归档（缺少魔数 "!<arch>"）`);
  process.exit(1);
}

// ar 全局头之后是一串成员，每个成员前面有 60 字节的 ASCII 头：
//   0..15   名字（GNU 风格以 '/' 结尾，需去掉）
//   16..27  修改时间
//   28..33  属主 uid
//   34..39  属组 gid
//   40..47  权限
//   48..57  长度（十进制 ASCII）
//   58..59  结束标记 "`\n"
const HEADER_SIZE = 60;
let off = MAGIC.length;

while (off + HEADER_SIZE <= buf.length) {
  const header = buf.subarray(off, off + HEADER_SIZE);

  if (header.subarray(58, 60).toString('binary') !== '`\n') {
    console.error(`错误: 偏移 ${off} 处的成员头格式非法`);
    process.exit(1);
  }

  const name = header.subarray(0, 16).toString('ascii').trim().replace(/\/$/, '');
  const size = parseInt(header.subarray(48, 58).toString('ascii').trim(), 10);

  if (!Number.isFinite(size) || size < 0) {
    console.error(`错误: 成员 ${name} 的长度字段无法解析`);
    process.exit(1);
  }

  const dataStart = off + HEADER_SIZE;

  if (name === memberName || name.startsWith(memberName)) {
    writeFileSync(outPath, buf.subarray(dataStart, dataStart + size));
    console.log(`  提取 ${name} (${size} 字节) -> ${outPath}`);
    process.exit(0);
  }

  // 成员之间按 2 字节对齐
  off = dataStart + size + (size % 2);
}

console.error(`错误: 在 ${debPath} 中找不到成员 "${memberName}"`);
process.exit(1);
