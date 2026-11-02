# AH Sniper (Fabric, Minecraft 26.2)

## Get the jar with GitHub
1. Upload ALL files and folders (including the hidden `.github` folder) to your repo, replacing the old ones.
2. Open the **Actions** tab and wait for the green tick (or press "Run workflow").
3. Open the run, scroll to **Artifacts**, download `ahsniper-jar`, unzip, use `ahsniper-1.4.1.jar` (not -sources).
Requires Java 25, Fabric Loader 0.19+, Fabric API for 26.2.

## New in 1.4.1
- Deal alerts and snipe messages are multi-line, colour-coded by how far under average the listing is
  (Decent aqua, Great green, Huge gold, Insane red, Legendary purple), with a bar, profit estimate and a sound.
  Turn the sound off with `"dealSound": false` in config/snipe.json.
- Selling: hold an item and type `/ah hand` (no price), press the Sell key (default `\`), or use the
  Purchases tab buttons. The mod holds the item and runs `ah hand <price>` using Settings > "Sell/resell price"
  (Lowest BIN / Average / Median) and Undercut %. Sell command default is now `ah hand {price}`.

## AFK mode (1.4.1) - Settings tab > "AFK mode" ON
- Forces auto-resell, and only buys a listing if relisting it (per your Sell/resell price, undercut, tax and min profit) would be profitable.
- Every buy is verified (item must actually arrive) and every relist is verified (item must actually leave your inventory).
- Items it could not sell are retried every `sweepMinutes` (default 5, config/snipe.json), up to 6 times.
- Failed listings are blocked for 5 minutes; after 4 failed buys or 3 failed relists in a row it STOPS with a sound and a red message.
- Stops if your inventory is full. "Max unsold" pauses buying when unsold stock exceeds that value (0 = off).
- Resumes by itself after a disconnect/reconnect. Prints a session profit summary when it stops.
- Tip: press F3+P to turn off "pause on lost focus" so alt-tabbing does not open the pause menu.

## 1.4.1
- Item textures next to every row in the Snipe List / Economy / Purchases tabs and in the detail panel.
