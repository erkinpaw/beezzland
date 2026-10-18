// BeezzAuth end-to-end: bot joins via the test proxy (25570), fake Telegram on 8099 plays the phone.
// usage: node authtest.js link|confirm
const path = require("path").join(__dirname, "../../../loadtest/") + "/";
const mineflayer = require(path + "node_modules/mineflayer");
const tickSync = require(path + "ticksync");
const TG = "http://127.0.0.1:8099";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log("[auth]", ...a);
const plain = (r) => JSON.stringify(r).replace(/\\n/g, "\n").replace(/"[a-z_]+":/g, "").replace(/[{}\[\]"]/g, " ").replace(/\s+/g, " ");

const inject = (update) => fetch(TG + "/test/inject", { method: "POST", body: JSON.stringify(update) });
const sent = async () => (await fetch(TG + "/test/sent")).json();
const msg = (user, text) => inject({ message: { message_id: 1, from: { id: user }, chat: { id: user }, text } });
const press = (user, data) => inject({ callback_query: { id: "cb" + Date.now(), from: { id: user }, data,
  message: { message_id: 5, chat: { id: user }, text: "Вход на BeezzLand" } } });

/** Join and report: {spawned:true} or {kicked:text}. */
function join(name, onWaiting) {
  return new Promise((resolve) => {
    const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25570, username: name, version: "1.21.11", auth: "offline" });
    tickSync(bot);
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); try { bot.quit(); } catch {} } };
    bot.once("spawn", () => setTimeout(() => finish({ spawned: true }), 500));
    bot.on("kicked", (r) => finish({ kicked: plain(r) }));
    bot.on("end", (r) => finish({ ended: String(r) }));
    bot.on("error", (e) => finish({ error: e.message }));
    if (onWaiting) onWaiting();
  });
}

/** Wait for a confirm message newer than `after` (count of confirms seen before the join). */
const nextConfirm = async (user, after) => {
  for (let i = 0; i < 40; i++) {
    const s = (await sent()).filter((x) => x.method === "sendMessage" && String(x.body.chat_id) === String(user) && x.body.reply_markup);
    if (s.length > after) return s[s.length - 1].body.reply_markup.inline_keyboard[0];
    await sleep(250);
  }
  return null;
};
const confirmCount = async (user) => (await sent()).filter((x) => x.method === "sendMessage" && String(x.body.chat_id) === String(user) && x.body.reply_markup).length;
const lastConfirm = async (user) => {
  const s = (await sent()).filter((x) => x.method === "sendMessage" && String(x.body.chat_id) === String(user) && x.body.reply_markup);
  return s.length ? s[s.length - 1].body.reply_markup.inline_keyboard[0] : null;
};
const lastReply = async () => { const s = (await sent()).filter((x) => x.method === "sendMessage"); return s.length ? s[s.length - 1].body.text : null; };
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);

(async () => {
  const mode = process.argv[2];
  const NAME = "AuthBot", OWNER = 777, STRANGER = 888;
  if (mode === "link") {
    let r = await join(NAME);
    log("first join:", r.kicked);
    const code = (r.kicked || "").match(/\b(\d{6})\b/)?.[1];
    check("new player is kicked with a 6-digit code", !!code);
    await msg(OWNER, "/start"); await sleep(800);
    check("bot answers /start with instructions", /код/i.test(await lastReply()));
    await msg(OWNER, "000000"); await sleep(800);
    check("wrong code is rejected", /не найден/i.test(await lastReply()));
    await msg(OWNER, code); await sleep(800);
    log("bot reply:", (await lastReply()).split("\n")[0]);
    check("right code links the nickname", /привязан/i.test(await lastReply()));
    await sleep(500);
    r = await join(NAME);
    check("same IP after linking goes straight in", r.spawned === true);
    r = await join("OtherNick");
    check("another nickname still needs its own link", /\d{6}/.test(r.kicked || ""));
  }
  if (mode === "confirm") {
    // session-hours: 0 -> every login asks
    let n = await confirmCount(OWNER);
    let r = await join(NAME, async () => {
      const kb = await nextConfirm(OWNER, n);
      log("confirm buttons:", kb && kb.map((b) => b.text).join(" | "));
      await press(STRANGER, kb[0].callback_data); await sleep(500);
      await press(OWNER, kb[0].callback_data);
    });
    check("«Это я» from the owner lets the player in (stranger's press ignored)", r.spawned === true);
    const s = await sent();
    check("stranger got «Это не твой запрос»", s.some((x) => x.method === "answerCallbackQuery" && String(x.body.text).includes("не твой")));
    await sleep(500);
    n = await confirmCount(OWNER);
    r = await join(NAME, async () => { const kb = await nextConfirm(OWNER, n); await press(OWNER, kb[1].callback_data); });
    log("after «Это не я»:", r.kicked);
    check("«Это не я» kicks with «Вход отклонён»", /отклон/i.test(r.kicked || ""));
    await sleep(500);
    log("waiting for the 25 s timeout...");
    n = await confirmCount(OWNER);
    r = await join(NAME);
    log("after timeout:", r.kicked);
    check("no answer -> kicked with «Подтверди вход»", /Подтверди/i.test(r.kicked || ""));
    const kb = await nextConfirm(OWNER, n);
    await press(OWNER, kb[0].callback_data); await sleep(800);
    r = await join(NAME);
    check("late «Это я» lets the next login in", r.spawned === true);
  }
  process.exit(0);
})();
