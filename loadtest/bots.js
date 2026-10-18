// Load test: N bots join one by one; "explorers" fly off in different directions (default 5 blocks/s,
// sprint speed; creative flight is ~11, elytra ~30), forcing the server to load and generate new chunks, the heaviest
// real-player load. Walking bots get stuck on terrain around spawn, so bots are switched to
// creative over RCON (needs MC_BASE, like scripts/rcon.py) and fly at y=160 above the terrain.
// The rest move back and forth near spawn like players in their base (chunks already loaded).
// Every bot is teleported to spawn first (otherwise it reappears where it logged off last run).
// radius > 0 keeps explorers inside that distance from spawn (e.g. a pre-generated area): at the edge
// they turn back inwards. Spawn is LT_SPAWN="x,z" (default 32,-16).
// 26.3 is not in mineflayer yet: bots join as 1.21.11 and ViaVersion translates.
//
// MC_BASE=/opt/mc node bots.js [count=40] [minutes=5] [speed=5] [explorers=1.0] [radius=0] [host=127.0.0.1] [port=25565] [version=1.21.11] [mode=fly|walk]
const path = require("path");
const { execFile } = require("child_process");
const { Vec3 } = require("vec3");
const mineflayer = require("mineflayer");
const tickSync = require("./ticksync");

const [count = 40, minutes = 5, speed = 5, explorers = 1.0, radius = 0, host = "127.0.0.1", port = 25565, version = "1.21.11", mode = "fly"] = process.argv.slice(2);
const JOIN_GAP_MS = 3200; // Velocity allows one login per IP every 3 s by default
const FLY_Y = 160;
const [SPAWN_X, SPAWN_Z] = (process.env.LT_SPAWN || "32,-16").split(",").map(Number);
const RCON = path.join(__dirname, "..", "scripts", "rcon.py");
const stats = { joined: 0, online: 0, kicked: 0, errors: 0 };
const bots = [];
let stopping = false;

const rcon = (cmd) => new Promise((resolve) =>
  execFile(process.platform === "win32" ? "python" : "python3", [RCON, cmd], (err, out) => resolve(err ? `rcon error: ${err.message}` : out.trim())));

async function fly(bot, yaw) {
  await rcon(`gamemode creative ${bot.username}`);
  const moved = new Promise((r) => { bot.once("forcedMove", r); setTimeout(r, 5000); });
  await rcon(`execute in minecraft:overworld run tp ${bot.username} ${SPAWN_X} ${FLY_Y} ${SPAWN_Z}`);
  await moved;
  await new Promise((r) => setTimeout(r, 500));
  // explorers: straight out along yaw, turning back inwards at the radius;
  // homebodies: back and forth over 32 blocks near spawn
  let dir = new Vec3(-Math.sin(yaw), 0, -Math.cos(yaw));
  const step = Number(speed) / 20; // blocks per 50 ms tick
  const r = Number(radius);
  for (let t = 0; !stopping && bot.entity; t++) {
    const pos = bot.entity.position;
    if (bot.explorer && r > 0 && Math.hypot(pos.x - SPAWN_X, pos.z - SPAWN_Z) > r) {
      // head back towards spawn, ±60° so bots keep crossing different chunks
      const a = Math.atan2(SPAWN_X - pos.x, SPAWN_Z - pos.z) + (Math.random() - 0.5) * (Math.PI * 2 / 3);
      dir = new Vec3(Math.sin(a), 0, Math.cos(a));
      bot.entity.position = pos.plus(dir.scaled(2)); // step inside so we don't turn again next tick
    }
    const sign = bot.explorer || Math.floor(t * step / 32) % 2 === 0 ? 1 : -1;
    bot.entity.velocity = new Vec3(0, 0, 0);
    bot.entity.position = bot.entity.position.plus(dir.scaled(step * sign));
    await new Promise((res) => setTimeout(res, 50));
  }
}

function walk(bot, yaw) {
  bot.look(yaw, 0, true);
  bot.setControlState("forward", true);
  bot.setControlState("sprint", true);
  // jump over obstacles, wiggle direction a bit so bots don't get stuck on one wall
  bot.walkTimer = setInterval(() => {
    bot.setControlState("jump", Math.random() < 0.5);
    bot.look(yaw + (Math.random() - 0.5) * 0.8, 0, true);
  }, 1000);
}

function spawnBot(i) {
  const name = `Bee_${String(i).padStart(2, "0")}`;
  const bot = mineflayer.createBot({ host, port: Number(port), username: name, version, auth: "offline" });
  tickSync(bot);
  bots.push(bot);
  const yaw = (i / count) * Math.PI * 2; // spread bots evenly around spawn
  bot.explorer = (i - 1) % Math.max(1, Math.round(1 / Number(explorers))) === 0 && Number(explorers) > 0;

  bot.once("spawn", () => {
    stats.joined++; stats.online++;
    if (mode === "walk") walk(bot, yaw);
    else fly(bot, yaw).catch((e) => console.log(`${name} fly error: ${e.message}`));
  });
  bot.on("kicked", (reason) => { stats.kicked++; const s = JSON.stringify(reason); console.log(`${name} kicked: ${s.match(/(multiplayer\.)?disconnect\.[a-z_.]+/)?.[0] || s.slice(0, 160)}`); });
  bot.on("error", (e) => { stats.errors++; console.log(`${name} error: ${e.message}`); });
  bot.on("end", () => { if (bot.walkTimer) clearInterval(bot.walkTimer); if (stats.online && bot.entity) stats.online--; });
}

for (let i = 1; i <= Number(count); i++) setTimeout(() => spawnBot(i), (i - 1) * JOIN_GAP_MS);

const report = setInterval(() => {
  const far = bots.filter((b) => b.entity).map((b) => Math.hypot(b.entity.position.x - SPAWN_X, b.entity.position.z - SPAWN_Z));
  const maxDist = far.length ? Math.round(Math.max(...far)) : 0;
  const minDist = far.length ? Math.round(Math.min(...far)) : 0;
  console.log(`[bots] joined ${stats.joined}/${count} (explorers ${bots.filter((b) => b.explorer).length}), online ${stats.online}, kicked ${stats.kicked}, errors ${stats.errors}, distance from spawn: ${minDist}-${maxDist} blocks`);
}, 10000);

setTimeout(() => {
  stopping = true;
  clearInterval(report);
  console.log(`[bots] done: joined ${stats.joined}/${count}, kicked ${stats.kicked}, errors ${stats.errors}`);
  bots.forEach((b) => b.quit());
  setTimeout(() => process.exit(0), 2000);
}, (Number(count) * JOIN_GAP_MS) + Number(minutes) * 60000);
