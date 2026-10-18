// node --test lib.test.js
const test = require("node:test");
const assert = require("node:assert");
const { parseCommand, userId, parseDuration, humanDuration, ticketName, NEEDS, ALIASES } = require("./lib");

test("commands in Russian and English, any case", () => {
  assert.deepStrictEqual(parseCommand("!бан <@123456789012345678> спам"), { name: "ban", args: ["<@123456789012345678>", "спам"] });
  assert.deepStrictEqual(parseCommand("!BAN <@123456789012345678>"), { name: "ban", args: ["<@123456789012345678>"] });
  assert.strictEqual(parseCommand("!мут x 10м").name, "mute");
  assert.strictEqual(parseCommand("!таймаут x 10м").name, "mute");
  assert.strictEqual(parseCommand("!помощь").name, "help");
});

test("not our commands", () => {
  assert.strictEqual(parseCommand("бан @x"), null);
  assert.strictEqual(parseCommand("!привет"), null);
  assert.strictEqual(parseCommand("!"), null);
  assert.strictEqual(parseCommand(""), null);
});

test("mentions and ids", () => {
  assert.strictEqual(userId("<@123456789012345678>"), "123456789012345678");
  assert.strictEqual(userId("<@!123456789012345678>"), "123456789012345678");
  assert.strictEqual(userId("123456789012345678"), "123456789012345678");
  assert.strictEqual(userId("@Вася"), null);
  assert.strictEqual(userId("<#123456789012345678>"), null); // channel mention
});

test("durations", () => {
  assert.strictEqual(parseDuration("30м"), 30 * 60 * 1000);
  assert.strictEqual(parseDuration("2ч"), 2 * 3600 * 1000);
  assert.strictEqual(parseDuration("1д"), 86400 * 1000);
  assert.strictEqual(parseDuration("45s"), 45 * 1000);
  assert.strictEqual(parseDuration("10мин"), 600 * 1000);
  assert.strictEqual(parseDuration("29д"), null); // over Discord's 28-day limit
  assert.strictEqual(parseDuration("0м"), null);
  assert.strictEqual(parseDuration("спам"), null);
  assert.strictEqual(humanDuration(5400 * 1000), "1 ч 30 мин");
});

test("every command has a permission rule", () => {
  for (const name of new Set(Object.values(ALIASES))) assert.ok(name in NEEDS, name);
});

test("ticket names", () => {
  assert.strictEqual(ticketName(7), "жалоба-0007");
});
