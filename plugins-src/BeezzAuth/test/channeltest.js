// BeezzAuth via the Discord #регистрация channel: the bot posts one «Привязать аккаунт» button,
// the button opens a window for the code, the answer is visible only to the player.
// Needs fake_discord.py (8097) + the test proxy on 25570 with discord enabled (see README).
const loadtest = require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const DC = "http://127.0.0.1:8097";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);
const plain = (r) => {
  if (typeof r === "string") { try { r = JSON.parse(r); } catch { return r; } }
  return (JSON.stringify(r).match(/"text":"[^"]*"/g) || []).map((t) => t.slice(8, -1)).join("");
};
const inject = (t, d) => fetch(DC + "/test/inject", { method: "POST", body: JSON.stringify({ t, d }) });
const sent = async () => (await fetch(DC + "/test/sent")).json();
const callback = async (id) => (await sent()).find((x) => x.path === `/api/interactions/${id}/t${id}/callback`);
const press = (id, user, custom) => inject("INTERACTION_CREATE", { type: 3, id, token: "t" + id, guild_id: "1", member: { user: { id: user } }, data: { custom_id: custom } });
const submit = (id, user, code) => inject("INTERACTION_CREATE", { type: 5, id, token: "t" + id, guild_id: "1", member: { user: { id: user } },
  data: { custom_id: "bz:code", components: [{ type: 1, components: [{ type: 4, custom_id: "code", value: code }] }] } });

function join(name) {
  return new Promise((resolve) => {
    const bot = mineflayer.createBot({ username: name, version: "1.21.11", auth: "offline", host: "127.0.0.1", port: 25570 });
    tickSync(bot);
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); try { bot.quit(); } catch {} } };
    bot.once("spawn", () => setTimeout(() => finish({ spawned: true }), 500));
    bot.on("kicked", (r) => finish({ kicked: plain(r) }));
    bot.on("error", (e) => finish({ error: e.message }));
  });
}

(async () => {
  const panels = (await sent()).filter((x) => x.path === "/api/channels/reg/messages");
  check("bot posted exactly one registration button in #регистрация", panels.length === 1
    && panels[0].body.components[0].components[0].custom_id === "bz:link");

  const NAME = "ChannelPlayer" + (Date.now() % 1000), USER = "555";
  let r = await join(NAME);
  const code = (r.kicked || "").match(/(\d{6})/)?.[1];
  check("kick screen points to #регистрация", /#регистрация/.test(r.kicked || ""));

  await press("t1", USER, "ticket:open");
  await sleep(500);
  check("other buttons (moderation bot tickets) are left alone", !(await callback("t1")));

  await press("b1", USER, "bz:link");
  await sleep(500);
  const form = await callback("b1");
  check("button opens a window for the code", form && form.body.type === 9 && form.body.data.custom_id === "bz:code");

  await submit("f1", USER, "000000" === code ? "111111" : "000000");
  await sleep(500);
  const bad = await callback("f1");
  check("wrong code: private «not found» answer", bad && bad.body.data.flags === 64 && /не найден/.test(bad.body.data.content));

  await submit("f2", USER, code);
  await sleep(500);
  const ok = await callback("f2");
  check("right code: private «Готово» answer", ok && ok.body.data.flags === 64 && /^Готово/.test(ok.body.data.content));

  r = await join(NAME);
  check("linked player gets in", r.spawned === true);
  process.exit(0);
})();
