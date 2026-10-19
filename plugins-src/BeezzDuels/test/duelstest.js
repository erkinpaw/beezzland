// BeezzDuels end-to-end: two bots, жужики given over RCON, a duel ended by surrender, then a real fight.
// usage: node duelstest.js [port] [rconPort] [rconPassword]   (server with allow-same-ip: true)
const net = require("net");
const loadtest = process.env.LOADTEST || require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const [port = "25590", rconPort = "25591", rconPass = "coretest"] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (label, ok, extra = "") => console.log(ok ? "  PASS" : "  FAIL", label, extra);

function rcon(cmds) {
  // one command at a time: the vanilla RCON server reads only the first packet of each network read
  return new Promise((resolve) => {
    const s = net.connect(Number(rconPort), "127.0.0.1"), out = [], queue = [rconPass, ...cmds];
    let buf = Buffer.alloc(0), id = 0;
    const next = () => {
      if (!queue.length) { s.end(); return resolve(out.slice(1)); }
      const b = Buffer.from(queue.shift(), "utf8"), p = Buffer.alloc(14 + b.length);
      p.writeInt32LE(10 + b.length, 0); p.writeInt32LE(++id, 4); p.writeInt32LE(id === 1 ? 3 : 2, 8); b.copy(p, 12);
      s.write(p);
    };
    s.on("data", (d) => {
      buf = Buffer.concat([buf, d]);
      while (buf.length >= 4 && buf.length >= buf.readInt32LE(0) + 4) {
        const n = buf.readInt32LE(0);
        out.push(buf.slice(12, n + 2).toString("utf8"));
        buf = buf.slice(n + 4);
        next();
      }
    });
    next();
  });
}

const join = (name) => new Promise((resolve) => {
  const bot = mineflayer.createBot({ host: "127.0.0.1", port: Number(port), username: name, version: "1.21.11", auth: "offline" });
  tickSync(bot);
  bot.chats = [];
  bot.on("messagestr", (m) => bot.chats.push(m));
  bot.once("spawn", () => setTimeout(() => resolve(bot), 1500));
});
const has = (bot, item) => bot.inventory.items().concat([bot.inventory.slots[45]].filter(Boolean)).some((i) => i.name === item);
const said = (bot, re) => bot.chats.some((m) => re.test(m));

(async () => {
  const A = await join("DuelA"), B = await join("DuelB");
  await rcon(["clear DuelA", "clear DuelB", "give DuelA dirt 5", "жужики дать DuelA 200 тест", "жужики дать DuelB 200 тест"]);
  await sleep(3500); // the daily reward message comes 3 s after joining
  check("daily login reward is given (once a day: fresh nick or new day)", said(A, /за вход/) || process.env.SECOND_RUN);
  check("admin gives жужики for an event", said(A, /\+200 жужиков/));
  const home = A.entity.position.clone();

  A.chat("/дуэль DuelB 50 алмазный");
  await sleep(1000);
  check("the challenged player gets the request", said(B, /вызывает тебя на дуэль/));
  B.chat("/дуэль принять");
  await sleep(1500);
  if (process.env.DEBUG) console.log("B:", B.chats.slice(-4).join(" | "), " A:", A.chats.slice(-4).join(" | "));
  check("both are taken to the arena (y=101)", Math.round(A.entity.position.y) === 101 && Math.round(B.entity.position.y) === 101,
    `A=${A.entity.position} B=${B.entity.position}`);
  check("kit given, survival inventory gone", has(A, "diamond_sword") && !has(A, "dirt"));
  const before = A.entity.position.clone();
  A.setControlState("forward", true);
  await sleep(1000);
  A.setControlState("forward", false);
  check("frozen during the countdown", A.entity.position.distanceTo(before) < 0.5);
  B.chat("/trade");
  await sleep(500);
  check("commands are blocked in a duel", said(B, /команды не работают/));
  await sleep(2500);
  B.chat("/дуэль сдаться");
  await sleep(500);
  check("surrender: winner announced to everyone", said(A, /DuelA победил DuelB/) && said(B, /DuelA победил DuelB/));
  await sleep(4000);
  check("after the duel: inventory back", has(A, "dirt") && !has(A, "diamond_sword"));
  check("after the duel: back where they stood", A.entity.position.distanceTo(home) < 1.5, `${A.entity.position} vs ${home}`);

  A.chats.length = 0;
  A.chat("/жужики");
  await sleep(700);
  // 200 + 10 (daily) - 50 + 95 (pot 100 minus 5%)
  check("winner balance = 200 + 10 - 50 + 95 = 255", said(A, /255 жужиков/), A.chats.join(" | ").slice(0, 160));
  B.chats.length = 0;
  B.chat("/жужики");
  await sleep(700);
  check("loser balance = 200 + 10 - 50 = 160", said(B, /160 жужиков/));

  // a real fight: A walks up and hits until B is down (or the 40 s limit makes it a draw)
  A.chats.length = 0;
  A.chat("/дуэль DuelB 20 лучник");
  await sleep(800);
  B.chat("/дуэль принять");
  await sleep(4500);
  const t0 = Date.now();
  let ended = false;
  while (Date.now() - t0 < 45000 && !ended) {
    const e = B.entity && A.nearestEntity((x) => x.type === "player" && x.username === "DuelB");
    if (e) {
      await A.lookAt(e.position.offset(0, 1.5, 0), true);
      A.setControlState("forward", A.entity.position.distanceTo(e.position) > 2.5);
      if (A.entity.position.distanceTo(e.position) < 3.2) A.attack(e);
    }
    await sleep(500);
    ended = said(A, /победил|Ничья/);
  }
  A.clearControlStates();
  check("a real fight ends with a winner (kill) or a draw (time)", ended, A.chats.filter((m) => /победил|Ничья/.test(m)).join(""));
  await sleep(4000);
  check("loser is back with full health, not dead", !B.isDead && B.health > 0 && Math.round(B.entity.position.y) !== 101);
  process.exit(0);
})();
