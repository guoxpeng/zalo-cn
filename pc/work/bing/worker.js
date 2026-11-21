// usage: node worker.js <in.json> <out.json>
// in.json : [text,...] ; out.json : [{"text":..,"zh":..|null},...]
const { translate } = require('bing-translate-api');
const fs = require('fs');

const [inFile, outFile] = process.argv.slice(2);
const texts = JSON.parse(fs.readFileSync(inFile, 'utf8'));
const CONC = 8;

async function one(text) {
  try {
    const r = await translate(text, 'vi', 'zh-Hans');
    return { text, zh: r.translation || null };
  } catch (e) {
    return { text, zh: null };
  }
}

(async () => {
  const results = [];
  let i = 0;
  async function pump() {
    while (i < texts.length) {
      const idx = i++;
      results.push(one(texts[idx]).then((r) => ({ idx, r })));
    }
  }
  const workers = [];
  for (let w = 0; w < CONC; w++) workers.push(pump());
  await Promise.all(workers);
  const settled = await Promise.all(results);
  const ordered = new Array(texts.length);
  for (const { idx, r } of settled) ordered[idx] = r;
  fs.writeFileSync(outFile, JSON.stringify(ordered));
})().catch((e) => { console.error(e); process.exit(1); });
