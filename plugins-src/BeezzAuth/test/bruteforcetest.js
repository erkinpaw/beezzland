// Guessing other people's link codes: after 5 wrong codes a bot account is paused, even for a right code.
// Needs fake_telegram.py (8099) + the test proxy on 25570 with telegram enabled.
const loadtest = require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const TG = "http://127.0.0.1:8099";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);
const plain = (r) => {
  if (typeof r === "string") { try { r = JSON.parse(r); } catch { return r; } }
  return (JSON.stringify(r).match(/"text":"[^"]*"/g) || []).map((t) => t.slice(8, -1)).join("");
};
const msg = (user, text) => fetch(TG + "/test/inject", { method: "POST",
  body: JSON.stringify({ message: { message_id: 1, from: { id: user }, chat: { id: user }, text } }) });
const replyTo = async (user) => {
  const s = (await (await fetch(TG + "/test/sent")).json()).filter((x) => x.method === "sendMessage" && String(x.body.chat_id) === String(user));
  return s.length ? s[s.length - 1].body.text : "";
};

function kickCode(name) {
  return new Promise((resolve) => {
    const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25570, username: name, version: "1.21.11", auth: "offline" });
    tickSync(bot);
    bot.on("kicked", (r) => resolve((plain(r).match(/(\d{6})/) || [])[1]));
    bot.on("error", () => resolve(null));
  });
}

(async () => {
  const ATTACKER = 9001, OWNER = 9002;
  const code = await kickCode("Victim" + (Date.now() % 10000));
  check("victim got a code", !!code);
  for (let i = 0; i < 5; i++) {
    const guess = String((Number(code) + 1 + i) % 1000000).padStart(6, "0");
    await msg(ATTACKER, guess);
    await sleep(300);
  }
  check("5 wrong guesses are answered «не найден»", /не найден/i.test(await replyTo(ATTACKER)));
  await msg(ATTACKER, code);
  await sleep(500);
  check("6th try with the RIGHT code is refused", /Слишком много/i.test(await replyTo(ATTACKER)));
  await msg(OWNER, code);
  await sleep(500);
  check("the real owner can still link with that code", /^Готово/.test(await replyTo(OWNER)));
  process.exit(0);
})();
