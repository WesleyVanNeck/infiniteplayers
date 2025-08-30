package com.wesleyvanneck.infiniteplayers;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

public class InfinitePlayers extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // Display
    private enum DisplayMode { DYNAMIC, FIXED }
    private DisplayMode mode; private int extraSlots; private int fixedMax;

    // Global soft cap / rate
    private boolean softCapEnabled; private int softCapLimit; private String softCapMessage;
    private boolean rateEnabled;    private int rateJoins;     private int rateSeconds; private String rateMessage;
    private final Deque<Long> recentJoinTimes = new ArrayDeque<>();

    // TPS gate
    private boolean tpsGateEnabled; private double tpsMin; private boolean tpsUse5m; private String tpsMessage;

    // Priority queue + tiers
    private boolean queueEnabled; private int queueMax; private String queueMessage;
    private List<String> tierPermissions = new ArrayList<>();          // high -> low
    private List<Deque<UUID>> tierQueues = new ArrayList<>();          // FIFO per tier (+ baseline)
    private final Map<UUID, Integer> inTier = new HashMap<>();
    private final Map<UUID, Long> queuedAt = new HashMap<>();

    // NEW: Per-tier soft caps (optional). If absent -> inherit global.
    // Index matches tier; last index = baseline/default
    private List<Integer> tierSoftCaps = new ArrayList<>();            // null => no override

    // NEW: Per-tier rate limits (optional)
    private static class TierRate { Integer joins; Integer seconds; String message; }
    private List<TierRate> tierRates = new ArrayList<>();
    private List<Deque<Long>> tierRecentJoins = new ArrayList<>();

    // Perms
    private static final String PERM_ADMIN  = "infiniteplayers.reload";
    private static final String PERM_BYPASS = "infiniteplayers.bypass";

    @Override public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("infiniteplayers") != null) {
            getCommand("infiniteplayers").setExecutor(this);
            getCommand("infiniteplayers").setTabCompleter(this);
        }
    }

    private void loadSettings() {
        // Display
        String modeStr = getConfig().getString("display.mode", "dynamic").toUpperCase();
        try { mode = DisplayMode.valueOf(modeStr); } catch (Exception e) { mode = DisplayMode.DYNAMIC; }
        extraSlots = Math.max(0, getConfig().getInt("display.extra-slots", 100));
        fixedMax   = Math.max(1, getConfig().getInt("display.fixed-max", 9999));

        // Global caps
        softCapEnabled = getConfig().getBoolean("soft-cap.enabled", false);
        softCapLimit   = Math.max(1, getConfig().getInt("soft-cap.limit", 150));
        softCapMessage = getConfig().getString("soft-cap.message", "&cBusy. Try again. (&7Cap {limit}&c)");

        rateEnabled = getConfig().getBoolean("rate-limit.enabled", false);
        rateJoins   = Math.max(1, getConfig().getInt("rate-limit.joins", 10));
        rateSeconds = Math.max(1, getConfig().getInt("rate-limit.per-seconds", 30));
        rateMessage = getConfig().getString("rate-limit.message", "&cJoin burst. Try soon. (&7{joins}/{seconds}&c)");

        // TPS
        tpsGateEnabled = getConfig().getBoolean("tps-gate.enabled", false);
        tpsMin         = Math.max(1.0, getConfig().getDouble("tps-gate.min-tps", 18.5));
        tpsUse5m       = getConfig().getBoolean("tps-gate.use-5m-window", false);
        tpsMessage     = getConfig().getString("tps-gate.message", "&cTPS low ({tps}). Min {minTps}");

        // Queue + tiers
        queueEnabled = getConfig().getBoolean("queue.enabled", true);
        queueMax     = Math.max(0, getConfig().getInt("queue.max-size", 500));
        queueMessage = getConfig().getString("queue.message", "&eQueue: &6#{pos}&e/&6{size}&e (Tier {tier})");

        tierPermissions = new ArrayList<>(getConfig().getStringList("queue.tiers")); // may be empty
        rebuildTierStructs();

        // Per-tier overrides
        Map<String,Object> tierSettings = getConfig().getConfigurationSection("queue.tier-settings") == null
                ? Collections.emptyMap()
                : getConfig().getConfigurationSection("queue.tier-settings").getValues(false);

        // Reset
        for (int i = 0; i < tierCount(); i++) {
            tierSoftCaps.set(i, null);
            tierRates.set(i, null);
            tierRecentJoins.get(i).clear();
        }

        for (Map.Entry<String,Object> e : tierSettings.entrySet()) {
            String key = e.getKey(); // permission node or "default"
            int idx = tierIndexFromKey(key);
            if (idx < 0 || idx >= tierCount()) continue;

            // soft-cap
            if (getConfig().isInt("queue.tier-settings." + key + ".soft-cap")) {
                int v = getConfig().getInt("queue.tier-settings." + key + ".soft-cap");
                tierSoftCaps.set(idx, Math.max(1, v));
            } else if (getConfig().isString("queue.tier-settings." + key + ".soft-cap")) {
                String v = getConfig().getString("queue.tier-settings." + key + ".soft-cap");
                if ("off".equalsIgnoreCase(v)) tierSoftCaps.set(idx, null);
            }

            // rate
            String base = "queue.tier-settings." + key + ".rate";
            boolean hasRate = getConfig().isConfigurationSection(base);
            if (hasRate) {
                TierRate tr = new TierRate();
                if (getConfig().isString(base + ".joins") && "off".equalsIgnoreCase(getConfig().getString(base + ".joins"))) {
                    tr.joins = null; tr.seconds = null;
                } else {
                    Integer j = getCfgInt(base + ".joins"); Integer s = getCfgInt(base + ".per-seconds");
                    if (j != null && j >= 1 && s != null && s >= 1) { tr.joins = j; tr.seconds = s; }
                }
                tr.message = getConfig().getString(base + ".message", null);
                tierRates.set(idx, tr);
            }
        }
    }

    private Integer getCfgInt(String path){ return getConfig().isInt(path) ? getConfig().getInt(path) : null; }

    private int tierCount() { return tierPermissions.size() + 1; } // + baseline
    private void rebuildTierStructs() {
        int n = tierCount();

        // queues list size
        while (tierQueues.size() < n) tierQueues.add(new ConcurrentLinkedDeque<>());
        while (tierQueues.size() > n) tierQueues.remove(tierQueues.size()-1);

        // per-tier recent joins
        while (tierRecentJoins.size() < n) tierRecentJoins.add(new ArrayDeque<>());
        while (tierRecentJoins.size() > n) tierRecentJoins.remove(tierRecentJoins.size()-1);

        // per-tier overrides arrays
        tierSoftCaps = new ArrayList<>(Collections.nCopies(n, null));
        tierRates    = new ArrayList<>(Collections.nCopies(n, null));
    }

    // ===== Events

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL) event.allow();

        Player p = event.getPlayer();
        if (p.hasPermission(PERM_BYPASS)) return;

        int online = Bukkit.getOnlinePlayers().size();
        boolean mustQueue = false;
        String reasonMsg = null;

        // TPS
        if (tpsGateEnabled) {
            double tps = readTps(tpsUse5m ? 1 : 0);
            if (tps > 0 && tps < tpsMin) {
                mustQueue = true;
                reasonMsg = format(tpsMessage, online).replace("{tps}", String.format("%.2f", tps))
                                                      .replace("{minTps}", String.format("%.2f", tpsMin));
            }
        }

        // Global soft cap
        if (!mustQueue && softCapEnabled && online >= softCapLimit) {
            mustQueue = true;
            reasonMsg = format(softCapMessage, online);
        }

        // Global rate
        if (!mustQueue && rateEnabled) {
            pruneOld(recentJoinTimes, rateSeconds);
            if (recentJoinTimes.size() >= rateJoins) {
                mustQueue = true;
                reasonMsg = format(rateMessage, online);
            }
        }

        // Tier checks
        int tier = getTierFromPermissions(p);
        Integer tSoft = tierSoftCaps.get(tier);
        TierRate tRate = tierRates.get(tier);

        if (!mustQueue && tSoft != null && online >= tSoft) {
            mustQueue = true;
            reasonMsg = format(softCapMessage, online)
                    .replace("{limit}", String.valueOf(tSoft))
                    .replace("{tier}", tierName(tier));
        }

        if (!mustQueue && tRate != null && tRate.joins != null && tRate.seconds != null) {
            Deque<Long> bucket = tierRecentJoins.get(tier);
            pruneOld(bucket, tRate.seconds);
            if (bucket.size() >= tRate.joins) {
                mustQueue = true;
                String msg = tRate.message != null ? tRate.message : rateMessage;
                reasonMsg = format(msg, online)
                        .replace("{joins}", String.valueOf(tRate.joins))
                        .replace("{seconds}", String.valueOf(tRate.seconds))
                        .replace("{tier}", tierName(tier));
            }
        }

        if (queueEnabled && mustQueue) {
            enqueue(p.getUniqueId(), tier);
            int pos = positionInQueue(p.getUniqueId(), tier);
            int size = totalQueueSize();
            String msg = format(queueMessage, online)
                    .replace("{pos}", String.valueOf(Math.max(1, pos)))
                    .replace("{size}", String.valueOf(size))
                    .replace("{tier}", tierName(tier));
            if (reasonMsg != null && !reasonMsg.isEmpty()) {
                msg = ChatColor.translateAlternateColorCodes('&', msg) + ChatColor.GRAY + "\n" +
                      ChatColor.translateAlternateColorCodes('&', reasonMsg);
            }
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, msg);
            return;
        }
        // else allow
    }

    @EventHandler public void onPlayerJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Integer tier = inTier.remove(id);
        if (tier != null && tier >= 0 && tier < tierQueues.size()) tierQueues.get(tier).remove(id);
        queuedAt.remove(id);

        // record global + tier rates
        if (rateEnabled) { pruneOld(recentJoinTimes, rateSeconds); recentJoinTimes.addLast(System.currentTimeMillis()); }
        int t = getTierFromPermissions(event.getPlayer());
        TierRate tr = tierRates.get(t);
        if (tr != null && tr.joins != null && tr.seconds != null) {
            Deque<Long> bucket = tierRecentJoins.get(t);
            pruneOld(bucket, tr.seconds);
            bucket.addLast(System.currentTimeMillis());
        }
    }

    private void pruneOld(Deque<Long> q, int seconds) {
        long cutoff = System.currentTimeMillis() - (seconds * 1000L);
        while (!q.isEmpty() && q.peekFirst() < cutoff) q.removeFirst();
    }

    // ===== Queue helpers

    private void enqueue(UUID id, int tier) {
        if (queueMax > 0 && totalQueueSize() >= queueMax) return;
        if (inTier.containsKey(id)) return;
        ensureTierCapacity();
        tierQueues.get(tier).offerLast(id);
        inTier.put(id, tier);
        queuedAt.put(id, System.currentTimeMillis());
    }

    private void ensureTierCapacity() {
        int need = tierCount();
        while (tierQueues.size() < need) tierQueues.add(new ConcurrentLinkedDeque<>());
        while (tierRecentJoins.size() < need) tierRecentJoins.add(new ArrayDeque<>());
        while (tierSoftCaps.size() < need) tierSoftCaps.add(null);
        while (tierRates.size() < need) tierRates.add(null); //tps
    }

    private int positionInQueue(UUID id, int tier) {
        int pos = 1;
        for (int t = 0; t < tier; t++) pos += tierQueues.get(t).size();
        int i = 0; for (UUID u : tierQueues.get(tier)) { i++; if (u.equals(id)) { pos += (i-1); break; } }
        return pos;
    }

    private int totalQueueSize() { int s=0; for (Deque<UUID> d:tierQueues) s+=d.size(); return s; }

    private int getTierFromPermissions(OfflinePlayer p) {
        for (int i = 0; i < tierPermissions.size(); i++) {
            String perm = tierPermissions.get(i);
            if (perm != null && !perm.isEmpty() && p.isOnline() && ((Player)p).hasPermission(perm)) return i;
        }
        return tierPermissions.size(); // baseline
    }

    private String tierName(int i) {
        if (i < tierPermissions.size()) return tierPermissions.get(i);
        return "default";
    }
    private int tierIndexFromKey(String key) {
        if (key.equalsIgnoreCase("default") || key.equalsIgnoreCase("baseline")) return tierPermissions.size();
        for (int i = 0; i < tierPermissions.size(); i++) if (tierPermissions.get(i).equalsIgnoreCase(key)) return i;
        try { int idx = Integer.parseInt(key); if (idx>=0 && idx<tierCount()) return idx; } catch (Exception ignored) {}
        return -1;
    }

    // Ping (fake max)
    @EventHandler public void onServerListPing(ServerListPingEvent event) {
        int online = Bukkit.getOnlinePlayers().size();
        event.setMaxPlayers(mode==DisplayMode.FIXED ? Math.max(fixedMax, online) : online + extraSlots);
    }

    // Utils
    private String format(String raw, int online) {
        String s = raw.replace("{online}", String.valueOf(online))
                      .replace("{limit}", String.valueOf(softCapLimit))
                      .replace("{joins}", String.valueOf(rateJoins))
                      .replace("{seconds}", String.valueOf(rateSeconds));
        return ChatColor.translateAlternateColorCodes('&', s);
    }

// Spigot-safe TPS reader (works on Paper too)
   private double readTps(int index) {
    try {
        Object server = Bukkit.getServer();

        // Try Paper: public double[] Server#getTPS()
        try {
            java.lang.reflect.Method m = server.getClass().getMethod("getTPS");
            Object res = m.invoke(server);
            if (res instanceof double[]) {
                double[] tps = (double[]) res;
                if (index >= 0 && index < tps.length) return clampTps(tps[index]);
            }
        } catch (NoSuchMethodException ignored) {
            // Not Paper, fall through to CraftBukkit internals
        }

        // Fallback: CraftBukkit/Mojang server 'recentTps' field
        try {
            java.lang.reflect.Field consoleField = server.getClass().getDeclaredField("console");
            consoleField.setAccessible(true);
            Object mcServer = consoleField.get(server);

            java.lang.reflect.Field recentTps = mcServer.getClass().getDeclaredField("recentTps");
            recentTps.setAccessible(true);
            double[] arr = (double[]) recentTps.get(mcServer);
            if (arr != null && index >= 0 && index < arr.length) return clampTps(arr[index]);
        } catch (NoSuchFieldException ignored) {
            // Not CraftBukkit with that field
        }
    } catch (Throwable ignored) {
    }
    return -1.0; // unknown / unavailable
}

   private double clampTps(double v) { return Math.max(0.0, Math.min(20.0, v)); }

    // ===== Commands

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission(PERM_ADMIN)) { s.sendMessage(ChatColor.RED+"No permission."); return true; }

        if (a.length==0 || a[0].equalsIgnoreCase("status")) { sendStatus(s); return true; }
        if (a[0].equalsIgnoreCase("reload")) { reloadConfig(); loadSettings(); s.sendMessage(ChatColor.GREEN+"Reloaded."); sendStatus(s); return true; }

        if (a[0].equalsIgnoreCase("tiers")) {
            s.sendMessage(ChatColor.AQUA+"Queue Tiers (high→low) + baseline:");
            for (int i=0;i<tierPermissions.size();i++) s.sendMessage(ChatColor.GRAY+"  "+i+": "+tierPermissions.get(i)+"  size="+tierQueues.get(i).size());
            s.sendMessage(ChatColor.GRAY+"  "+tierPermissions.size()+": default  size="+tierQueues.get(tierPermissions.size()).size());
            s.sendMessage(ChatColor.GRAY+"Total queued: "+totalQueueSize());
            return true;
        }

        if (a[0].equalsIgnoreCase("queue")) {
            if (a.length==1 || a[1].equalsIgnoreCase("list")) {
                s.sendMessage(ChatColor.AQUA+"Queue total "+totalQueueSize());
                for (int t=0;t<tierQueues.size();t++){ int size=tierQueues.get(t).size(); if(size>0) s.sendMessage(ChatColor.GRAY+"  Tier "+t+" ("+tierName(t)+"): "+size); }
                return true;
            }
            if (a[1].equalsIgnoreCase("clear")) { for (Deque<UUID> d:tierQueues) d.clear(); inTier.clear(); queuedAt.clear(); s.sendMessage(ChatColor.GREEN+"Queue cleared."); return true; }
            if (a[1].equalsIgnoreCase("remove") && a.length>=3) {
                String name=a[2]; UUID target=null;
                for (Deque<UUID> d:tierQueues) for (UUID u:new ArrayList<>(d)) { OfflinePlayer op=Bukkit.getOfflinePlayer(u); if(op!=null && name.equalsIgnoreCase(op.getName())){ target=u; break; } }
                if (target!=null){ Integer t=inTier.remove(target); if(t!=null) tierQueues.get(t).remove(target); queuedAt.remove(target); s.sendMessage(ChatColor.GREEN+"Removed "+name+"."); }
                else s.sendMessage(ChatColor.YELLOW+"Not found: "+name);
                return true;
            }
            s.sendMessage(ChatColor.YELLOW+"Usage: /"+label+" queue [list|clear|remove <player>]"); return true;
        }

        if (a[0].equalsIgnoreCase("set")) {
            if (a.length<3){ s.sendMessage(ChatColor.YELLOW+"Usage: /"+label+" set <key> <value...>"); return true; }
            String key=a[1].toLowerCase(); String val=String.join(" ", Arrays.copyOfRange(a,2,a.length));

            // display/global/tps/queue keys unchanged from prior versions...
            switch (key) {
                case "mode": case "extra": case "fixed":
                case "softcap-enabled": case "softcap": case "softcap-message":
                case "rate-enabled": case "rate-joins": case "rate-seconds": case "rate-message":
                case "tps-enabled": case "tps-min": case "tps-window": case "tps-message":
                case "queue-enabled": case "queue-max": case "queue-message":
                case "tiers":
                    return legacySet(s, label, key, val);
            }

            // NEW: per-tier setters
            if (key.equals("tier-softcap")) {
                // /infiniteplayers set tier-softcap <tierKey> <number|off>
                String[] sp = val.split("\\s+");
                if (sp.length<2){ s.sendMessage(ChatColor.YELLOW+"Usage: /"+label+" set tier-softcap <tier|default|index> <number|off>"); return true; }
                int idx = tierIndexFromKey(sp[0]); if (idx<0){ s.sendMessage(ChatColor.RED+"Unknown tier '"+sp[0]+"'."); return true; }
                String v = sp[1];
                if ("off".equalsIgnoreCase(v)) { getConfig().set("queue.tier-settings."+tierKeyForIndex(idx)+".soft-cap", "off"); }
                else {
                    Integer n = parseInt(v); if (n==null||n<1){ s.sendMessage(ChatColor.RED+"soft-cap must be >=1 or 'off'."); return true; }
                    getConfig().set("queue.tier-settings."+tierKeyForIndex(idx)+".soft-cap", n);
                }
                saveConfig(); loadSettings(); s.sendMessage(ChatColor.GREEN+"Tier soft-cap updated."); sendStatus(s); return true;
            }

            if (key.equals("tier-rate")) {
                // /infiniteplayers set tier-rate <tierKey> <joins> <seconds|off> [message...]
                String[] sp = val.split("\\s+");
                if (sp.length<3){ s.sendMessage(ChatColor.YELLOW+"Usage: /"+label+" set tier-rate <tier|default|index> <joins> <seconds|off> [message]"); return true; }
                int idx = tierIndexFromKey(sp[0]); if (idx<0){ s.sendMessage(ChatColor.RED+"Unknown tier '"+sp[0]+"'."); return true; }
                String joinsStr = sp[1], secStr = sp[2];
                String msg = (sp.length>3) ? String.join(" ", Arrays.copyOfRange(sp,3,sp.length)) : null;

                String base = "queue.tier-settings."+tierKeyForIndex(idx)+".rate";
                if ("off".equalsIgnoreCase(joinsStr) || "off".equalsIgnoreCase(secStr)) {
                    getConfig().set(base+".joins", "off"); // mark disabled
                    getConfig().set(base+".per-seconds", "off");
                } else {
                    Integer j = parseInt(joinsStr), sss = parseInt(secStr);
                    if (j==null||j<1||sss==null||sss<1){ s.sendMessage(ChatColor.RED+"joins>=1 and seconds>=1, or 'off'."); return true; }
                    getConfig().set(base+".joins", j);
                    getConfig().set(base+".per-seconds", sss);
                }
                if (msg != null) getConfig().set(base+".message", msg);
                saveConfig(); loadSettings(); s.sendMessage(ChatColor.GREEN+"Tier rate updated."); sendStatus(s); return true;
            }

            s.sendMessage(ChatColor.YELLOW+"Unknown key '"+key+"'."); return true;
        }

        s.sendMessage(ChatColor.YELLOW+"Usage: /"+label+" reload|status|tiers|queue [list|clear|remove <player>] | set <...>");
        return true;
    }
	
	private void err(org.bukkit.command.CommandSender s, String expected) {
    s.sendMessage(org.bukkit.ChatColor.RED + "Invalid value. Expected " + expected);
    }
	
    private boolean legacySet(CommandSender s, String label, String key, String val) {
        // Reuse existing setters from previous versions: keep this compact
        switch (key) {
            case "mode":
                if (!val.equalsIgnoreCase("dynamic") && !val.equalsIgnoreCase("fixed")) { err(s,"dynamic|fixed"); return true; }
                getConfig().set("display.mode", val.toLowerCase()); break;
            case "extra": { Integer n=parseInt(val); if(n==null||n<0){ err(s,">=0"); return true; } getConfig().set("display.extra-slots", n); break; }
            case "fixed": { Integer n=parseInt(val); if(n==null||n<1){ err(s,">=1"); return true; } getConfig().set("display.fixed-max", n); break; }
            case "softcap-enabled": { Boolean b=parseBool(val); if(b==null){ err(s,"true|false"); return true; } getConfig().set("soft-cap.enabled", b); break; }
            case "softcap": { Integer n=parseInt(val); if(n==null||n<1){ err(s,">=1"); return true; } getConfig().set("soft-cap.limit", n); break; }
            case "softcap-message": getConfig().set("soft-cap.message", val); break;
            case "rate-enabled": { Boolean b=parseBool(val); if(b==null){ err(s,"true|false"); return true; } getConfig().set("rate-limit.enabled", b); break; }
            case "rate-joins": { Integer n=parseInt(val); if(n==null||n<1){ err(s,">=1"); return true; } getConfig().set("rate-limit.joins", n); break; }
            case "rate-seconds": { Integer n=parseInt(val); if(n==null||n<1){ err(s,">=1"); return true; } getConfig().set("rate-limit.per-seconds", n); break; }
            case "rate-message": getConfig().set("rate-limit.message", val); break;
            case "tps-enabled": { Boolean b=parseBool(val); if(b==null){ err(s,"true|false"); return true; } getConfig().set("tps-gate.enabled", b); break; }
            case "tps-min": { Double d=parseDouble(val); if(d==null||d<1.0||d>20.0){ err(s,"1.0–20.0"); return true; } getConfig().set("tps-gate.min-tps", d); break; }
            case "tps-window":
                if (!val.equalsIgnoreCase("1m") && !val.equalsIgnoreCase("5m")) { err(s,"1m|5m"); return true; }
                getConfig().set("tps-gate.use-5m-window", val.equalsIgnoreCase("5m")); break;
            case "tps-message": getConfig().set("tps-gate.message", val); break;
            case "queue-enabled": { Boolean b=parseBool(val); if(b==null){ err(s,"true|false"); return true; } getConfig().set("queue.enabled", b); break; }
            case "queue-max": { Integer n=parseInt(val); if(n==null||n<0){ err(s,">=0"); return true; } getConfig().set("queue.max-size", n); break; }
            case "queue-message": getConfig().set("queue.message", val); break;
            case "tiers": {
                List<String> list = Arrays.stream(val.split(",")).map(String::trim).filter(s2->!s2.isEmpty()).collect(Collectors.toList());
                getConfig().set("queue.tiers", list); break;
            }
        }
        saveConfig(); loadSettings(); s.sendMessage(ChatColor.GREEN+"Updated "+key+"."); sendStatus(s); return true;
    }

    private void sendStatus(CommandSender s) {
        double tps1 = readTps(0), tps5 = readTps(1);
        s.sendMessage(ChatColor.AQUA+"InfinitePlayers Status");
        s.sendMessage(ChatColor.GRAY+"  Display: "+ChatColor.WHITE+mode+ChatColor.GRAY+" extra="+ChatColor.WHITE+extraSlots+ChatColor.GRAY+" fixed="+ChatColor.WHITE+fixedMax);
        s.sendMessage(ChatColor.GRAY+"  SoftCap(G): "+onOff(softCapEnabled)+ChatColor.GRAY+" limit="+ChatColor.WHITE+softCapLimit);
        s.sendMessage(ChatColor.GRAY+"  Rate(G): "+onOff(rateEnabled)+ChatColor.GRAY+" "+rateJoins+"/"+rateSeconds);
        s.sendMessage(ChatColor.GRAY+"  TPS: "+onOff(tpsGateEnabled)+ChatColor.GRAY+" min="+ChatColor.WHITE+String.format("%.2f", tpsMin)+ChatColor.GRAY+" win="+ChatColor.WHITE+(tpsUse5m?"5m":"1m")+ChatColor.GRAY+" cur="+fmt(tps1)+"/"+fmt(tps5));
        s.sendMessage(ChatColor.GRAY+"  Queue: "+onOff(queueEnabled)+ChatColor.GRAY+" size="+ChatColor.WHITE+totalQueueSize());
        s.sendMessage(ChatColor.GRAY+"  Tiers: "+ChatColor.WHITE+(tierPermissions.isEmpty()?"(none)":String.join(", ", tierPermissions))+" + default");
        for (int i=0;i<tierCount();i++) {
            Integer sc = tierSoftCaps.get(i);
            TierRate tr = tierRates.get(i);
            String trTxt = (tr==null||tr.joins==null||tr.seconds==null) ? "inherit/off" : (tr.joins+"/"+tr.seconds);
            s.sendMessage(ChatColor.DARK_GRAY+"    - "+i+" ("+tierName(i)+")  softCap="+(sc==null?"inherit/off":sc)+"  rate="+trTxt);
        }
    }

    private String onOff(boolean b){ return b? ChatColor.GREEN+"on": ChatColor.RED+"off"; }
    private String fmt(double d){ return d<0? "n/a" : String.format("%.2f", d); }

    private Integer parseInt(String s){ try { return Integer.parseInt(s); } catch (Exception e){ return null; } }
    private Double  parseDouble(String s){ try { return Double.parseDouble(s);} catch (Exception e){ return null; } }
    private Boolean parseBool(String s){
        if (s.equalsIgnoreCase("true")||s.equalsIgnoreCase("yes")||s.equalsIgnoreCase("on")) return true;
        if (s.equalsIgnoreCase("false")||s.equalsIgnoreCase("no")||s.equalsIgnoreCase("off")) return false;
        return null;
    }

    @Override public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (!s.hasPermission(PERM_ADMIN)) return out;
        if (a.length==1) { add(out,a[0],"reload","status","tiers","queue","set"); }
        else if (a.length==2 && a[0].equalsIgnoreCase("queue")) { add(out,a[1],"list","clear","remove"); }
        else if (a.length==2 && a[0].equalsIgnoreCase("set")) {
            add(out,a[1],"mode","extra","fixed","softcap-enabled","softcap","softcap-message",
                    "rate-enabled","rate-joins","rate-seconds","rate-message",
                    "tps-enabled","tps-min","tps-window","tps-message",
                    "queue-enabled","queue-max","queue-message","tiers",
                    "tier-softcap","tier-rate");
        } else if (a.length==3 && a[0].equalsIgnoreCase("set") && (a[1].equalsIgnoreCase("tier-softcap")||a[1].equalsIgnoreCase("tier-rate"))) {
            // suggest tier keys
            List<String> keys = new ArrayList<>(tierPermissions);
            keys.add("default");
            for (String k: keys) add(out,a[2],k);
        }
        return out;
    }
    private void add(List<String> out, String cur, String... opts){ String lc=cur==null?"":cur.toLowerCase(); for(String o:opts){ if(o.toLowerCase().startsWith(lc)) out.add(o);} }

    private String tierKeyForIndex(int idx){ return (idx==tierPermissions.size()) ? "default" : tierPermissions.get(idx); }
}
