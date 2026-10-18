// BeezzLand moderation bot: !бан / !кик / !мут / !варн … and complaint tickets.
// Run: put DISCORD_TOKEN=… into discord/bot/.env (never commit it), then `node bot.js`.
const fs = require("fs");
const path = require("path");
const {
  Client, GatewayIntentBits, PermissionFlagsBits, ChannelType, EmbedBuilder, ActionRowBuilder, ButtonBuilder,
  ButtonStyle, ModalBuilder, TextInputBuilder, TextInputStyle, AttachmentBuilder, MessageFlags,
} = require("discord.js");
const { NEEDS, parseCommand, userId, parseDuration, humanDuration, ticketName } = require("./lib");

const HERE = __dirname;
const cfg = JSON.parse(fs.readFileSync(path.join(HERE, "config.json"), "utf8"));
const STATE_FILE = path.join(HERE, "state.json");
const HONEY = 0xfacc15, RED = 0xf87171, GREEN = 0x34d399, BLUE = 0x60a5fa;

// token: environment first, then a local .env file (git-ignored)
function token() {
  if (process.env.DISCORD_TOKEN) return process.env.DISCORD_TOKEN.trim();
  const env = path.join(HERE, ".env");
  if (fs.existsSync(env)) {
    const m = /^DISCORD_TOKEN\s*=\s*"?([^"\r\n]+)"?/m.exec(fs.readFileSync(env, "utf8"));
    if (m) return m[1].trim();
  }
  console.error("Нет токена: создай discord/bot/.env со строкой DISCORD_TOKEN=... (или переменную окружения)");
  process.exit(1);
}

let state = { tickets: 0, panels: {}, warns: {} };
try { state = { ...state, ...JSON.parse(fs.readFileSync(STATE_FILE, "utf8")) }; } catch { /* first run */ }
const save = () => fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages, GatewayIntentBits.MessageContent],
});

const byName = (guild, name, type = ChannelType.GuildText) =>
  guild.channels.cache.find((c) => c.name === name && c.type === type);

const embed = (color, title, text) => new EmbedBuilder().setColor(color).setTitle(title).setDescription(text || null);

async function log(guild, color, title, fields) {
  const ch = byName(guild, cfg.logChannel);
  if (!ch) return;
  const e = embed(color, title).setTimestamp();
  for (const [k, v] of Object.entries(fields)) if (v) e.addFields({ name: k, value: String(v).slice(0, 1024), inline: k !== "Причина" });
  await ch.send({ embeds: [e] }).catch((err) => console.warn("log:", err.message));
}

/** Can `actor` act on `target`? Discord itself also refuses if the bot's role is lower. */
function outranks(actor, target) {
  if (actor.guild.ownerId === actor.id) return true;
  if (target.guild.ownerId === target.id) return false;
  return actor.roles.highest.comparePositionTo(target.roles.highest) > 0;
}

// ------------------------------------------------------------------ commands

const HELP = [
  "`!бан @ник [причина]` — бан (удаляет его сообщения за последний час)",
  "`!разбан <id> [причина]` — снять бан",
  "`!кик @ник [причина]` — выгнать с сервера",
  "`!мут @ник 30м [причина]` — тайм-аут: `с`, `м`, `ч`, `д` (до 28 д)",
  "`!размут @ник` — снять тайм-аут",
  "`!варн @ник причина` — предупреждение, `!варны @ник` — история",
  "`!очистить 20` — удалить последние сообщения (до 100)",
].join("\n");

async function run(msg, { name, args }) {
  const need = NEEDS[name];
  if (need && !msg.member.permissions.has(PermissionFlagsBits[need])) {
    return msg.reply({ embeds: [embed(RED, "Нет прав", "Эта команда только для команды сервера.")] });
  }
  if (name === "help") return msg.reply({ embeds: [embed(HONEY, "Команды модерации", HELP)] });

  if (name === "clear") {
    const n = Math.min(Number(args[0]) || 0, 100);
    if (n < 1) return msg.reply("Сколько удалить? Например: `!очистить 20`");
    const gone = await msg.channel.bulkDelete(n + 1, true).catch(() => null);
    await log(msg.guild, BLUE, "Очистка чата", { Модератор: msg.author.toString(), Канал: msg.channel.toString(), Удалено: gone ? gone.size - 1 : 0 });
    return;
  }

  const id = userId(args[0]);
  if (!id) return msg.reply("Укажи участника: `!" + name + " @ник ...`");
  let rest = args.slice(1);
  const actor = msg.member;

  if (name === "unban") {
    const reason = rest.join(" ") || "без причины";
    const ok = await msg.guild.members.unban(id, `${msg.author.tag}: ${reason}`).then(() => true).catch(() => false);
    if (!ok) return msg.reply("Этого пользователя нет в списке банов.");
    await log(msg.guild, GREEN, "Разбан", { Участник: `<@${id}>`, Модератор: actor.toString(), Причина: reason });
    return msg.reply({ embeds: [embed(GREEN, "Бан снят", `<@${id}>`)] });
  }

  const target = await msg.guild.members.fetch(id).catch(() => null);
  if (target && !outranks(actor, target)) {
    return msg.reply({ embeds: [embed(RED, "Нельзя", "У него роль не ниже твоей.")] });
  }

  if (name === "warns") {
    const list = state.warns[id] || [];
    const text = list.length
      ? list.map((w, i) => `**${i + 1}.** ${w.reason} — <@${w.by}>, <t:${Math.floor(w.at / 1000)}:d>`).join("\n")
      : "Предупреждений нет.";
    return msg.reply({ embeds: [embed(HONEY, `Предупреждения`, `<@${id}>\n${text}`)] });
  }

  let ms = null;
  if (name === "mute") {
    ms = parseDuration(rest[0]);
    if (!ms) return msg.reply("Укажи срок: `!мут @ник 30м причина` (с, м, ч, д — до 28 д)");
    rest = rest.slice(1);
  }
  const reason = rest.join(" ") || "без причины";
  const audit = `${msg.author.tag}: ${reason}`;

  if (!target && name !== "ban") return msg.reply("Этого участника нет на сервере.");
  try {
    if (name === "ban") {
      if (target) await target.send(`Ты забанен на Discord-сервере BeezzLand. Причина: ${reason}`).catch(() => {});
      const del = Math.floor((parseDuration(cfg.deleteMessagesOnBan) || 0) / 1000);
      await msg.guild.members.ban(id, { reason: audit, deleteMessageSeconds: del });
      await log(msg.guild, RED, "Бан", { Участник: `<@${id}>`, Модератор: actor.toString(), Причина: reason });
      return msg.channel.send({ embeds: [embed(RED, "Забанен", `<@${id}> — ${reason}`)] });
    }
    if (name === "kick") {
      await target.send(`Тебя выгнали с Discord-сервера BeezzLand. Причина: ${reason}`).catch(() => {});
      await target.kick(audit);
      await log(msg.guild, RED, "Кик", { Участник: target.toString(), Модератор: actor.toString(), Причина: reason });
      return msg.channel.send({ embeds: [embed(RED, "Выгнан", `${target} — ${reason}`)] });
    }
    if (name === "mute") {
      await target.timeout(ms, audit);
      await log(msg.guild, HONEY, "Тайм-аут", { Участник: target.toString(), Модератор: actor.toString(), Срок: humanDuration(ms), Причина: reason });
      return msg.channel.send({ embeds: [embed(HONEY, "Тайм-аут", `${target} на ${humanDuration(ms)} — ${reason}`)] });
    }
    if (name === "unmute") {
      await target.timeout(null, audit);
      await log(msg.guild, GREEN, "Тайм-аут снят", { Участник: target.toString(), Модератор: actor.toString() });
      return msg.channel.send({ embeds: [embed(GREEN, "Тайм-аут снят", target.toString())] });
    }
    if (name === "warn") {
      if (!rest.length) return msg.reply("За что? `!варн @ник причина`");
      (state.warns[id] ||= []).push({ reason, by: msg.author.id, at: Date.now() });
      save();
      const count = state.warns[id].length;
      await target.send(`Предупреждение на BeezzLand (${count}-е): ${reason}`).catch(() => {});
      await log(msg.guild, HONEY, "Предупреждение", { Участник: target.toString(), Модератор: actor.toString(), Всего: count, Причина: reason });
      return msg.channel.send({ embeds: [embed(HONEY, `Предупреждение №${count}`, `${target} — ${reason}`)] });
    }
  } catch (err) {
    // Discord refuses when the bot's own role is not above the target, or it lacks the permission
    return msg.reply({ embeds: [embed(RED, "Discord не дал это сделать", "Проверь, что роль бота выше роли участника и у бота есть нужное право.\n`" + err.message + "`")] });
  }
}

client.on("messageCreate", async (msg) => {
  if (msg.author.bot || !msg.guild) return;
  const cmd = parseCommand(msg.content, cfg.prefix);
  if (!cmd) return;
  try {
    await run(msg, cmd);
  } catch (err) {
    console.error(err);
  }
});

// ------------------------------------------------------------------ tickets

const isStaff = (member) =>
  member.permissions.has(PermissionFlagsBits.ModerateMembers) ||
  member.roles.cache.some((r) => cfg.staffRoles.includes(r.name));

async function ensurePanel(guild) {
  const ch = byName(guild, cfg.ticketPanelChannel);
  if (!ch) return console.warn(`${guild.name}: нет канала #${cfg.ticketPanelChannel}, панель жалоб не создана`);
  const known = state.panels[guild.id];
  if (known && (await ch.messages.fetch(known).catch(() => null))) return;
  const sent = await ch.send({
    embeds: [embed(HONEY, "Жалоба на игрока",
      "Нарушили правила? Нажми кнопку и опиши, что случилось.\n" +
      "Откроется приватный канал: его видишь только ты и команда сервера.")],
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId("ticket:open").setLabel("Подать жалобу").setStyle(ButtonStyle.Primary))],
  });
  state.panels[guild.id] = sent.id;
  save();
}

async function openTicket(i) {
  const offender = i.fields.getTextInputValue("offender");
  const what = i.fields.getTextInputValue("what");
  const guild = i.guild;
  state.tickets += 1;
  save();
  const category = byName(guild, cfg.ticketCategory, ChannelType.GuildCategory);
  const staffRoles = guild.roles.cache.filter((r) => cfg.staffRoles.includes(r.name));
  const view = [PermissionFlagsBits.ViewChannel, PermissionFlagsBits.SendMessages, PermissionFlagsBits.ReadMessageHistory, PermissionFlagsBits.AttachFiles];
  const ch = await guild.channels.create({
    name: ticketName(state.tickets),
    type: ChannelType.GuildText,
    parent: category?.id,
    topic: `author:${i.user.id}`,
    permissionOverwrites: [
      { id: guild.roles.everyone.id, deny: [PermissionFlagsBits.ViewChannel] },
      { id: i.user.id, allow: view },
      { id: client.user.id, allow: [...view, PermissionFlagsBits.ManageChannels] },
      ...staffRoles.map((r) => ({ id: r.id, allow: view })),
    ],
  });
  await ch.send({
    content: `${i.user} ${staffRoles.map((r) => r.toString()).join(" ")}`,
    embeds: [embed(HONEY, `Жалоба №${state.tickets}`, null).addFields(
      { name: "От", value: i.user.toString(), inline: true },
      { name: "На кого", value: offender.slice(0, 1024), inline: true },
      { name: "Что случилось", value: what.slice(0, 1024) },
    ).setFooter({ text: "Можно приложить скриншоты сюда же. Команда ответит здесь." })],
    components: [new ActionRowBuilder().addComponents(
      new ButtonBuilder().setCustomId("ticket:close").setLabel("Закрыть жалобу").setStyle(ButtonStyle.Danger))],
  });
  await log(guild, BLUE, "Новая жалоба", { Номер: state.tickets, От: i.user.toString(), "На кого": offender, Канал: ch.toString() });
  await i.reply({ content: `Жалоба создана: ${ch}`, flags: MessageFlags.Ephemeral });
}

async function closeTicket(i) {
  const ch = i.channel;
  const author = /author:(\d+)/.exec(ch.topic || "")?.[1];
  if (i.user.id !== author && !isStaff(i.member)) {
    return i.reply({ content: "Закрыть жалобу может автор или команда сервера.", flags: MessageFlags.Ephemeral });
  }
  await i.reply({ embeds: [embed(GREEN, "Жалоба закрыта", `Закрыл ${i.user}. Канал удалится через 10 секунд.`)] });
  // transcript: oldest first, up to 1000 messages
  let all = [], before;
  for (let k = 0; k < 10; k++) {
    const batch = await ch.messages.fetch({ limit: 100, before }).catch(() => null);
    if (!batch || !batch.size) break;
    all = all.concat([...batch.values()]);
    before = batch.last().id;
  }
  const lines = all.reverse().map((m) => {
    const files = [...m.attachments.values()].map((a) => " [файл: " + a.url + "]").join("");
    const emb = m.embeds.map((e) => ` [${e.title || ""}: ${(e.description || "").replace(/\n/g, " ")} ${e.fields.map((f) => f.name + "=" + f.value).join("; ")}]`).join("");
    return `[${m.createdAt.toISOString().slice(0, 16).replace("T", " ")}] ${m.author.tag}: ${m.content}${emb}${files}`;
  });
  const logCh = byName(i.guild, cfg.logChannel);
  if (logCh) {
    await logCh.send({
      embeds: [embed(GREEN, `${ch.name} закрыта`, `Закрыл ${i.user}, сообщений: ${lines.length}`)],
      files: [new AttachmentBuilder(Buffer.from(lines.join("\n"), "utf8"), { name: `${ch.name}.txt` })],
    }).catch((err) => console.warn("transcript:", err.message));
  }
  setTimeout(() => ch.delete(`жалоба закрыта: ${i.user.tag}`).catch(() => {}), 10_000);
}

client.on("interactionCreate", async (i) => {
  try {
    if (i.isButton() && i.customId === "ticket:open") {
      const modal = new ModalBuilder().setCustomId("ticket:modal").setTitle("Жалоба на игрока");
      modal.addComponents(
        new ActionRowBuilder().addComponents(new TextInputBuilder().setCustomId("offender").setLabel("Ник нарушителя (в игре или в Discord)")
          .setStyle(TextInputStyle.Short).setMaxLength(100).setRequired(true)),
        new ActionRowBuilder().addComponents(new TextInputBuilder().setCustomId("what").setLabel("Что случилось? Где и когда?")
          .setStyle(TextInputStyle.Paragraph).setMaxLength(1500).setRequired(true)),
      );
      return i.showModal(modal);
    }
    if (i.isModalSubmit() && i.customId === "ticket:modal") return openTicket(i);
    if (i.isButton() && i.customId === "ticket:close") return closeTicket(i);
  } catch (err) {
    console.error(err);
    if (i.isRepliable() && !i.replied) i.reply({ content: "Что-то пошло не так, попробуй ещё раз.", flags: MessageFlags.Ephemeral }).catch(() => {});
  }
});

client.once("clientReady", async () => {
  console.log(`BeezzLand bot online as ${client.user.tag}, servers: ${client.guilds.cache.size}`);
  for (const guild of client.guilds.cache.values()) await ensurePanel(guild).catch((e) => console.warn("panel:", e.message));
});

client.login(token());
