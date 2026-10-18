// one bot that walks forward for 20 s, to check it stays online: node probe.js [version=1.21.11]
const mineflayer = require("mineflayer");
const tickSync = require("./ticksync");
const version = process.argv[2] || "1.21.11";
const bot = mineflayer.createBot({ host: "127.0.0.1", port: 25565, username: "Probe", version, auth: "offline" });
tickSync(bot);
bot.on("spawn", () => { console.log("spawn at", bot.entity.position); bot.setControlState("forward", true); bot.setControlState("sprint", true); });
bot.on("kicked", (r) => { console.log("kicked", JSON.stringify(r).match(/disconnect\.[a-z_]+/)?.[0] || JSON.stringify(r).slice(0, 200)); process.exit(); });
bot.on("error", (e) => console.log("error", e.message));
setTimeout(() => { console.log("still online after 20s at", bot.entity?.position); process.exit(); }, 20000);
