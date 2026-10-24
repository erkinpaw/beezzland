// BeezzAuth via Discord: link by a DM, then a login from 127.0.0.2 confirmed with a button.
// Needs fake_discord.py (8097) + the test proxy on 25570 with discord enabled (api-base http://127.0.0.1:8097/api).
const net = require("net");
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
const dmsTo = async (user) => (await sent()).filter((x) => x.path === `/api/channels/dm-${user}/messages`);

function join(name, fromIp, onWaiting) {
  return new Promise((resolve) => {
    const bot = mineflayer.createBot({
      username: name, version: "1.21.11", auth: "offline", host: "127.0.0.1", port: 25570,
      connect: (client) => {
        client.setSocket(net.connect({ host: "127.0.0.1", port: 25570, localAddress: fromIp }));
        client.emit("connect");
      },
    });
    tickSync(bot);
    let done = false;
    const finish = (r) => { if (!done) { done = true; resolve(r); try { bot.quit(); } catch {} } };
    bot.once("spawn", () => setTimeout(() => finish({ spawned: true }), 500));
    bot.on("kicked", (r) => finish({ kicked: plain(r) }));
    bot.on("error", (e) => finish({ error: e.message }));
    if (onWaiting) onWaiting();
  });
}

(async () => {
  const NAME = "DiscordPlayer" + (Date.now() % 1000), USER = "321";
  let r = await join(NAME, "127.0.0.1");
  const code = (r.kicked || "").match(/(\d{6})/)?.[1];
  check("kick screen lists the Discord invite", /discord\.gg\/beezztest/.test(r.kicked || ""));
  check("new player gets a code", !!code);

  const sentBefore = (await sent()).length;
  await inject("MESSAGE_CREATE", { channel_id: "server-chat", guild_id: "1", author: { id: USER }, content: code });
  await sleep(600);
  check("messages in server channels are ignored", (await sent()).length === sentBefore);

  await inject("MESSAGE_CREATE", { channel_id: `dm-${USER}`, author: { id: USER }, content: code });
  await sleep(1000);
  const linked = (await dmsTo(USER)).some((m) => /^Готово/.test(m.body.content));
  check("DM with the code links the nick", linked);
  check("REST calls carry the bot token", (await sent()).every((x) => x.auth === "Bot TESTTOKEN"));

  await sleep(300);
  r = await join(NAME, "127.0.0.1");
  check("same IP goes straight in", r.spawned === true);

  const before = (await dmsTo(USER)).length;
  r = await join(NAME, "127.0.0.2", async () => {
    for (let i = 0; i < 40; i++) {
      const confirm = (await dmsTo(USER)).slice(before).find((m) => m.body.components);
      if (confirm) {
        const btn = confirm.body.components[0].components[0];
        check("confirm DM has «Это я» / «Это не я» buttons", btn.label === "Это я" && confirm.body.components[0].components[1].label === "Это не я");
        await inject("INTERACTION_CREATE", { type: 3, id: "i1", token: "tok1", user: { id: USER }, data: { custom_id: btn.custom_id } });
        return;
      }
      await sleep(250);
    }
  });
  check("new IP + «Это я» in Discord lets the player in", r.spawned === true);
  const cb = (await sent()).find((x) => x.path === "/api/interactions/i1/tok1/callback");
  check("button answer updates the message and removes buttons", cb && cb.body.type === 7 && cb.body.data.components.length === 0);
  process.exit(0);
})();
