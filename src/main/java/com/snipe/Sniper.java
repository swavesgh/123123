package com.snipe;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;
import java.util.regex.*;

public class Sniper {
    enum State { OPEN, SCAN, CONFIRM, VERIFY, WAIT, RS_FIND, RS_SEND, RS_CONFIRM, RS_VERIFY }
    public static boolean running = false;
    public static String status = "idle";
    static boolean analyzing = false;   // first pass before sniping: scan only
    static boolean analyzeOnly = false; // one scan pass, then stop
    static State state = State.OPEN;
    static int cooldown, wait, page, tries;
    static Map<Integer, List<Market.Listing>> pages = new HashMap<>();
    static Map<String, Long> alerted = new HashMap<>();
    static Purchases.P pending;
    static double resellRef;
    static int pendingBefore, rsBefore, failStreak, sellFails, sellCount;
    static String pendingId = "", pendingSig = "", rsId = "";
    static boolean offline;
    static long sessionStart;
    static Map<String, Long> blocked = new HashMap<>();
    static Pattern priceP, sellerP;

    static void begin() {
        priceP = Pattern.compile(Config.I.priceRegex);
        sellerP = Pattern.compile(Config.I.sellerRegex);
        running = true; state = State.OPEN; cooldown = 0;
        sessionStart = System.currentTimeMillis(); failStreak = 0; sellFails = 0; offline = false;
    }

    public static void start() {
        begin();
        analyzeOnly = false;
        analyzing = Config.I.buyEnabled && Config.I.analyzeFirst && System.currentTimeMillis() - Market.lastCommit > 10 * 60_000L;
        status = "starting";
        msg(!Config.I.buyEnabled ? "Tracking started (buy mode is off)" : analyzing ? "Analyzing the AH first, then sniping" : "Sniping started");
    }
    /** One full scan with no buying, then stops by itself. */
    public static void analyze() {
        if (running) { msg("Stop the bot first"); return; }
        begin(); analyzeOnly = true; analyzing = false; status = "starting analysis";
        msg("Analyzing the AH (no buying)");
    }
    public static void stop() { running = false; analyzing = false; analyzeOnly = false; status = "idle"; msg("Stopped"); summary(); Market.save(); }

    /** Stop because something needs your attention (inventory full, purchases failing...). */
    static void halt(String why) {
        running = false; analyzing = false; analyzeOnly = false; status = "STOPPED: " + why;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) { mc.player.closeContainer(); Compat.sound(mc, 3); }
        say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY)
            .append(Component.literal("STOPPED: " + why).withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
        summary(); Market.save();
    }

    static void summary() {
        int n = 0, listed = 0; double spent = 0, exp = 0;
        for (Purchases.P p : Purchases.list) {
            if (p.time < sessionStart) continue;
            n++; spent += p.total;
            if (p.status.startsWith("listed")) { listed++; exp += p.listedFor * (1 - Config.I.ahTaxPercent / 100) - p.total; }
        }
        if (n > 0) msg("Session: bought " + n + " for " + Config.fmt(spent) + ", " + listed + " listed, expected profit if they sell: " + (exp < 0 ? "-" : "+") + Config.fmt(Math.abs(exp)));
    }

    // ---- inventory helpers
    static int countId(Minecraft mc, String id) {
        int c = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = Compat.invItem(mc, i);
            if (!s.isEmpty() && BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().equals(id)) c += s.getCount();
        }
        return c;
    }
    static int freeSlots(Minecraft mc) {
        int f = 0;
        for (int i = 0; i < 36; i++) if (Compat.invItem(mc, i).isEmpty()) f++;
        return f;
    }
    static String sig(Market.Listing l) { return l.key() + "|" + l.seller() + "|" + (long) l.total() + "|" + l.count(); }
    static boolean isBlocked(Market.Listing l) {
        Long t = blocked.get(sig(l));
        if (t == null) return false;
        if (System.currentTimeMillis() - t > 5 * 60_000L) { blocked.remove(sig(l)); return false; }
        return true;
    }
    /** Cheapest other listing of the same item seen so far this scan (per item). 0 = none. */
    static double lowestOther(Market.Listing l) {
        double best = 0;
        for (var pg : pages.values()) for (Market.Listing x : pg)
            if (x != l && x.key().equals(l.key()) && (best == 0 || x.unit() < best)) best = x.unit();
        return best;
    }
    /** Resell reference per item for this listing (unit price). 0 = unknown. */
    static double refUnit(String key, double lowestOther) {
        Market.Snap s = Market.latest(key);
        return switch (Config.I.resellMode) {
            case "AVERAGE" -> s != null ? s.avg : 0;
            case "MEDIAN" -> s != null ? s.median : 0;
            default -> lowestOther > 0 ? lowestOther : (s != null ? s.median : 0);
        };
    }
    /** AFK pre-check: only buy what we could relist at a profit. */
    static boolean worthIt(Market.Listing l) {
        if (!Config.I.afk) return true;
        double ref = refUnit(l.key(), lowestOther(l));
        if (ref <= 0) { warnOnce(l, "can't price a resale (no data)"); return false; }
        double total = Math.floor(ref * (1 - Config.I.undercutPercent / 100) * l.count());
        if (total * (1 - Config.I.ahTaxPercent / 100) < l.total() * (1 + Config.I.minProfitPercent / 100)) { warnOnce(l, "resale would not be profitable"); return false; }
        return true;
    }
    static boolean underLimit(Market.Listing l) {
        if (Config.I.maxUnsold <= 0) return true;
        double held = 0;
        for (Purchases.P p : Purchases.list) if (p.status.startsWith("held")) held += p.total;
        if (held + l.total() > Config.I.maxUnsold) { warnOnce(l, "unsold stock limit reached (" + Config.fmt(held) + ")"); return false; }
        return true;
    }

    static void msg(String s) { say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(s).withStyle(ChatFormatting.WHITE))); }
    static void warn(String s) { say(Component.literal("[Sniper] ").withStyle(ChatFormatting.DARK_GRAY).append(Component.literal(s).withStyle(ChatFormatting.YELLOW))); }
    static void say(Component c) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) Compat.say(mc, c);
    }

    /** Item identity of a stack, same format used for AH listings. */
    static String keyOf(ItemStack st) {
        return Config.vkey(st.getHoverName().getString(), BuiltInRegistries.ITEM.getKey(st.getItem()).getPath());
    }

    static Market.Listing listing(ItemStack st) {
        ItemLore lore = st.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
        double total = -1; String seller = "?";
        for (Component line : lore.lines()) {
            String s = line.getString();
            if (total < 0) {
                Matcher m = priceP.matcher(s);
                if (m.find()) {
                    try { total = Double.parseDouble(m.group(1).replace(",", "")) * Config.suffix(m.groupCount() >= 2 && m.group(2) != null ? m.group(2) : ""); }
                    catch (Exception ignored) {}
                }
            }
            if (seller.equals("?")) { Matcher sm = sellerP.matcher(s); if (sm.find()) seller = sm.group(1); }
        }
        if (total <= 0) return null;
        String name = st.getHoverName().getString();
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).getPath();
        return new Market.Listing(Config.vkey(name, id), name, id, st.has(DataComponents.CUSTOM_NAME), total, st.getCount(), seller);
    }

    static int findButton(ChestMenu h, String contains) {
        int size = h.getRowCount() * 9;
        for (int i = 0; i < size; i++) {
            ItemStack s = h.getSlot(i).getItem();
            if (!s.isEmpty() && s.getHoverName().getString().toLowerCase().contains(contains.toLowerCase())) return i;
        }
        return -1;
    }

    static void click(Minecraft mc, ChestMenu h, int slot) { Compat.click(mc, h, slot); cooldown = Config.I.delayTicks; }

    static void warnOnce(Market.Listing l, String why) {
        String sig = "skip|" + l.key() + "|" + l.seller() + "|" + (long) l.total();
        if (alerted.containsKey(sig)) return;
        alerted.put(sig, System.currentTimeMillis());
        warn("Skipped " + l.name() + " [" + l.id() + "] for " + Config.fmt(l.total()) + ": " + why);
    }

    /** Safety checks before buying: needs price data, and must not look too good to be true. */
    static boolean safe(Market.Listing l) {
        Market.Snap s = Market.latest(l.key());
        boolean haveData = s != null && s.total >= 3;
        if (Config.I.requireData && !haveData) { warnOnce(l, "no price data for this exact item yet (run Analyze)"); return false; }
        if (haveData && (1 - l.unit() / s.avg) * 100 > Config.I.scamGuardPercent) {
            warnOnce(l, Math.round((1 - l.unit() / s.avg) * 100) + "% under average, looks fake"); return false;
        }
        return true;
    }

    /** Chat alert for a listing far below the average of an item you are not sniping. */
    static void alertIfGood(Market.Listing l, int pageNo) {
        Config.Entry e = Config.I.entries.get(l.key());
        if (e != null && e.enabled) return;
        Market.Snap s = Market.latest(l.key());
        if (s == null || s.total < 3 || s.avg < Config.I.alertMinPrice) return;
        double disc = 1 - l.unit() / s.avg;
        if (disc * 100 < Config.I.alertPercent) return;
        String sig = l.key() + "|" + l.seller() + "|" + (long) l.total() + "|" + l.count();
        long now = System.currentTimeMillis();
        Long t = alerted.get(sig);
        if (t != null && now - t < 30 * 60_000L) return;
        if (alerted.size() > 500) alerted.clear();
        alerted.put(sig, now);
        say(Chat.deal(l, s, pageNo, false));
        if (Config.I.dealSound) Compat.sound(Minecraft.getInstance(), Chat.tier(Math.round(disc * 100)).sound());
    }

    /** Price to relist at (total for the stack), 0 = no data, -1 = not profitable. */
    static double resellPrice() {
        double ref = refUnit(pending.key, resellRef);
        if (ref <= 0) return 0;
        double total = Math.floor(ref * (1 - Config.I.undercutPercent / 100) * sellCount);
        double net = total * (1 - Config.I.ahTaxPercent / 100);
        double cost = pending.total / Math.max(1, pending.count) * sellCount;
        return net < cost * (1 + Config.I.minProfitPercent / 100) ? -1 : total;
    }

    static void bought(Minecraft mc) { state = State.VERIFY; wait = 0; cooldown = 4; status = "Verifying purchase"; }

    public static void tick(Minecraft mc) {
        if (!running) return;
        if (mc.player == null || mc.getConnection() == null) { offline = true; status = "Disconnected - waiting"; return; }
        if (offline) { offline = false; state = State.OPEN; cooldown = 100; status = "Reconnected"; return; }
        if (cooldown > 0) { cooldown--; return; }

        switch (state) {
            case OPEN -> {
                pages.clear(); page = 0; wait = 0; status = "Opening AH";
                Compat.command(mc, Config.I.command);
                state = State.SCAN; cooldown = Config.I.delayTicks * 2;
            }
            case SCAN -> {
                if (!(Compat.screen(mc) instanceof ContainerScreen gs)) { if (++wait > 80) state = State.OPEN; return; }
                wait = 0;
                ChestMenu h = gs.getMenu();
                int size = h.getRowCount() * 9;
                List<Market.Listing> list = new ArrayList<>();
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < size; i++) {
                    ItemStack st = h.getSlot(i).getItem();
                    if (st.isEmpty()) continue;
                    Market.Listing l = listing(st);
                    if (l != null) { list.add(l); slots.add(i); }
                }
                pages.put(page, list);
                int seen = 0; for (var p : pages.values()) seen += p.size();
                String mode = analyzeOnly || analyzing ? "Analyzing" : Config.I.buyEnabled ? "Sniping" : "Tracking";
                status = mode + " - page " + (page + 1) + ", " + seen + " listings";

                if (Config.I.alerts) for (Market.Listing l : list) alertIfGood(l, page);

                boolean buyNow = Config.I.buyEnabled && !analyzing && !analyzeOnly;
                if (buyNow) {
                    int best = -1; double bestUnit = Double.MAX_VALUE;
                    for (int j = 0; j < list.size(); j++) {
                        Market.Listing l = list.get(j);
                        Config.Entry e = Config.I.entries.get(l.key());
                        if (e == null || !e.enabled || e.maxPrice <= 0) continue;
                        if ((e.each ? l.unit() : l.total()) <= e.maxPrice && l.unit() < bestUnit && !isBlocked(l) && safe(l) && worthIt(l) && underLimit(l)) { best = j; bestUnit = l.unit(); }
                    }
                    if (best >= 0) {
                        Market.Listing l = list.get(best);
                        if (freeSlots(mc) < 1) { halt("Inventory is full - free some space and press Start"); return; }
                        resellRef = lowestOther(l);
                        pendingBefore = countId(mc, l.id()); pendingId = l.id(); pendingSig = sig(l);
                        pending = new Purchases.P();
                        pending.name = l.name(); pending.key = l.key(); pending.count = l.count(); pending.total = l.total(); pending.time = System.currentTimeMillis();
                        Market.Snap bs = Market.latest(l.key());
                        if (bs != null && bs.avg > 0) {
                            say(Chat.deal(l, bs, page, true));
                            if (Config.I.dealSound) Compat.sound(mc, Chat.tier(Math.round((1 - l.unit() / bs.avg) * 100)).sound());
                        } else msg("Buying " + l.name() + " x" + l.count() + " for " + Config.fmt(l.total()));
                        click(mc, h, slots.get(best));
                        state = State.CONFIRM; wait = 0;
                        return;
                    }
                }
                int next = findButton(h, Config.I.nextName);
                if (next >= 0 && page < Config.I.maxPages) { page++; click(mc, h, next); cooldown += 4; return; }

                List<Market.Listing> all = new ArrayList<>();
                for (var p : pages.values()) all.addAll(p);
                Market.commit(all);
                mc.player.closeContainer();
                if (analyzeOnly) {
                    running = false; analyzeOnly = false;
                    status = "Analysis done (" + all.size() + " listings)";
                    msg("Analysis complete: " + all.size() + " listings, " + Market.d.latest.size() + " items with price data");
                    Market.save();
                    return;
                }
                if (analyzing) { analyzing = false; msg("Analysis complete - sniping now"); }
                state = State.WAIT; cooldown = Config.I.refreshTicks; status = "Scan done (" + all.size() + " listings)";
            }
            case CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); bought(mc); return; }
                }
                if (++wait > 20) bought(mc);
            }
            case VERIFY -> {
                if (countId(mc, pendingId) > pendingBefore) {
                    failStreak = 0;
                    Purchases.add(pending);
                    if (Config.I.resellOn()) { state = State.RS_FIND; tries = 0; cooldown = Config.I.resellWaitTicks; status = "Preparing resell"; }
                    else { state = State.SCAN; cooldown += 8; }
                    return;
                }
                if (++wait > 30) {
                    failStreak++;
                    blocked.put(pendingSig, System.currentTimeMillis());
                    warn("Purchase of " + pending.name + " did not go through (" + failStreak + "/4). Listing gone, not enough coins, or wrong confirm button?");
                    if (failStreak >= 4) { halt("Purchases keep failing - check your coins, inventory and confirm button name"); return; }
                    if (Compat.screen(mc) != null) mc.player.closeContainer();
                    state = State.OPEN; cooldown = 10;
                }
            }
            case WAIT -> {
                state = State.OPEN;
                if (Config.I.resellOn()) {
                    long now = System.currentTimeMillis();
                    for (Purchases.P p : Purchases.list) {
                        if (p.status.startsWith("held") && p.retries < 6 && now - p.lastTry > Config.I.sweepMinutes * 60_000L) {
                            p.retries++; p.lastTry = now; pending = p; resellRef = 0;
                            state = State.RS_FIND; tries = 0; status = "Retrying held item " + p.name;
                            break;
                        }
                    }
                }
            }

            case RS_FIND -> {
                if (Compat.screen(mc) != null) mc.player.closeContainer();
                int found = -1;
                for (int i = 0; i < 36 && found < 0; i++) {
                    ItemStack s = Compat.invItem(mc, i);
                    if (!s.isEmpty() && s.getHoverName().getString().equalsIgnoreCase(pending.name)) found = i;
                }
                if (found < 0) {
                    if (++tries > 10) { pending.status = "bought (not found in inventory)"; Purchases.save(); state = State.OPEN; }
                    else cooldown = 10;
                    return;
                }
                ItemStack fs = Compat.invItem(mc, found);
                sellCount = fs.getCount();
                rsId = BuiltInRegistries.ITEM.getKey(fs.getItem()).getPath();
                double price = resellPrice();
                if (price <= 0) {
                    pending.status = price < 0 ? "held (resell not profitable)" : "held (no price data)";
                    Purchases.save(); msg(pending.name + ": " + pending.status); state = State.OPEN; return;
                }
                pending.listedFor = price;
                Compat.holdSlot(mc, found);
                state = State.RS_SEND; cooldown = 6; status = "Relisting " + pending.name;
            }
            case RS_SEND -> {
                rsBefore = countId(mc, rsId);
                Compat.command(mc, Config.I.sellCommand.replace("{price}", String.valueOf((long) pending.listedFor)));
                pending.status = "listing"; Purchases.save();
                msg("Relisting " + pending.name + " for " + Config.fmt(pending.listedFor));
                state = State.RS_CONFIRM; wait = 0; cooldown = Config.I.delayTicks * 3;
            }
            case RS_CONFIRM -> {
                if (Compat.screen(mc) instanceof ContainerScreen gs) {
                    int c = findButton(gs.getMenu(), Config.I.confirmName);
                    if (c >= 0) { click(mc, gs.getMenu(), c); cooldown += 10; state = State.RS_VERIFY; wait = 0; return; }
                }
                if (++wait > 20) { state = State.RS_VERIFY; wait = 0; }
            }
            case RS_VERIFY -> {
                if (countId(mc, rsId) < rsBefore) {
                    sellFails = 0; pending.status = "listed"; Purchases.save();
                    state = State.OPEN; cooldown = 10; return;
                }
                if (++wait > 30) {
                    sellFails++; pending.listedFor = 0; pending.status = "held (sell failed)"; Purchases.save();
                    warn("Listing " + pending.name + " failed (" + sellFails + "/3). AH listing limit reached or wrong sell command?");
                    if (sellFails >= 3) { halt("Listings keep failing - AH listing limit or wrong sell command"); return; }
                    if (Compat.screen(mc) != null) mc.player.closeContainer();
                    state = State.OPEN; cooldown = 10;
                }
            }
        }
    }
}
