// Pure helpers for the BeezzLand moderation bot: no Discord calls here, so they are unit-testable.

/** Russian and English names of every command → canonical name. */
const ALIASES = {
  бан: "ban", ban: "ban",
  разбан: "unban", unban: "unban",
  кик: "kick", kick: "kick",
  мут: "mute", mute: "mute", таймаут: "mute", timeout: "mute",
  размут: "unmute", unmute: "unmute",
  варн: "warn", warn: "warn", пред: "warn",
  варны: "warns", warns: "warns",
  очистить: "clear", clear: "clear", purge: "clear",
  помощь: "help", help: "help", команды: "help",
};

/** Discord permission each command needs (names from discord.js PermissionFlagsBits). */
const NEEDS = {
  ban: "BanMembers", unban: "BanMembers", kick: "KickMembers",
  mute: "ModerateMembers", unmute: "ModerateMembers", warn: "ModerateMembers", warns: "ModerateMembers",
  clear: "ManageMessages", help: null,
};

/**
 * "!бан <@123> спам в чате" → { name: "ban", args: ["<@123>", "спам", "в", "чате"] }
 * Returns null for anything that isn't one of our commands.
 */
function parseCommand(content, prefix = "!") {
  if (!content || !content.startsWith(prefix)) return null;
  const parts = content.slice(prefix.length).trim().split(/\s+/);
  const name = ALIASES[(parts.shift() || "").toLowerCase()];
  return name ? { name, args: parts } : null;
}

/** "<@123>", "<@!123>" or a bare snowflake → "123"; anything else → null. */
function userId(arg) {
  const m = /^<@!?(\d{15,22})>$|^(\d{15,22})$/.exec(arg || "");
  return m ? m[1] || m[2] : null;
}

const UNITS = { с: 1, s: 1, сек: 1, м: 60, m: 60, мин: 60, ч: 3600, h: 3600, час: 3600, д: 86400, d: 86400, дн: 86400, н: 604800, w: 604800, нед: 604800 };

/** "30м" / "2ч" / "1д" / "45s" → milliseconds, or null if it isn't a duration. Discord caps timeouts at 28 days. */
function parseDuration(arg) {
  const m = /^(\d+)\s*([a-zа-яё]+)$/i.exec(arg || "");
  if (!m) return null;
  const unit = UNITS[m[2].toLowerCase()];
  if (!unit) return null;
  const ms = Number(m[1]) * unit * 1000;
  return ms > 0 && ms <= 28 * 86400 * 1000 ? ms : null;
}

/** 5400000 → "1 ч 30 мин" */
function humanDuration(ms) {
  let s = Math.round(ms / 1000);
  const out = [];
  for (const [u, name] of [[86400, "д"], [3600, "ч"], [60, "мин"], [1, "с"]]) {
    if (s >= u) { out.push(`${Math.floor(s / u)} ${name}`); s %= u; }
  }
  return out.join(" ") || "0 с";
}

/** "жалоба-0007" for ticket number 7. */
function ticketName(n) {
  return `жалоба-${String(n).padStart(4, "0")}`;
}

module.exports = { ALIASES, NEEDS, parseCommand, userId, parseDuration, humanDuration, ticketName };
