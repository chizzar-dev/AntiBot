package com.metox.antibot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Bot saldirilarina karsi cok katmanli koruma.
 *
 *   1. Baglanti hizi  - kisa surede cok fazla giris olursa kilit moduna gecer
 *   2. IP limitleri   - ayni IP'den es zamanli / dakikalik giris siniri
 *   3. Isim filtresi  - Player1234 gibi kaliplari reddeder
 *   4. GUI dogrulama  - yeni oyuncu dogru esyayi tiklayana kadar donar
 *
 * Giris kontrolleri AsyncPlayerPreLoginEvent icinde, yani sunucu ana
 * dongusune dokunmadan yapilir. Bu yuzden tum sayaclar es zamanli
 * (concurrent) koleksiyonlarda tutulur.
 */
public class AntiBot extends JavaPlugin implements Listener, TabExecutor {

    private final Random random = new Random();

    /** Son giris zamanlari (ms) - kayan pencere. */
    private final List<Long> recentJoins = Collections.synchronizedList(new ArrayList<Long>());
    /** IP -> son giris zamanlari. */
    private final Map<String, List<Long>> ipJoins = new ConcurrentHashMap<String, List<Long>>();
    /** Dogrulamayi gecmis oyuncular (kalici). */
    private final Set<String> verified = Collections.synchronizedSet(new HashSet<String>());
    /** Dogrulama bekleyenler. */
    private final Map<UUID, Captcha> pending = new HashMap<UUID, Captcha>();

    /** Kilit modunun bitis zamani (0 = kapali). */
    private final AtomicLong lockdownUntil = new AtomicLong(0);

    private File dataFile;
    private YamlConfiguration data;
    private List<Pattern> namePatterns = new ArrayList<Pattern>();

    private static class Captcha {
        int attempts;
        long shownAt = System.currentTimeMillis();
        Location anchor;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "verified.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        verified.addAll(data.getStringList("verified"));

        compilePatterns();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("antibot") != null) {
            getCommand("antibot").setExecutor(this);
            getCommand("antibot").setTabCompleter(this);
        }

        startWatchdog();
        getLogger().info("AntiBot aktif - algilanan surum 1." + Compat.MINOR
                + (Compat.PATCH > 0 ? "." + Compat.PATCH : "")
                + ", kayitli dogrulama: " + verified.size());
    }

    @Override
    public void onDisable() {
        saveVerified();
    }

    private void compilePatterns() {
        List<Pattern> out = new ArrayList<Pattern>();
        for (String s : getConfig().getStringList("name-filter.blocked-patterns")) {
            if (s == null || s.isEmpty()) continue;
            try {
                out.add(Pattern.compile(s, Pattern.CASE_INSENSITIVE));
            } catch (PatternSyntaxException ex) {
                getLogger().warning("Gecersiz isim kalibi atlandi: " + s + " (" + ex.getDescription() + ")");
            }
        }
        namePatterns = out;
    }

    // ------------------------------------------------------------------
    // 1-3: Giris kontrolleri (async)
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        String ip = e.getAddress() == null ? "" : e.getAddress().getHostAddress();
        String name = e.getName();
        long now = System.currentTimeMillis();

        if (isWhitelisted(ip)) return;

        // Daha once dogrulanmis oyuncular hiz limitinden muaf tutulabilir
        boolean known = verified.contains(name.toLowerCase(Locale.ENGLISH));

        // 3) Isim filtresi
        if (getConfig().getBoolean("name-filter.enabled", false) && !known) {
            for (Pattern p : namePatterns) {
                if (p.matcher(name).matches()) {
                    deny(e, "messages.kick-name", "Bot ismi kalibi: " + name);
                    return;
                }
            }
        }

        // 2) IP limitleri
        if (!ip.isEmpty()) {
            int perMin = getConfig().getInt("per-ip.max-joins-per-minute", 6);
            if (perMin > 0) {
                List<Long> list = ipJoins.get(ip);
                if (list == null) {
                    list = Collections.synchronizedList(new ArrayList<Long>());
                    ipJoins.put(ip, list);
                }
                prune(list, now, 60000L);
                if (list.size() >= perMin) {
                    deny(e, "messages.kick-ip-rate", "IP dakika limiti asildi: " + ip);
                    return;
                }
                list.add(now);
            }

            int maxOnline = getConfig().getInt("per-ip.max-online", 0);
            if (maxOnline > 0 && countOnlineFromIp(ip) >= maxOnline) {
                deny(e, "messages.kick-ip-limit", "Ayni IP'den es zamanli limit: " + ip);
                return;
            }
        }

        // 1) Genel giris hizi
        int maxJoins = getConfig().getInt("join-rate.max-joins", 6);
        int window = getConfig().getInt("join-rate.interval-seconds", 3);
        if (getConfig().getBoolean("join-rate.enabled", true) && maxJoins > 0) {
            prune(recentJoins, now, window * 1000L);
            recentJoins.add(now);
            if (recentJoins.size() > maxJoins && !isLocked()) {
                startLockdown();
            }
        }

        if (isLocked() && !known) {
            deny(e, "messages.kick-lockdown", "Kilit modu: " + name + " reddedildi");
        }
    }

    private void deny(AsyncPlayerPreLoginEvent e, String messageKey, String logLine) {
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Compat.color(getConfig().getString(messageKey,
                        "&cSunucu su an yeni baglantilari kabul etmiyor.")));
        if (getConfig().getBoolean("log", true)) getLogger().info("[AntiBot] " + logLine);
    }

    /** Verilen pencereden eski kayitlari temizler. */
    private void prune(List<Long> list, long now, long windowMs) {
        synchronized (list) {
            java.util.Iterator<Long> it = list.iterator();
            while (it.hasNext()) {
                if (now - it.next() > windowMs) it.remove();
            }
        }
    }

    private int countOnlineFromIp(String ip) {
        int n = 0;
        for (Player p : Compat.online()) {
            try {
                if (p.getAddress() != null
                        && ip.equals(p.getAddress().getAddress().getHostAddress())) n++;
            } catch (Throwable ignored) {
            }
        }
        return n;
    }

    private boolean isWhitelisted(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        for (String s : getConfig().getStringList("whitelist-ips")) {
            if (s != null && s.equals(ip)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Kilit modu
    // ------------------------------------------------------------------

    boolean isLocked() {
        return System.currentTimeMillis() < lockdownUntil.get();
    }

    void startLockdown() {
        int secs = Math.max(1, getConfig().getInt("join-rate.lockdown-seconds", 60));
        lockdownUntil.set(System.currentTimeMillis() + secs * 1000L);
        announce(getConfig().getString("messages.lockdown-start",
                "&c[AntiBot] Saldiri algilandi, kilit modu %time%s acildi.")
                .replace("%time%", String.valueOf(secs)));
    }

    void stopLockdown() {
        if (lockdownUntil.getAndSet(0) > 0) {
            announce(getConfig().getString("messages.lockdown-end",
                    "&a[AntiBot] Kilit modu kapandi."));
        }
    }

    /** Yetkililere ve konsola bildirir. */
    private void announce(final String raw) {
        if (raw == null || raw.isEmpty()) return;
        final String text = Compat.color(raw);
        Runnable task = new Runnable() {
            @Override
            public void run() {
                getLogger().info(Compat.strip(text));
                if (!getConfig().getBoolean("notify-staff", true)) return;
                for (Player p : Compat.online()) {
                    if (p.hasPermission("antibot.notify")) p.sendMessage(text);
                }
            }
        };
        // Giris kontrolleri async calisir; oyunculara ana thread'den yazmaliyiz
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (Throwable ignored) {
            // Sunucu kapanirken zamanlayici yeni gorev kabul etmeyebilir
        }
    }

    /** Kilit suresi dolunca kapatir. */
    private void startWatchdog() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (lockdownUntil.get() > 0 && !isLocked()) stopLockdown();

                // Zaman asimina ugrayan dogrulamalari at
                int timeout = getConfig().getInt("captcha.timeout", 45);
                if (timeout <= 0 || pending.isEmpty()) return;
                long now = System.currentTimeMillis();
                for (Map.Entry<UUID, Captcha> en : new HashMap<UUID, Captcha>(pending).entrySet()) {
                    if (now - en.getValue().shownAt < timeout * 1000L) continue;
                    Player p = Bukkit.getPlayer(en.getKey());
                    pending.remove(en.getKey());
                    if (p != null && p.isOnline()) {
                        p.kickPlayer(Compat.color(getConfig().getString(
                                "messages.captcha-timeout", "&cDogrulama suresi doldu.")));
                    }
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    // ------------------------------------------------------------------
    // 4: GUI dogrulama
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        if (!getConfig().getBoolean("captcha.enabled", true)) return;
        if (p.hasPermission("antibot.bypass")) return;

        String name = p.getName().toLowerCase(Locale.ENGLISH);
        if (getConfig().getBoolean("captcha.only-first-join", true) && verified.contains(name)) return;
        if (getConfig().getBoolean("captcha.only-during-attack", false) && !isLocked()) return;

        Captcha c = new Captcha();
        c.anchor = p.getLocation().clone();
        pending.put(p.getUniqueId(), c);

        new BukkitRunnable() {
            @Override
            public void run() {
                if (p.isOnline() && pending.containsKey(p.getUniqueId())) openCaptcha(p);
            }
        }.runTaskLater(this, 5L);
    }

    private void openCaptcha(Player p) {
        List<Material> pool = captchaPool();
        if (pool.size() < 2) {
            // Materyal bulunamadiysa dogrulamayi atla, oyuncuyu kilitleme
            pass(p, false);
            return;
        }

        int size = 27;
        int answerSlot = random.nextInt(size);
        Material answer = pool.get(random.nextInt(pool.size()));

        CaptchaHolder holder = new CaptchaHolder(answerSlot);
        Inventory inv = Bukkit.createInventory(holder, size, Compat.title32(
                getConfig().getString("messages.captcha-title", "&8Dogrulama")));
        holder.setInventory(inv);

        for (int i = 0; i < size; i++) {
            Material m;
            int guard = 0;
            do {
                m = pool.get(random.nextInt(pool.size()));
            } while (m == answer && ++guard < 20);
            inv.setItem(i, Compat.item(m, "&7?", null));
        }
        inv.setItem(answerSlot, Compat.item(answer,
                getConfig().getString("messages.captcha-answer-name", "&a&lBUNA TIKLA"),
                Arrays.asList(getConfig().getString("messages.captcha-answer-lore",
                        "&7Insan oldugunu dogrula"))));

        p.openInventory(inv);
        p.sendMessage(Compat.color(getConfig().getString("messages.captcha-hint",
                "&eDevam etmek icin isaretli esyaya tikla.")));
    }

    private List<Material> captchaPool() {
        List<Material> out = new ArrayList<Material>();
        for (String s : getConfig().getStringList("captcha.items")) {
            Material m = Compat.material(s);
            if (m != null && m != Material.AIR && !out.contains(m)) out.add(m);
        }
        if (out.size() < 2) {
            for (String s : new String[]{"STONE", "DIRT", "OAK_LOG", "LOG", "SAND", "GRAVEL",
                    "COBBLESTONE", "GLASS", "BRICK", "CLAY"}) {
                Material m = Compat.material(s);
                if (m != null && m != Material.AIR && !out.contains(m)) out.add(m);
            }
        }
        return out;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();

        Inventory top = e.getView() == null ? null : e.getView().getTopInventory();
        boolean captchaWindow = top != null && top.getHolder() instanceof CaptchaHolder;

        if (!captchaWindow) {
            if (frozen(p)) e.setCancelled(true);
            return;
        }
        e.setCancelled(true);

        Captcha c = pending.get(p.getUniqueId());
        if (c == null) return;

        CaptchaHolder holder = (CaptchaHolder) top.getHolder();
        if (e.getRawSlot() == holder.getAnswerSlot()) {
            pass(p, true);
            return;
        }
        if (e.getRawSlot() < 0 || e.getRawSlot() >= top.getSize()) return;

        c.attempts++;
        int max = getConfig().getInt("captcha.attempts", 3);
        if (max > 0 && c.attempts >= max) {
            pending.remove(p.getUniqueId());
            p.kickPlayer(Compat.color(getConfig().getString(
                    "messages.captcha-failed", "&cDogrulama basarisiz.")));
            return;
        }
        p.sendMessage(Compat.color(getConfig().getString(
                "messages.captcha-wrong", "&cYanlis. Kalan hak: &e%left%")
                .replace("%left%", String.valueOf(max - c.attempts))));
        Compat.sound(p, "no", 0.8f, 1.0f);
        openCaptcha(p);
    }

    private void pass(final Player p, boolean announceOk) {
        pending.remove(p.getUniqueId());
        String name = p.getName().toLowerCase(Locale.ENGLISH);
        if (verified.add(name)) saveVerified();

        new BukkitRunnable() {
            @Override
            public void run() {
                if (p.isOnline()) p.closeInventory();
            }
        }.runTask(this);

        if (announceOk) {
            p.sendMessage(Compat.color(getConfig().getString(
                    "messages.captcha-passed", "&aDogrulama tamam, iyi oyunlar!")));
            Compat.sound(p, "levelup", 0.8f, 1.2f);
        }
    }

    // ------------------------------------------------------------------
    // Dogrulanana kadar kisitlama
    // ------------------------------------------------------------------

    private boolean frozen(Player p) {
        return p != null && pending.containsKey(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        Captcha c = pending.get(e.getPlayer().getUniqueId());
        if (c == null || e.getTo() == null) return;
        if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                || e.getFrom().getBlockY() != e.getTo().getBlockY()
                || e.getFrom().getBlockZ() != e.getTo().getBlockZ()) {
            Location back = e.getFrom().clone();
            back.setYaw(e.getTo().getYaw());
            back.setPitch(e.getTo().getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCmd(PlayerCommandPreprocessEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (frozen(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player && frozen((Player) e.getEntity())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageBy(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player && frozen((Player) e.getDamager())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getWhoClicked() instanceof Player && frozen((Player) e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent e) {
        if (!(e.getPlayer() instanceof Player)) return;
        if (frozen((Player) e.getPlayer())
                && !(e.getInventory().getHolder() instanceof CaptchaHolder)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof CaptchaHolder)) return;
        if (!(e.getPlayer() instanceof Player)) return;
        final Player p = (Player) e.getPlayer();
        if (!pending.containsKey(p.getUniqueId())) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!p.isOnline() || !pending.containsKey(p.getUniqueId())) return;
                Object h = null;
                try {
                    h = p.getOpenInventory().getTopInventory().getHolder();
                } catch (Throwable ignored) {
                }
                if (!(h instanceof CaptchaHolder)) openCaptcha(p);
            }
        }.runTask(this);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        pending.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Veri
    // ------------------------------------------------------------------

    private void saveVerified() {
        try {
            data.set("verified", new ArrayList<String>(verified));
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            data.save(dataFile);
        } catch (IOException ex) {
            getLogger().warning("verified.yml kaydedilemedi: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Komut
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("antibot.admin")) {
            sender.sendMessage(Compat.color(getConfig().getString(
                    "messages.no-permission", "&cBunun icin yetkin yok.")));
            return true;
        }
        String sub = args.length >= 1 ? args[0].toLowerCase(Locale.ENGLISH) : "";

        if (sub.equals("reload")) {
            reloadConfig();
            compilePatterns();
            sender.sendMessage(Compat.color("&aAntiBot yeniden yuklendi."));
            return true;
        }
        if (sub.equals("lock") || sub.equals("kilit")) {
            startLockdown();
            return true;
        }
        if (sub.equals("unlock") || sub.equals("ac")) {
            stopLockdown();
            sender.sendMessage(Compat.color("&aKilit modu kapatildi."));
            return true;
        }
        if (sub.equals("status") || sub.equals("durum")) {
            long left = (lockdownUntil.get() - System.currentTimeMillis()) / 1000L;
            sender.sendMessage(Compat.color("&7Kilit modu: "
                    + (isLocked() ? "&cACIK &7(" + Math.max(0, left) + "s)" : "&akapali")));
            sender.sendMessage(Compat.color("&7Son pencerede giris: &f" + recentJoins.size()));
            sender.sendMessage(Compat.color("&7Dogrulanmis oyuncu: &f" + verified.size()));
            sender.sendMessage(Compat.color("&7Dogrulama bekleyen: &f" + pending.size()));
            return true;
        }
        sender.sendMessage(Compat.color("&7/" + label
                + " status &8| &7lock &8| &7unlock &8| &7reload"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("antibot.admin")) {
            List<String> out = new ArrayList<String>();
            for (String s : Arrays.asList("status", "lock", "unlock", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ENGLISH))) out.add(s);
            }
            return out;
        }
        return Collections.emptyList();
    }
}
