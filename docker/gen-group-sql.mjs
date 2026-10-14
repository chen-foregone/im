/**
 * 生成「五百人群 / 两千人群 / 五千人群」的测试数据 SQL。
 *
 * 为什么用 Node 而不是 PowerShell：
 *   Windows PowerShell 5.1 读取 .ps1 文件时，若无 BOM 会按 GBK 解码，
 *   脚本里的中文（群名）会变成乱码并直接导致语法错误。
 *   Node 的源码按 UTF-8 读取，没有这个问题。
 *
 * 号段接在官方脚本之后，不冲突：
 *   十人群 x1000 id    1 ~ 1000     千人群 x10 id 1101 ~ 1110
 *   百人群 x100   id 1001 ~ 1100    万人群 x10 id 1111 ~ 1120
 *   五百人群 x10 id 1121 ~ 1130     两千人群 x10 id 1131 ~ 1140
 *   五千人群 x10 id 1141 ~ 1150
 *
 * 用法： node gen-group-sql.mjs
 */
import { writeFileSync, mkdirSync, statSync, readFileSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, '..', 'box-im-simulator', 'db');
mkdirSync(outDir, { recursive: true });

const specs = [
  { size: 500,  cn: '五百人群', fileNo: 8,  startId: 1121 },
  { size: 2000, cn: '两千人群', fileNo: 9,  startId: 1131 },
  { size: 5000, cn: '五千人群', fileNo: 10, startId: 1141 },
];
const GROUP_COUNT = 10;

console.log('输出目录: ' + outDir + '\n');

for (const { size, cn, fileNo, startId } of specs) {
  const lastId = startId + GROUP_COUNT - 1;
  const memberRows = GROUP_COUNT * size;
  const out = [];

  out.push(
    '-- ' + GROUP_COUNT + ' 个' + cn + '：群 id ' + startId + '~' + lastId + '。群名 ' + cn + '1 ~ ' + cn + GROUP_COUNT + '。',
    '-- 每个群的成员均为 user 1~' + size + '。每群 ' + size + ' 人，共 ' + memberRows + ' 行 im_group_member。',
    '-- 群主：' + cn + 'k -> owner_id = k。需已存在 im_user id 1~' + size + '。version 1~' + memberRows + '。',
    '-- 由 docker/gen-group-sql.mjs 生成，请勿手工编辑。',
    '',
    'INSERT INTO im_group (id, name, owner_id) VALUES',
  );
  const groups = [];
  for (let g = 0; g < GROUP_COUNT; g++) {
    groups.push('(' + (startId + g) + ", '" + cn + (g + 1) + "', " + (g + 1) + ')');
  }
  out.push(groups.join(',\n') + ';', '', 'INSERT INTO im_group_member (group_id, user_id, user_nick_name, version) VALUES');

  const rows = [];
  let ver = 0;
  const nick = new Array(10001);
  for (let u = 1; u <= size; u++) nick[u] = 'TT' + String(u - 1).padStart(4, '0');
  for (let g = 0; g < GROUP_COUNT; g++) {
    const gid = startId + g;
    for (let u = 1; u <= size; u++) {
      ver++;
      rows.push('(' + gid + ', ' + u + ", '" + nick[u] + "', " + ver + ')');
    }
  }
  out.push(rows.join(',\n') + ';', '', 'ALTER TABLE im_group AUTO_INCREMENT = ' + (lastId + 1) + ';', '');

  const file = join(outDir, fileNo + '.创建' + GROUP_COUNT + '个' + cn + '.sql');
  writeFileSync(file, out.join('\n'), 'utf8');   // 无 BOM
  const kb = (statSync(file).size / 1024).toFixed(0);
  console.log('  [OK] ' + file.split('\\').pop() + '  (' + memberRows.toLocaleString() + ' 行, ' + kb + ' KB)');
}

console.log('\n完成。请先导入 db/汇总.sql，再依次导入以上三个文件。');
