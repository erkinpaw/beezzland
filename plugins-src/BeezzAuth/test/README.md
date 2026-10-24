# BeezzAuth end-to-end test

Runs a real login through a second Velocity on port 25570 against a fake Telegram Bot API, so no
real bot token is needed and the main proxy (25565) is not touched.

1. `python fake_telegram.py` (listens on 127.0.0.1:8099)
2. Copy the proxy folder to `proxy-auth`, set `bind = "127.0.0.1:25570"` and `login-ratelimit = 0`
   in its `velocity.toml`, put `BeezzAuth-*.jar` into its `plugins/`, and in
   `plugins/beezzauth/config.yml` enable telegram with `token: "TEST"` and `api-base: "http://127.0.0.1:8099"`.
3. `node authtest.js link` — new player gets a code, links it via the fake bot, gets in.
4. Set `session-hours: 0`, reset `sessionUntil` in `accounts.json`, restart the test proxy, then
   `node authtest.js confirm` — «Это я», «Это не я», stranger's press, timeout, late «Это я».

5. VK: `python fake_vk.py` (127.0.0.1:8098), enable `vk` in the test config with
   `api-base: "http://127.0.0.1:8098/method"`, then `node vktest.js` — link by a VK message,
   then a login from 127.0.0.2 (a «new IP» without touching the network) confirmed by a VK callback button.
6. Discord: `python fake_discord.py` (REST + gateway WebSocket on 127.0.0.1:8097), enable `discord` with
   `api-base: "http://127.0.0.1:8097/api"`, then `node discordtest.js` — DM link, server-channel messages
   ignored, button confirmation from a new IP. With `channel: "регистрация"` in the test config,
   `node channeltest.js` — one «Привязать аккаунт» button in #регистрация (not re-posted after a restart),
   button → window for the code, private answers, the moderation bot's ticket buttons left alone.

Uses mineflayer from `../../../loadtest/node_modules` (`npm install` there first).
Last run 18.10.2026: Telegram 11/11, VK 9/9, Discord 9/9, Discord channel 7/7, brute force 4/4 — all PASS. `bruteforcetest.js` needs only the fake Telegram.
