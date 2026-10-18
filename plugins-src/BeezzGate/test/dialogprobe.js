// Checks that a new player gets the quiz window on the loading screen and is NOT let into the world
// without answering. (mineflayer can receive dialogs but not click them: clicks are checked by hand.)
// usage: node dialogprobe.js [host] [port]
const loadtest = process.env.LOADTEST || require("path").join(__dirname, "../../../loadtest/");
const mineflayer = require(loadtest + "node_modules/mineflayer");
const tickSync = require(loadtest + "ticksync");
const [host = "127.0.0.1", port = "25580"] = process.argv.slice(2);
const check = (label, ok) => console.log(ok ? "  PASS" : "  FAIL", label);

const bot = mineflayer.createBot({ host, port: Number(port), username: "GateProbe" + (Date.now() % 10000), version: "1.21.11", auth: "offline" });
tickSync(bot);
let dialog = null, spawned = false;
bot._client.on("packet", (data, meta) => {
  if (meta.name === "show_dialog" && !dialog) dialog = { state: meta.state, text: JSON.stringify(data) };
});
bot.once("spawn", () => { spawned = true; });
bot.on("kicked", (r) => console.log("  kicked:", JSON.stringify(r).slice(0, 160)));
setTimeout(() => {
  check("quiz window arrives during loading (configuration phase)", dialog && dialog.state === "configuration");
  check("window shows the first question", dialog && /Вопрос 1 из/.test(dialog.text) && /Правила BeezzLand/.test(dialog.text));
  check("player is not in the world while the quiz is open", !spawned);
  process.exit(0);
}, 12000);
