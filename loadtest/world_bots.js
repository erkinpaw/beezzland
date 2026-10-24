// Load test with players spread over all worlds like a real evening:
//   main world — explorers (new chunks) and players at their bases; 2 stand at the main-world farms;
//   nether — half wander, half explore; farm world — AFK players at the farms and a few touring them;
//   farm nether — a few wandering.
// Bots fly in creative (walking bots get stuck on terrain); they are put into their world over RCON.
// Plan file: the roles below; farm spots come from farms.py (same coordinates).
//
// MC_BASE=… node world_bots.js [minutes=15] [port=25590] [scale=1]
//   scale multiplies every role (0.5 → 30 bots) for quick checks; scale=farms → one bot at each farm, nothing else
const path = require("path");
const { execFile } = require("child_process");
const { Vec3 } = require("vec3");
const mineflayer = require("mineflayer");
const tickSync = require("./ticksync");

const [minutes = 15, port = 25590, scale = 1] = process.argv.slice(2);
const JOIN_GAP_MS = 3200;           // Velocity: one login per IP every 3 s
// LT_SPREAD=5000: main-world bases 300–5000 blocks from spawn and at least 300 apart, explorers within 4900
// (needs the main world pre-generated to that radius). Default: bases 150–1550, explorers within 2900.
const SPREAD = Number(process.env.LT_SPREAD || 0);
const RADIUS = SPREAD ? SPREAD - 100 : 2900;  // inside the pre-generated area
const SPAWN = [32, -32];
const FY = 200;                     // farms float at y=200 (farms.py)
const RCON = path.join(__dirname, "..", "scripts", "rcon.py");

// farm stand spots (farms.py stand_spot)
const FARM = {
  ironA: [304, FY + 1, 312], ironB: [-296, FY + 1, 312], woolA: [304, FY + 1, -296], woolB: [-296, FY + 1, -296], spawner: [7, FY, 400],
  mainIron: [604, FY + 1, 612], mainWool: [-596, FY + 1, 604],
};

const roles = [];
const add = (n, role) => { for (let i = 0; i < Math.round(n * Number(scale)); i++) roles.push({ ...role, i }); };
if (scale === "farms") {
  // farm check without load: one bot standing at each farm
  for (const [k, at] of Object.entries(FARM)) roles.push({ world: k.startsWith("main") ? "minecraft:overworld" : "minecraft:farm", kind: "stand", at, i: 0 });
} else {
add(15, { world: "minecraft:overworld", kind: "explore", y: 160, speed: 5 });
add(13, { world: "minecraft:overworld", kind: "base", y: 120, speed: 2 });
add(1, { world: "minecraft:overworld", kind: "stand", at: FARM.mainIron });
add(1, { world: "minecraft:overworld", kind: "stand", at: FARM.mainWool });
add(5, { world: "minecraft:the_nether", kind: "base", y: 100, speed: 2, spread: 400 });
if (process.env.LT_HYPERLOOP) {
  // nether hyperloops: boats on blue ice, ~70 blocks/s, straight along x or z from the hub at 0,0 to a spot within
  // ±1450 and back — the fastest way players load chunks (needs the nether pre-generated to its 3k border)
  add(5, { world: "minecraft:the_nether", kind: "hyperloop", y: 100, speed: 70, radius: 1450 });
} else {
  add(5, { world: "minecraft:the_nether", kind: "explore", y: 100, speed: 4, radius: 900 });
}
for (const spot of ["ironA", "ironB", "woolA", "woolB", "spawner"]) add(2, { world: "minecraft:farm", kind: "stand", at: FARM[spot] });
add(5, { world: "minecraft:farm", kind: "tour", y: FY + 2, speed: 4 });
add(5, { world: "minecraft:farm_nether", kind: "base", y: 100, speed: 2, spread: 200 });
}

const stats = { joined: 0, online: 0, kicked: 0, errors: 0 };
const bots = [];
let stopping = false;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const rcon = (...cmds) => new Promise((resolve) =>
  execFile(process.platform === "win32" ? "python" : "python3", [RCON, ...cmds], (err, out) => resolve(err ? `rcon error: ${err.message}` : out.trim())));

// stable pseudo-random per bot, so every run puts bases in the same places
const rnd = (seed) => { const x = Math.sin(seed * 9301 + 49297) * 233280; return x - Math.floor(x); };

// main-world bases for LT_SPREAD: the same spots every run (seeded), no two closer than 300 blocks
const bases = [];
function spreadBase(i) {
  while (bases.length <= i) {
    for (let k = 0; ; k++) {
      const seed = 1000 + bases.length * 97 + k;
      const a = rnd(seed) * Math.PI * 2, d = 300 + rnd(seed + 7) * (SPREAD - 300);
      const p = [Math.round(SPAWN[0] + Math.cos(a) * d), Math.round(SPAWN[1] + Math.sin(a) * d)];
      if (bases.every((b) => Math.hypot(b[0] - p[0], b[1] - p[1]) >= 300)) { bases.push(p); break; }
    }
  }
  return bases[i];
}

async function place(bot, role, idx) {
  let x, y, z;
  if (role.kind === "stand") [x, y, z] = role.at.map((v, k) => v + (k === 0 ? role.i * 2 : 0));
  else if (role.kind === "tour") [x, y, z] = [FARM.ironA[0], role.y, FARM.ironA[2]];
  else if (SPREAD && role.kind === "base" && role.world === "minecraft:overworld") {
    const [bx, bz] = spreadBase(role.i);
    [x, y, z] = [bx, role.y, bz];
  } else {
    const a = rnd(idx) * Math.PI * 2, d = role.kind === "explore" ? 0 : 150 + rnd(idx + 99) * (role.spread || 1400);
    [x, y, z] = [Math.round(SPAWN[0] + Math.cos(a) * d), role.y, Math.round(SPAWN[1] + Math.sin(a) * d)];
  }
  await rcon(`gamemode creative ${bot.username}`);
  await sleep(500);
  const moved = new Promise((r) => { bot.once("forcedMove", r); setTimeout(r, 8000); });
  await rcon(`execute in ${role.world} run tp ${bot.username} ${x} ${y} ${z}`);
  await moved;
  // fly from here on: no gravity, so the bot stays where the server put it (a jump back up would be refused)
  bot.creative.startFlying();
  return bot.entity.position.clone();
}

async function act(bot, role, idx) {
  const home = await place(bot, role, idx);
  const step = (role.speed || 0) / 20;
  let dir = new Vec3(Math.cos(rnd(idx) * Math.PI * 2), 0, Math.sin(rnd(idx) * Math.PI * 2));
  const tour = [FARM.ironA, FARM.woolA, FARM.woolB, FARM.ironB, FARM.spawner];
  let target = (role.i + 1) % tour.length;
  for (let t = 0; !stopping && bot.entity; t++) {
    const pos = bot.entity.position;
    pos.y = home.y;  // bots have no flight: without this they sink with gravity
    bot.entity.velocity = new Vec3(0, 0, 0);
    if (role.kind === "stand") {
      // AFK player: stays put (no flight in the bot, so hold it in place), turns the head now and then
      bot.entity.position = home.clone();
      if (t % 100 === 0) bot.look(rnd(t + idx) * 6.28, 0, true);
    } else if (role.kind === "explore") {
      const r = role.radius || RADIUS;
      if (Math.hypot(pos.x - SPAWN[0], pos.z - SPAWN[1]) > r) {
        const a = Math.atan2(SPAWN[1] - pos.z, SPAWN[0] - pos.x) + (Math.random() - 0.5) * 2;
        dir = new Vec3(Math.cos(a), 0, Math.sin(a));
        bot.entity.position = pos.plus(dir.scaled(2));
      }
      bot.entity.position = bot.entity.position.plus(dir.scaled(step));
    } else if (role.kind === "base") {
      // around the base: back and forth over 24 blocks, turning a little every pass
      const sign = Math.floor(t * step / 24) % 2 === 0 ? 1 : -1;
      bot.entity.position = pos.plus(dir.scaled(step * sign));
      if (t % 600 === 0) dir = new Vec3(Math.cos(t + idx), 0, Math.sin(t + idx));
    } else if (role.kind === "hyperloop") {
      // ride to the target, then back to the hub, then a new target on the other axis
      if (!bot.hyper || Math.hypot(bot.hyper[0] - pos.x, bot.hyper[1] - pos.z) < step) {
        const toHub = bot.hyper && !(bot.hyper[0] === 0 && bot.hyper[1] === 0);
        const r = role.radius, far = (rnd(t * 13 + idx) * 2 - 1) * r;
        bot.leg = (bot.leg || 0) + 1;
        bot.hyper = toHub ? [0, 0] : (bot.leg + idx) % 2 ? [Math.round(far), 0] : [0, Math.round(far)];
      }
      const dx = bot.hyper[0] - pos.x, dz = bot.hyper[1] - pos.z, d = Math.hypot(dx, dz);
      bot.entity.position = pos.plus(new Vec3(dx / d, 0, dz / d).scaled(Math.min(step, d)));
    } else if (role.kind === "tour") {
      const [tx, , tz] = tour[target];
      const d = Math.hypot(tx - pos.x, tz - pos.z);
      if (d < 3) target = (target + 1) % tour.length;
      else bot.entity.position = pos.plus(new Vec3((tx - pos.x) / d, 0, (tz - pos.z) / d).scaled(step));
    }
    await sleep(50);
  }
  return home;
}

function spawnBot(idx, role) {
  const name = `Bee_${String(idx).padStart(2, "0")}`;
  const bot = mineflayer.createBot({ host: "127.0.0.1", port: Number(port), username: name, version: "1.21.11", auth: "offline" });
  tickSync(bot);
  bot.acceptResourcePack = () => {};
  const w = bot._client.write.bind(bot._client);
  bot._client.write = (n, d) => (n === "resource_pack_receive" && typeof d.uuid !== "string") ? undefined : w(n, d);
  bot._client.on("add_resource_pack", (d) => { for (const r of [3, 4, 0]) w("resource_pack_receive", { uuid: d.uuid, result: r }); });
  bot.role = role;
  bots.push(bot);
  bot.once("spawn", () => {
    stats.joined++; stats.online++;
    act(bot, role, idx).catch((e) => console.log(`${name} error in act: ${e.message}`));
  });
  bot.on("kicked", (reason) => { stats.kicked++; console.log(`${name} kicked: ${JSON.stringify(reason).slice(0, 160)}`); });
  bot.on("error", (e) => { stats.errors++; console.log(`${name} error: ${e.message}`); });
  bot.on("end", () => { if (stats.online) stats.online--; });
}

roles.forEach((role, k) => setTimeout(() => spawnBot(k + 1, role), k * JOIN_GAP_MS));
const joinMs = roles.length * JOIN_GAP_MS;
console.log(`[bots] ${roles.length} bots, joining over ${Math.round(joinMs / 1000)} s, then ${minutes} min`);

const report = setInterval(() => {
  const per = {};
  for (const b of bots) if (b.entity) per[b.role.world] = (per[b.role.world] || 0) + 1;
  console.log(`[bots] joined ${stats.joined}/${roles.length}, online ${stats.online}, kicked ${stats.kicked}, errors ${stats.errors}`, JSON.stringify(per));
}, 30000);

setTimeout(() => {
  stopping = true;
  clearInterval(report);
  console.log(`[bots] done: joined ${stats.joined}/${roles.length}, kicked ${stats.kicked}, errors ${stats.errors}`);
  bots.forEach((b) => b.quit());
  setTimeout(() => process.exit(0), 3000);
}, joinMs + Number(minutes) * 60000);
