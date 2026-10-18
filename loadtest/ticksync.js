// Minecraft 26.3 kicks with invalid_player_movement when a client sends two position packets
// without a client_tick_end ("tick_end") between them (ServerGamePacketListenerImpl.receivedPositionThisTick).
// Real clients send tick_end every tick; mineflayer 4.39 never does. This patches a bot to send it
// after every physics tick, and also before a position packet if one was already sent this tick.
module.exports = function tickSync(bot) {
  const client = bot._client;
  const write = client.write.bind(client);
  let sentPos = false;
  client.write = (name, data) => {
    if (name === "position" || name === "position_look") {
      if (sentPos) write("tick_end", {});
      sentPos = true;
    } else if (name === "tick_end") sentPos = false;
    return write(name, data);
  };
  bot.on("physicsTick", () => { if (client.state === "play") client.write("tick_end", {}); });
};
