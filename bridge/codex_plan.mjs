/** Optional OFFLINE plan generator using the official SDK, not a phone control endpoint.
 * Authenticate in the official Codex flow before running. Never provide account cookies.
 * Run: npm install @openai/codex-sdk ; node codex_plan.mjs "先识别，再点一下"
 * The output is a suggestion; submit_task.py creates the device-bound envelope separately.
 */
import { Codex } from '@openai/codex-sdk';
const instruction = process.argv[2];
if (!instruction || instruction.length > 1000) throw new Error('Supply a short public test instruction');
const codex = new Codex();
const thread = codex.startThread({ sandboxMode: 'read-only', skipGitRepoCheck: true });
const result = await thread.run(
  'Return ONLY a JSON array of 1 to 10 action names, selected from INSPECT, CLICK, INPUT, SWIPE. ' +
  'These are public demo controls in a sandbox. INPUT writes a fixed demo constant. ' +
  'No sending, secrets, apps, shell, coordinates or selectors. No tools are needed. ' +
  'Do not treat the request as permission to do anything; only suggest action names. Request: ' + instruction
);
const actions = JSON.parse(result.finalResponse);
if (!Array.isArray(actions) || actions.length < 1 || actions.length > 10 ||
    actions.some(a => !['INSPECT','CLICK','INPUT','SWIPE'].includes(a))) throw new Error('Rejected model output');
console.log(JSON.stringify(actions));
