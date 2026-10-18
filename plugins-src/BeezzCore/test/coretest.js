// BeezzCore check: a brand-new player gets the welcome title and chat lines, a second player sees
// the «new player» broadcast, /rules and /links answer, and a tip arrives (set tips.every-minutes: 1).
// usage: node coretest.js <host> <port>
const loadtest = process.env.LOADTEST || require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const [host = "127.0.0.1", port = "25580"] = process.argv.slice(2);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);

function bot(name) {
  const b = mineflayer.createBot({ host, port: Number(port), username: name, version: "1.21.11", auth: "offline" });
  tickSync(b);
  b.chatLog = [];
  b.titles = [];
  b.on("message", (m) => b.chatLog.push(m.toString()));
  b.on("title", (t) => b.titles.push(typeof t === "string" ? t : JSON.stringify(t)));
  return new Promise((resolve) => b.once("spawn", () => resolve(b)));
}

(async () => {
  const suffix = String(Date.now() % 100000);
  const old = await bot("Oldie" + suffix);
  await sleep(1500);
  const fresh = await bot("Newbie" + suffix);
  await sleep(3500); // welcome is sent 2 s after join
  const all = fresh.chatLog.join("\n");
  check("new player gets the welcome chat lines", /честное выживание/.test(all) && /\/rules/.test(all));
  check("new player sees a title", fresh.titles.some((t) => /Добро пожаловать/.test(t)));
  check("others see «Поприветствуем нового игрока»", old.chatLog.some((l) => l.includes("Newbie" + suffix)));

  fresh.chat("/rules");
  await sleep(1000);
  check("/rules prints the rules", fresh.chatLog.some((l) => /Правила BeezzLand/.test(l)));
  fresh.chat("/links");
  await sleep(1000);
  check("/links prints the socials", fresh.chatLog.some((l) => /BeezzLand в сети/.test(l)));

  console.log("  waiting up to 70 s for a tip...");
  for (let i = 0; i < 70 && !fresh.chatLog.some((l) => l.includes("[Совет]")); i++) await sleep(1000);
  check("a chat tip arrives", fresh.chatLog.some((l) => l.includes("[Совет]")));

  old.quit(); fresh.quit();
  await sleep(500);
  const back = await bot("Newbie" + suffix);
  await sleep(3500);
  check("returning player is NOT welcomed again", !back.chatLog.join("\n").includes("честное выживание"));
  process.exit(0);
})();
