package com.snipe;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.*;

public class SnipeScreen extends Screen {
    static final int W = 440, H = 236;
    static int tab = 0, listPage = 0, window = 1, sort = 0, selPurchase = -1;
    static String selKey = null, search = "";
    static boolean eachFlag = true, customOnly = false;
    static final long[] WINDOWS = {3600_000L, 86400_000L, 7 * 86400_000L};
    static final String[] WNAMES = {"Past hour", "Past 24h", "Past 7 days"};
    static final String[] SORTS = {"Priciest", "Cheapest", "A-Z", "Listings"};
    static final String[] MODES = {"LOWEST", "AVERAGE", "MEDIAN"};

    int x0, y0;
    List<Catalog.Row> allRows = List.of(), view = List.of();
    Map<String, Integer> nameCount = new HashMap<>();
    Button[] rows = new Button[8], winBtns = new Button[3];
    Button sortBtn, addBtn, delBtn, eachBtn, customBtn;
    EditBox searchBox, priceBox;
    String pageLabel = "1/1";
    List<Object[]> labels = new ArrayList<>();

    public SnipeScreen() { super(Component.literal("AH Sniper")); }

    // ---------------------------------------------------------------- layout
    Button btn(Component c, int x, int y, int w, int h, Runnable r) {
        Button b = Button.builder(c, bb -> r.run()).bounds(x, y, w, h).build();
        addRenderableWidget(b);
        return b;
    }
    Component txt(String s) { return Component.literal(s); }

    @Override protected void init() {
        x0 = (width - W) / 2; y0 = (height - H) / 2; labels.clear();
        String[] tn = {"Snipe List", "Economy", "Purchases", "Settings"};
        int[] tw = {72, 60, 66, 58};
        int tx = x0 + 6;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            btn(Component.literal(tn[i]).withStyle(tab == i ? ChatFormatting.AQUA : ChatFormatting.WHITE), tx, y0 + 5, tw[i], 18,
                () -> { tab = idx; listPage = 0; rebuildWidgets(); });
            tx += tw[i] + 4;
        }
        btn(txt("Analyze"), x0 + 280, y0 + 5, 58, 18, () -> { Sniper.analyze(); rebuildWidgets(); });
        btn(Component.literal(Sniper.running ? "Stop" : "Start sniping").withStyle(Sniper.running ? ChatFormatting.RED : ChatFormatting.GREEN),
            x0 + W - 96, y0 + 5, 90, 18, () -> { if (Sniper.running) Sniper.stop(); else Sniper.start(); rebuildWidgets(); });

        if (tab <= 1) initItems(); else if (tab == 2) initPurchases(); else initSettings();
    }

    void makeRows() {
        for (int i = 0; i < 8; i++) { final int idx = i; rows[i] = btn(Component.empty(), x0 + 24, y0 + 50 + i * 20, 152, 18, () -> selectRow(idx)); }
        btn(txt("<"), x0 + 6, y0 + 212, 20, 16, () -> { listPage--; refreshList(); });
        btn(txt(">"), x0 + 28, y0 + 212, 20, 16, () -> { listPage++; refreshList(); });
    }

    void initItems() {
        allRows = Catalog.all();
        nameCount.clear();
        for (Catalog.Row r : allRows) nameCount.merge(Config.key(r.name()), 1, Integer::sum);
        searchBox = new EditBox(font, x0 + 6, y0 + 28, 56, 18, txt("Search"));
        searchBox.setHint(txt("Search..."));
        searchBox.setValue(search);
        searchBox.setResponder(s -> { if (!s.equals(search)) { search = s; listPage = 0; refreshList(); } });
        addRenderableWidget(searchBox);
        customBtn = btn(Component.literal(customOnly ? "Custom" : "All").withStyle(customOnly ? ChatFormatting.AQUA : ChatFormatting.WHITE), x0 + 66, y0 + 28, 52, 18, () -> {
            customOnly = !customOnly;
            customBtn.setMessage(Component.literal(customOnly ? "Custom" : "All").withStyle(customOnly ? ChatFormatting.AQUA : ChatFormatting.WHITE));
            listPage = 0; refreshList(); });
        sortBtn = btn(txt(SORTS[sort]), x0 + 122, y0 + 28, 54, 18, () -> {
            sort = (sort + 1) % SORTS.length; sortBtn.setMessage(txt(SORTS[sort])); listPage = 0; refreshList(); });
        makeRows();
        for (int i = 0; i < 3; i++) { final int idx = i; winBtns[i] = btn(txt(WNAMES[i]), x0 + 192 + i * 78, y0 + 28, 76, 18, () -> { window = idx; refreshWindowButtons(); }); }
        refreshWindowButtons();

        priceBox = new EditBox(font, x0 + 256, y0 + 210, 62, 18, txt("Price"));
        priceBox.setHint(txt("price"));
        addRenderableWidget(priceBox);
        eachBtn = btn(txt("each"), x0 + 322, y0 + 210, 38, 18, () -> { eachFlag = !eachFlag; eachBtn.setMessage(txt(eachFlag ? "each" : "total")); });
        addBtn = btn(txt("Add"), x0 + 364, y0 + 210, 34, 18, this::addSnipe);
        delBtn = btn(txt("Off"), x0 + 402, y0 + 210, 32, 18, this::removeSnipe);
        refreshList(); refreshDetail();
    }

    void initPurchases() {
        makeRows(); refreshList();
        btn(txt("Sell selected"), x0 + 192, y0 + 210, 90, 18, () -> {
            if (selPurchase < 0 || selPurchase >= Purchases.list.size()) return;
            Purchases.P p = Purchases.list.get(selPurchase);
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            Compat.open(mc, null); Seller.sellPurchase(mc, p);
        });
        btn(txt("Sell hand"), x0 + 286, y0 + 210, 66, 18, () -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            Compat.open(mc, null); Seller.sellHeld(mc);
        });
    }

    // ------------------------------------------------------------ settings tab
    void toggle(String label, int x, int y, Supplier<String> text, Runnable act) {
        Button b = Button.builder(txt(text.get()), bb -> { act.run(); bb.setMessage(txt(text.get())); Config.save(); }).bounds(x + 112, y, 92, 18).build();
        addRenderableWidget(b); labels.add(new Object[]{label, x, y + 5});
    }
    void field(String label, int x, int y, String val, Consumer<String> on) {
        EditBox e = new EditBox(font, x + 112, y, 92, 18, txt(label));
        e.setMaxLength(64); e.setValue(val); e.setResponder(on);
        addRenderableWidget(e); labels.add(new Object[]{label, x, y + 5});
    }
    void num(String label, int x, int y, double val, DoubleConsumer on) {
        field(label, x, y, val == Math.floor(val) ? String.valueOf((long) val) : String.valueOf(val), s -> { try { on.accept(Double.parseDouble(s.trim())); } catch (Exception ignored) {} });
    }
    void inum(String label, int x, int y, int val, IntConsumer on) {
        field(label, x, y, String.valueOf(val), s -> { try { on.accept(Integer.parseInt(s.trim())); } catch (Exception ignored) {} });
    }

    void initSettings() {
        int ax = x0 + 8, bx = x0 + 226, y = y0 + 31, st = 20;
        Config c = Config.I;
        toggle("Buy mode", ax, y, () -> c.buyEnabled ? "ON (buys)" : "OFF (scan)", () -> c.buyEnabled = !c.buyEnabled);
        toggle("Auto resell", ax, y + st, () -> c.resell ? "ON" : "OFF", () -> c.resell = !c.resell);
        toggle("Sell/resell price", ax, y + st * 2, () -> switch (c.resellMode) { case "AVERAGE" -> "Average"; case "MEDIAN" -> "Median"; default -> "Lowest BIN"; },
            () -> c.resellMode = MODES[(Arrays.asList(MODES).indexOf(c.resellMode) + 1) % MODES.length]);
        num("Undercut %", ax, y + st * 3, c.undercutPercent, v -> c.undercutPercent = v);
        num("Min profit %", ax, y + st * 4, c.minProfitPercent, v -> c.minProfitPercent = v);
        num("AH tax %", ax, y + st * 5, c.ahTaxPercent, v -> c.ahTaxPercent = v);
        toggle("Analyze first", ax, y + st * 6, () -> c.analyzeFirst ? "ON" : "OFF", () -> c.analyzeFirst = !c.analyzeFirst);
        toggle("Deal alerts", ax, y + st * 7, () -> c.alerts ? "ON" : "OFF", () -> c.alerts = !c.alerts);
        toggle("Need price data", ax, y + st * 8, () -> c.requireData ? "ON" : "OFF", () -> c.requireData = !c.requireData);
        toggle("AFK mode", ax, y + st * 9, () -> c.afk ? "ON" : "OFF", () -> c.afk = !c.afk);
        inum("Max pages", bx, y, c.maxPages, v -> c.maxPages = v);
        inum("Click delay", bx, y + st, c.delayTicks, v -> c.delayTicks = v);
        inum("Refresh wait", bx, y + st * 2, c.refreshTicks, v -> c.refreshTicks = v);
        inum("Resell wait", bx, y + st * 3, c.resellWaitTicks, v -> c.resellWaitTicks = v);
        field("AH command", bx, y + st * 4, c.command, s -> c.command = s.trim());
        field("Sell command", bx, y + st * 5, c.sellCommand, s -> c.sellCommand = s);
        num("Alert % under", bx, y + st * 6, c.alertPercent, v -> c.alertPercent = v);
        num("Alert min avg", bx, y + st * 7, c.alertMinPrice, v -> c.alertMinPrice = v);
        num("Scam guard %", bx, y + st * 8, c.scamGuardPercent, v -> c.scamGuardPercent = v);
        num("Max unsold", bx, y + st * 9, c.maxUnsold, v -> c.maxUnsold = v);
    }

    // ------------------------------------------------------------ list logic
    boolean enabled(String key) { Config.Entry e = Config.I.entries.get(key); return e != null && e.enabled; }
    double avg(Catalog.Row r) { Market.Snap s = Market.latest(r.key()); return s == null ? 0 : s.avg; }
    int listings(Catalog.Row r) { Market.Snap s = Market.latest(r.key()); return s == null ? 0 : s.listings; }

    Comparator<Catalog.Row> comparator() {
        return switch (sort) {
            case 0 -> Comparator.comparingDouble((Catalog.Row r) -> -avg(r));
            case 1 -> Comparator.comparingDouble((Catalog.Row r) -> avg(r) <= 0 ? Double.MAX_VALUE : avg(r));
            case 2 -> Comparator.comparing((Catalog.Row r) -> r.name().toLowerCase());
            default -> Comparator.comparingInt((Catalog.Row r) -> -listings(r));
        };
    }

    String cut(String s, int n) { return s.length() > n ? s.substring(0, n - 1) + "..." : s; }

    Component rowLabel(Catalog.Row r) {
        Market.Snap s = Market.latest(r.key());
        ChatFormatting col = r.key().equals(selKey) ? ChatFormatting.AQUA : enabled(r.key()) ? ChatFormatting.GREEN : ChatFormatting.WHITE;
        boolean dup = nameCount.getOrDefault(Config.key(r.name()), 1) > 1 && !r.id().isEmpty();
        String nm = dup ? cut(r.name(), 13) + " [" + cut(r.id(), 8) + "]" : cut(r.name(), 21);
        return Component.literal(nm + " (" + (s == null ? "-" : Config.fmt(s.avg)) + ")").withStyle(col);
    }
    Component purchaseLabel(int idx) {
        Purchases.P p = Purchases.list.get(idx);
        ChatFormatting col = idx == selPurchase ? ChatFormatting.AQUA : p.status.startsWith("listed") ? ChatFormatting.GREEN : p.status.startsWith("held") ? ChatFormatting.YELLOW : ChatFormatting.WHITE;
        return Component.literal(cut(p.name, 16) + " x" + p.count + " (" + Config.fmt(p.total) + ")").withStyle(col);
    }

    void refreshList() {
        if (tab <= 1) {
            String q = search.toLowerCase();
            List<Catalog.Row> v = new ArrayList<>();
            for (Catalog.Row r : allRows) {
                if (tab == 0 && !enabled(r.key())) continue;
                if (customOnly && !r.custom()) continue;
                if (!q.isEmpty() && !r.name().toLowerCase().contains(q)) continue;
                v.add(r);
            }
            v.sort(comparator());
            view = v;
        }
        int n = tab <= 1 ? view.size() : Purchases.list.size();
        int pages = Math.max(1, (n + 7) / 8);
        listPage = Math.max(0, Math.min(listPage, pages - 1));
        for (int i = 0; i < 8; i++) {
            int idx = listPage * 8 + i;
            Button b = rows[i];
            if (b == null) continue;
            if (idx < n) { b.visible = true; b.setMessage(tab <= 1 ? rowLabel(view.get(idx)) : purchaseLabel(idx)); }
            else b.visible = false;
        }
        pageLabel = (listPage + 1) + "/" + pages;
    }

    void selectRow(int i) {
        int idx = listPage * 8 + i;
        if (tab <= 1) { if (idx < view.size()) { selKey = view.get(idx).key(); refreshList(); refreshDetail(); } }
        else { selPurchase = idx; refreshList(); }
    }

    void refreshWindowButtons() {
        for (int i = 0; i < 3; i++) winBtns[i].setMessage(Component.literal(WNAMES[i]).withStyle(i == window ? ChatFormatting.AQUA : ChatFormatting.WHITE));
    }

    void refreshDetail() {
        if (priceBox == null) return;
        Config.Entry e = selKey == null ? null : Config.I.entries.get(selKey);
        boolean on = e != null && e.enabled;
        if (selKey != null) {
            Market.Snap s = Market.latest(selKey);
            if (e != null && e.maxPrice > 0) priceBox.setValue(String.valueOf((long) e.maxPrice));
            else priceBox.setValue(s != null ? String.valueOf((long) (s.avg * 0.8)) : "");
            if (e != null) eachFlag = e.each;
        }
        eachBtn.setMessage(txt(eachFlag ? "each" : "total"));
        addBtn.setMessage(txt(on ? "Save" : "Add"));
        delBtn.visible = on;
    }

    void addSnipe() {
        double p = Config.parseUser(priceBox.getValue());
        if (selKey == null || p <= 0) return;
        Config.Entry e = Config.entryOrNew(selKey);
        e.enabled = true; e.maxPrice = p; e.each = eachFlag;
        Config.save(); refreshList(); refreshDetail();
    }
    void removeSnipe() {
        if (selKey == null) return;
        Config.Entry e = Config.I.entries.get(selKey);
        if (e != null) { e.enabled = false; Config.save(); }
        refreshList(); refreshDetail();
    }

    @Override public void removed() { Config.save(); }
    @Override public boolean isPauseScreen() { return false; }

    // ---------------------------------------------------------------- drawing
    @Override public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float dt) {
        g.fill(x0, y0, x0 + W, y0 + H, 0xF0101420);
        g.fill(x0, y0 + 26, x0 + W, y0 + 27, 0xFF2A3350);
        if (tab <= 2) g.fill(x0 + 186, y0 + 28, x0 + 187, y0 + H - 6, 0xFF2A3350);
        super.extractRenderState(g, mx, my, dt);
        if (tab <= 2) drawIcons(g);

        g.text(font, "Status: " + Sniper.status + (Config.I.buyEnabled ? "" : "  [buy mode off]"), x0, y0 - 11, 0xFFAAAAAA);
        if (tab <= 1) { drawItem(g); g.text(font, pageLabel, x0 + 54, y0 + 217, 0xFFAAAAAA); }
        else if (tab == 2) { drawPurchases(g); g.text(font, pageLabel, x0 + 54, y0 + 217, 0xFFAAAAAA); }
        else {
            for (Object[] l : labels) g.text(font, (String) l[0], (Integer) l[1], (Integer) l[2], 0xFFFFFFFF);
        }
    }

    /** Item texture next to every row in the list. */
    void drawIcons(GuiGraphicsExtractor g) {
        int n = tab <= 1 ? view.size() : Purchases.list.size();
        for (int i = 0; i < 8; i++) {
            int idx = listPage * 8 + i;
            if (idx >= n) break;
            String id = tab <= 1 ? view.get(idx).id() : Icons.idOfKey(Purchases.list.get(idx).key);
            ItemStack st = Icons.get(id);
            if (!st.isEmpty()) Compat.drawItem(g, st, x0 + 6, y0 + 51 + i * 20);
        }
    }

    Catalog.Row findRow(String key) { for (Catalog.Row r : allRows) if (r.key().equals(key)) return r; return null; }

    void drawItem(GuiGraphicsExtractor g) {
        int rx = x0 + 192, y = y0 + 52;
        if (selKey == null) {
            g.text(font, "Select an item on the left.", rx, y, 0xFFAAAAAA);
            g.text(font, tab == 1 ? "Economy lists every item - Add to snipe it." : "Your enabled snipes show here.", rx, y + 11, 0xFF888899);
            return;
        }
        Catalog.Row r = findRow(selKey);
        ItemStack ic = r != null ? Icons.get(r.id()) : ItemStack.EMPTY;
        int tx = ic.isEmpty() ? rx : rx + 20;
        if (!ic.isEmpty()) Compat.drawItem(g, ic, rx, y - 2);
        g.text(font, r != null ? r.name() : selKey, tx, y, 0xFF55FFFF);
        g.text(font, (r != null && !r.id().isEmpty() ? r.id() + " - " : "") + (r != null && r.custom() ? "custom item" : "vanilla item"), tx, y + 11, 0xFF888899);
        Market.Snap s = Market.latest(selKey);
        if (s == null) {
            g.text(font, "No market data yet.", rx, y + 28, 0xFFFFAA00);
            g.text(font, "Press Start (buy mode off = scan only).", rx, y + 39, 0xFFAAAAAA);
            return;
        }
        int ly = y + 26;
        g.text(font, "Lowest", rx, ly, 0xFFAAAAAA);  g.text(font, Config.fmt(s.low) + " by " + s.lowSeller, rx + 52, ly, 0xFF55FF55);
        g.text(font, "Average", rx, ly + 11, 0xFFAAAAAA); g.text(font, Config.fmt(s.avg) + " (" + s.used + "/" + s.total + " used)", rx + 52, ly + 11, 0xFFFFFF55);
        g.text(font, "Median", rx, ly + 22, 0xFFAAAAAA); g.text(font, Config.fmt(s.median), rx + 52, ly + 22, 0xFFFFFFFF);
        g.text(font, "Highest " + Config.fmt(s.high), rx + 124, ly + 22, 0xFFFFFFFF);
        g.text(font, "Listings " + s.listings + "   Sellers " + s.sellers + "   Items " + s.items, rx, ly + 33, 0xFFCCCCCC);
        g.text(font, "price over time (old -> now), yellow = avg", rx, y0 + 127, 0xFF888899);
        drawChart(g, rx, y0 + 138, 242, 66);
        g.text(font, "Snipe under", rx, y0 + 215, 0xFFFFFFFF);
    }

    void drawChart(GuiGraphicsExtractor g, int cx, int cy, int cw, int ch) {
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF0B0E18);
        List<double[]> h = Market.window(selKey, WINDOWS[window]);
        if (h.isEmpty()) { g.text(font, "no samples in this window yet", cx + 4, cy + 4, 0xFF888899); return; }
        int n = Math.min(h.size(), 40);
        List<double[]> w = h.subList(h.size() - n, h.size());
        double wavg = 0, max = 0;
        for (double[] x : w) { wavg += x[2]; max = Math.max(max, x[1]); }
        wavg /= n; max = Math.max(max, wavg) * 1.05;
        int bw = Math.max(2, Math.min(12, cw / n - 1));
        for (int i = 0; i < n; i++) {
            int bx = cx + cw - (n - i) * (bw + 1);
            int hh = Math.max(1, (int) (w.get(i)[1] / max * (ch - 2)));
            g.fill(bx, cy + ch - hh, bx + bw, cy + ch, w.get(i)[1] <= wavg ? 0xFF4CD964 : 0xFFE0903A);
        }
        int ly = cy + ch - (int) (wavg / max * (ch - 2));
        g.fill(cx, ly, cx + cw, ly + 1, 0xFFFFFF55);
    }

    void drawPurchases(GuiGraphicsExtractor g) {
        double spent = 0, exp = 0; int listed = 0;
        for (Purchases.P p : Purchases.list) {
            spent += p.total;
            if (p.listedFor > 0) { listed++; exp += p.listedFor * (1 - Config.I.ahTaxPercent / 100) - p.total; }
        }
        g.text(font, Purchases.list.size() + " bought, spent " + Config.fmt(spent), x0 + 6, y0 + 33, 0xFFFFFFFF);
        int rx = x0 + 192, y = y0 + 34;
        g.text(font, "Total spent: " + Config.fmt(spent), rx, y, 0xFFFFFFFF);
        g.text(font, "Relisted: " + listed + " / " + Purchases.list.size(), rx, y + 11, 0xFFFFFFFF);
        g.text(font, "Profit if all relists sell: " + (exp < 0 ? "-" : "") + Config.fmt(Math.abs(exp)), rx, y + 22, exp >= 0 ? 0xFF55FF55 : 0xFFFF5555);
        g.text(font, "Or hold any item and type /" + Config.I.command + " hand (auto price)", rx, y0 + 196, 0xFF888899);
        g.fill(rx, y0 + 70, x0 + W - 6, y0 + 71, 0xFF2A3350);
        if (selPurchase < 0 || selPurchase >= Purchases.list.size()) { g.text(font, "Select a purchase on the left.", rx, y0 + 80, 0xFFAAAAAA); return; }
        Purchases.P p = Purchases.list.get(selPurchase);
        int py = y0 + 78;
        ItemStack pic = Icons.get(Icons.idOfKey(p.key));
        int ptx = pic.isEmpty() ? rx : rx + 20;
        if (!pic.isEmpty()) Compat.drawItem(g, pic, rx, py - 2);
        g.text(font, p.name, ptx, py, 0xFF55FFFF);
        g.text(font, LocalDateTime.ofInstant(Instant.ofEpochMilli(p.time), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM HH:mm")), ptx, py + 11, 0xFF888899);
        g.text(font, "Bought x" + p.count + " for " + Config.fmt(p.total) + " (" + Config.fmt(p.total / Math.max(1, p.count)) + " each)", rx, py + 26, 0xFFFFFFFF);
        g.text(font, "Status: " + p.status, rx, py + 37, 0xFFFFFF55);
        if (p.listedFor > 0) {
            double net = p.listedFor * (1 - Config.I.ahTaxPercent / 100) - p.total;
            g.text(font, "Listed for " + Config.fmt(p.listedFor), rx, py + 48, 0xFF55FF55);
            g.text(font, "Profit if sold: " + (net < 0 ? "-" : "") + Config.fmt(Math.abs(net)), rx, py + 59, net >= 0 ? 0xFF55FF55 : 0xFFFF5555);
        }
    }
}
