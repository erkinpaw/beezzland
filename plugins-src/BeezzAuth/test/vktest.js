// BeezzAuth via VK: link through a VK message, then a login from another IP (127.0.0.2) is
// confirmed with a VK callback button. Needs fake_vk.py (8098) + the test proxy on 25570 with vk enabled.
const net = require("net");
const loadtest = require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const VK = "http://127.0.0.1:8098";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);
// kick reasons arrive either as a chat object or as a JSON string of one
const plain = (r) => {
  if (typeof r === "string") { try { r = JSON.parse(r); } catch { return r; } }
  return (JSON.stringify(r).match(/"text":"[^"]*"/g) || []).map((t) => t.slice(8, -1)).join("");
};
const inject = (type, object) => fetch(VK + "/test/inject", { method: "POST", body: JSON.stringify({ type, object }) });
const sent = async () => (await fetch(VK + "/test/sent")).json();

function join(name, fromIp, onWaiting) {
  return new Promise((resolve) => {
    const bot = mineflayer.createBot({
      username: name, version: "1.21.11", auth: "offline", host: "127.0.0.1", port: 25570,
      // connect from a chosen loopback address so the server sees a different IP
      connect: (client) => {
        const s = net.connect({ host: "127.0.0.1", port: 25570, localAddress: fromIp });
        client.setSocket(s);
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

const sendsTo = async (user) => (await sent()).filter((x) => x.method === "messages.send" && x.params.peer_id === String(user));

(async () => {
  const NAME = "VkPlayer2", USER = 555;
  let r = await join(NAME, "127.0.0.1");
  const code = (r.kicked || "").match(/(\d{6})/)?.[1];
  check("kick screen lists the VK community", /vk\.me\/beezzland_test/.test(r.kicked || ""));
  check("new player gets a code", !!code);

  await inject("message_new", { message: { from_id: USER, peer_id: USER, text: "привет" } });
  await sleep(800);
  check("VK bot answers a greeting with instructions", (await sendsTo(USER)).some((m) => /код/i.test(m.params.message)));

  await inject("message_new", { message: { from_id: USER, peer_id: USER, text: `мой код ${code}` } });
  await sleep(800);
  check("code inside a sentence links the nick", (await sendsTo(USER)).some((m) => /^Готово/.test(m.params.message)));

  await sleep(300);
  r = await join(NAME, "127.0.0.1");
  check("same IP goes straight in", r.spawned === true);

  const before = (await sendsTo(USER)).length;
  r = await join(NAME, "127.0.0.2", async () => {
    for (let i = 0; i < 40; i++) {
      const msgs = await sendsTo(USER);
      const confirm = msgs.slice(before).find((m) => m.params.keyboard);
      if (confirm) {
        const kb = JSON.parse(confirm.params.keyboard);
        check("confirm message shows a masked IP", /127\.0\.\*\.\*/.test(confirm.params.message));
        check("keyboard is inline with callback buttons", kb.inline === true && kb.buttons[0][0].action.type === "callback");
        const payload = kb.buttons[0][0].action.payload;
        await inject("message_event", { user_id: USER, peer_id: USER, event_id: "e1", payload: JSON.parse(payload) });
        return;
      }
      await sleep(250);
    }
  });
  check("new IP + «Это я» in VK lets the player in", r.spawned === true);
  const answers = (await sent()).filter((x) => x.method === "messages.sendMessageEventAnswer");
  check("VK button gets a snackbar answer", answers.some((a) => /подтвержд/i.test(a.params.event_data)));
  process.exit(0);
})();
