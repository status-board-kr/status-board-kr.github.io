'use strict';
// Run with Application Default Credentials. Default is a dry run; never print key values.
const admin = require('firebase-admin');
admin.initializeApp();
async function main() {
  const db = admin.database(), apply = process.argv.includes('--apply');
  const companies = (await db.ref('companies').once('value')).val() || {};
  let count = 0;
  for(const [id, company] of Object.entries(companies)) {
    if(!company.aiSettings) continue;
    const old = company.aiSettings;
    const saved = (await db.ref('privateAiSettings/' + id).once('value')).val();
    const keys = saved || {
      geminiKey: old.geminiKey || (old.provider === 'gemini' ? old.key : '') || '',
      grokKey: old.grokKey || '', updatedAt: new Date().toISOString()
    };
    if(apply) await db.ref().update({ ['privateAiSettings/' + id]: keys, ['companies/' + id + '/aiSettings']: null });
    count++;
  }
  console.log(`${apply ? 'Migrated' : 'Would migrate'} ${count} companies. No key values displayed.`);
}
main().then(() => admin.app().delete()).catch(() => { console.error('Migration failed. Check credentials and database configuration.'); process.exitCode = 1; admin.app().delete(); });
