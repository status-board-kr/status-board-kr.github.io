/**
 * AI 모델 호출 - 현황판 "🤖 AI 설정"에 넣은 업체 공용 키 종류에 맞춰 부릅니다.
 *  - 구글 Gemini 키 → Gemini (무료 모델, 현황판 사진 인식과 같은 모델 목록)
 *  - Claude 키(sk-ant-…) → Claude
 * 두 경우 모두 같은 안내문(system)과 같은 도구(예약·상담 신청)를 씁니다.
 * runTool(name, input) → { ok } 또는 { error } 를 돌려주면, 그 결과를 AI에게 다시 보여주고 한 번 더 답을 받습니다(최대 3회).
 */
import { TOOLS } from './chat-core.js';

// 현황판 index.html 의 CLAUDE_MODEL, GEMINI_MODELS 와 같게
const CLAUDE_MODEL = 'claude-sonnet-5';
export const GEMINI_MODELS = ['gemini-3.1-flash-lite', 'gemini-3.5-flash-lite', 'gemini-flash-lite-latest', 'gemini-3.6-flash', 'gemini-flash-latest'];
const MAX_ROUNDS = 3;
export const REFUSAL_TEXT = '죄송해요, 그 문의는 도와드리기 어려워요.';

/** 현황판 index.html aiProvider 와 같은 규칙 */
export function aiProvider(key) {
  if (typeof key !== 'string' || key.trim().length < 20) return '';
  return key.startsWith('sk-ant-') ? 'claude' : 'gemini';
}

/** Claude 도구 정의(JSON Schema) → Gemini 함수 선언 형식 (지원하지 않는 항목은 뺌) */
export function toGeminiSchema(s) {
  const out = { type: String(s.type || 'string').toUpperCase() };
  if (s.description) out.description = s.description;
  if (s.enum) out.enum = s.enum;
  if (s.properties) out.properties = Object.fromEntries(Object.entries(s.properties).map(([k, v]) => [k, toGeminiSchema(v)]));
  if (s.required) out.required = s.required;
  if (s.items) out.items = toGeminiSchema(s.items);
  return out;
}

const GEMINI_TOOLS = [{ functionDeclarations: TOOLS.map(t => ({ name: t.name, description: t.description, parameters: toGeminiSchema(t.input_schema) })) }];

/** Gemini 한 번 호출. 모델이 바쁘거나 없으면 다음 모델로. 성공한 모델 이름도 돌려줌. */
async function geminiCall(key, models, body, fetchImpl) {
  let last = '';
  for (const model of models) {
    try {
      const r = await fetchImpl(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`, {
        method: 'POST',
        headers: { 'x-goog-api-key': key, 'content-type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(30_000),
      });
      const data = await r.json().catch(() => null);
      if (r.status >= 200 && r.status < 300 && data) return { data, model };
      last = `${model} ${r.status} ${data?.error?.message || ''}`.trim();
      if (r.status === 401 || r.status === 403) break; // 키 문제는 다른 모델도 같음
    } catch (e) { last = `${model} ${e?.message || e}`; }
  }
  const err = new Error('Gemini 호출 실패: ' + last);
  err.status = 502;
  throw err;
}

async function runGemini({ key, system, history, msg, runTool, fetchImpl }) {
  const contents = [
    ...history.map(m => ({ role: m.role === 'assistant' ? 'model' : 'user', parts: [{ text: m.text }] })),
    { role: 'user', parts: [{ text: msg }] },
  ];
  let models = GEMINI_MODELS;
  for (let i = 0; i < MAX_ROUNDS; i++) {
    const { data, model } = await geminiCall(key, models, {
      systemInstruction: { parts: [{ text: system }] },
      contents,
      tools: GEMINI_TOOLS,
      generationConfig: { temperature: 0.4, maxOutputTokens: 2048 },
    }, fetchImpl);
    models = [model]; // 한 대화 안에서는 같은 모델로 이어감
    const cand = data.candidates?.[0];
    if (!cand) { if (data.promptFeedback?.blockReason) return REFUSAL_TEXT; return ''; }
    if (cand.finishReason === 'SAFETY' || cand.finishReason === 'PROHIBITED_CONTENT') return REFUSAL_TEXT;
    const parts = cand.content?.parts || [];
    const text = parts.filter(p => typeof p.text === 'string' && !p.thought).map(p => p.text).join('').trim();
    const calls = parts.filter(p => p.functionCall);
    if (!calls.length) return text;
    contents.push(cand.content); // 모델 답을 그대로(생각 서명 포함) 돌려줘야 다음 호출이 이어짐
    const responses = [];
    for (const p of calls) {
      const r = await runTool(p.functionCall.name, p.functionCall.args || {});
      responses.push({ functionResponse: { name: p.functionCall.name, response: r.ok ? { result: r.ok } : { error: r.error } } });
    }
    contents.push({ role: 'user', parts: responses });
  }
  return '';
}

async function runClaude({ client, system, history, msg, runTool }) {
  const messages = [
    ...history.map(m => ({ role: m.role, content: m.text })),
    { role: 'user', content: msg },
  ];
  for (let i = 0; i < MAX_ROUNDS; i++) {
    const response = await client.messages.create({
      model: CLAUDE_MODEL,
      max_tokens: 4000,
      output_config: { effort: 'low' },
      system,
      tools: TOOLS,
      messages,
    });
    if (response.stop_reason === 'refusal') return REFUSAL_TEXT;
    const out = response.content.filter(b => b.type === 'text').map(b => b.text).join('').trim();
    const calls = response.content.filter(b => b.type === 'tool_use');
    if (response.stop_reason !== 'tool_use' || !calls.length) return out;

    messages.push({ role: 'assistant', content: response.content });
    const results = [];
    for (const call of calls) {
      const r = await runTool(call.name, call.input || {});
      results.push({ type: 'tool_result', tool_use_id: call.id, content: r.ok || r.error, ...(r.error ? { is_error: true } : {}) });
    }
    messages.push({ role: 'user', content: results });
  }
  return '';
}

/**
 * 키 종류에 맞는 AI로 답을 만듭니다.
 * @returns {Promise<string>} 고객에게 보낼 답 (비어 있을 수 있음)
 */
export async function runModel({ key, system, history, msg, runTool, makeClient, fetchImpl }) {
  return aiProvider(key) === 'claude'
    ? runClaude({ client: makeClient(key), system, history, msg, runTool })
    : runGemini({ key, system, history, msg, runTool, fetchImpl });
}
