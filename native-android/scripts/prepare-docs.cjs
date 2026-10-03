const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(root, 'docs.html'), 'utf8');
const text = value => value.replace(/<br\s*\/?\s*>/gi, '\n').replace(/<\/(?:div|p|h\d|tr)>/gi, '\n').replace(/<[^>]*>/g, '')
  .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim();
const attr = (tag, key) => (tag.match(new RegExp('(?:^|\\s)' + key + '="([^"]*)"')) || [])[1];
const tabs = { simple: ['일반 견적서','s'], statement: ['거래명세서','st'], quote: ['장기 견적서','q'], newcar: ['신차 렌트 견적서','n'], contract: ['계약서','c'], receipt: ['영수증','r'] };
const starts = [...html.matchAll(/<div class="tab-content[^\"]*" id="tab-(\w+)"/g)];
const schema = Object.entries(tabs).map(([key,[label,prefix]]) => {
  const index = starts.findIndex(x=>x[1]===key);
  if (index < 0) throw new Error('Missing tab: '+key);
  const body = html.slice(starts[index].index, starts[index+1]?.index ?? html.length);
  const fields=[]; const radios={};
  // Keep the original enclosing card/field, including labels containing nested radio labels.
  const layout = new Map(), stack = [];
  for (const token of body.matchAll(/<!--[\s\S]*?-->|<\/?([a-z][\w:-]*)\b[^>]*>/gi)) {
    if (!token[1]) continue;
    const name = token[1].toLowerCase(), tag = token[0];
    if (tag.startsWith('</')) { const at = stack.map(x => x.name).lastIndexOf(name); if (at >= 0) stack.splice(at); continue; }
    const classes = (attr(tag, 'class') || '').split(/\s+/);
    const node = {name, index:token.index, end:token.index+tag.length, classes};
    const parents = [...stack, node];
    const card = parents.findLast(x => x.classes.includes('acard'));
    const cell = parents.findLast(x => x.classes.includes('afield'));
    const section = card ? text((body.slice(card.end).match(/<div[^>]*class="acard-title"[^>]*>([\s\S]*?)<\/div>/) || [])[1] || '') : '';
    const cellBody = cell ? body.slice(cell.end) : '';
    const mainLabel = (cellBody.match(/<label\b[^>]*>([\s\S]*?)(?:<span\b|<input\b|<\/label>)/) || [])[1];
    const radioNames = cell ? [...cellBody.slice(0, cellBody.indexOf('</div>')).matchAll(/<input\b[^>]*type="radio"[^>]*>/g)].map(x=>attr(x[0],'name')).filter(Boolean) : [];
    layout.set(token.index, {section, cell:cell?.index, label:mainLabel ? text(mainLabel) : '', radioNames:[...new Set(radioNames)]});
    if (!/\/\s*>$/.test(tag) && !['input','img','br','hr','meta','link','source','wbr','area','base','embed','param'].includes(name)) stack.push(node);
  }
  const pattern=/<(input|select|textarea)\b([^>]*)(?:>([\s\S]*?)<\/\1>)?/g;
  for (const match of body.matchAll(pattern)) {
    const tag=match[0], attrs=match[2], kind=match[1]; const id=attr(attrs,'id'), type=attr(attrs,'type') || 'text';
    if(type==='radio') {
      const name=attr(attrs,'name'); if(!name)continue;
      const tagEnd = body.indexOf('>', match.index) + 1;
      (radios[name]??=[]).push({value:attr(attrs,'value')||'',label:text(body.slice(tagEnd).split('</label>')[0]), checked:/\bchecked\b/.test(attrs)}); continue;
    }
    if(!id || !id.startsWith(prefix+'_') || type==='hidden')continue;
    const before=body.slice(0,match.index); const labels=[...before.matchAll(/<label[^>]*>([\s\S]*?)<\/label>/g)];
    const place = layout.get(match.index) || {};
    let title=place.label || text(labels.at(-1)?.[1] || attr(attrs,'placeholder') || id);
    if(id.endsWith('_biznum'))title='사업자등록번호'; if(id.endsWith('_corpnum'))title='법인등록번호'; if(id.endsWith('_ceo'))title='대표자 이름';
    const options=kind==='select'?[...(match[3]||'').matchAll(/<option\b([^>]*)>([\s\S]*?)<\/option>/g)].map(x=>({value:attr(x[1],'value')??text(x[2]),label:text(x[2]),selected:/\bselected\b/.test(x[1])})):[];
    fields.push({id,label:title,kind,type,section:place.section||'',cell:place.cell,radioNames:place.radioNames||[],placeholder:attr(attrs,'placeholder')||'',readonly:/\breadonly\b/.test(attrs),options,value:attr(attrs,'value')??(kind==='textarea'?text(match[3]||''):(options.find(x=>x.selected)||options[0])?.value||'')});
  }
  if(key==='newcar')for(let option=1;option<=3;option++) for(const [name,title] of [['deposit','보증금 (%)'],['prepay','선납금 (%)'],['price','월 렌트료 (원)']]) fields.push({id:`n_p${option}_${name}`,label:`조건 ${option} · ${title}`,kind:'input',type:'text',options:[],value:''});
  return {key,label,prefix,fields,radios};
});
function source(name) {
  const start=html.indexOf('function '+name+'('); if(start<0)throw Error('Missing '+name);
  const end=html.slice(start+1).search(/\nfunction \w+\(/);
  return html.slice(start,end<0?html.length:start+1+end);
}
const context=vm.createContext({secTitle:title=>'<h2>'+title+'</h2>'});
for(const name of ['termsHtml','privacyHtml','rentalTermsHtml'])vm.runInContext(source(name),context,{timeout:1000});
const terms={special:text(vm.runInContext('termsHtml("{{km}}")',context,{timeout:1000})),privacy:text(vm.runInContext('privacyHtml()',context,{timeout:1000})),rental:text(vm.runInContext('rentalTermsHtml()',context,{timeout:1000}))};
const priceMatch = html.match(/var PRICE_TABLE\s*=\s*({[\s\S]*?});/);
const ratesMatch = html.match(/var NC_RATES_DEFAULT\s*=\s*({[\s\S]*?});/);
const priceTable = vm.runInNewContext('('+priceMatch[1]+')');
const carsMatch = html.match(/var NC_CARS_DEFAULT\s*=\s*("(?:\\.|[^"\\])*")/);
const cars = carsMatch ? vm.runInNewContext('('+carsMatch[1]+')') : '';
const newcarRates = ratesMatch ? vm.runInNewContext('('+ratesMatch[1]+')', {NC_CARS_DEFAULT:cars}) : {};
const images = Object.fromEntries(['logoImg','stampImg'].map(id=>[id,(html.match(new RegExp('<img id="'+id+'" src="([^"]*)"'))||[])[1]||'']));
const out=path.join(root,'native-android/app/src/main/assets/documents-schema.json');
fs.writeFileSync(out, JSON.stringify({source:'docs.html',tabs:schema,terms,priceTable,newcarRates},null,2)+'\n');
fs.writeFileSync(path.join(root,'native-android/app/src/main/assets/documents-brand.json'),JSON.stringify(images)+'\n');
console.log('Native document schema: '+schema.length+' forms / '+schema.reduce((n,t)=>n+t.fields.length,0)+' fields; original contract terms copied.');
