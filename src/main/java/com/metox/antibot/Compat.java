package com.metox.antibot;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Surumler arasi uyumluluk katmani - 1.8 ile 1.21.x arasi tek jar.
 *
 * Kural: bu sinif yalnizca 1.8 API'sinde bulunan uyeleri dogrudan cagirir.
 * Daha yeni her sey reflection ile, bulunamazsa sessizce eski yonteme duser.
 */
public final class Compat {

    /** Ana surum numarasi: 1.8.8 -> 8, 1.21.11 -> 21. */
    public static final int MINOR;
    /** Yama numarasi: 1.21.11 -> 11, 1.21 -> 0. */
    public static final int PATCH;
    /** NMS paket eki (ornek: v1_8_R3). 1.17+ surumlerde bos. */
    public static final String NMS;

    private static final Pattern HEX = Pattern.compile("(?i)&#([0-9a-f]{6})");

    static {
        int minor = 8, patch = 0;
        try {
            // "1.21.11-R0.1-SNAPSHOT" -> "1.21.11"
            String raw = Bukkit.getBukkitVersion().split("-")[0];
            String[] parts = raw.split("\\.");
            if (parts.length >= 2) minor = Integer.parseInt(parts[1].replaceAll("[^0-9]", ""));
            if (parts.length >= 3) patch = Integer.parseInt(parts[2].replaceAll("[^0-9]", ""));
        } catch (Throwable ignored) {
        }
        MINOR = minor;
        PATCH = patch;

        String nms = "";
        try {
            String[] parts = Bukkit.getServer().getClass().getName().split("\\.");
            if (parts.length >= 4 && parts[3].startsWith("v")) nms = parts[3];
        } catch (Throwable ignored) {
        }
        NMS = nms;
    }

    private Compat() {}

    /** Surum en az 1.<minor> mu? */
    public static boolean atLeast(int minor) {
        return MINOR >= minor;
    }

    // ------------------------------------------------------------------
    // Metin
    // ------------------------------------------------------------------

    /** &a kodlarini ve 1.16+ surumlerde &#RRGGBB hex kodlarini uygular. */
    public static String color(String in) {
        if (in == null) return "";
        String s = in;
        Matcher m = HEX.matcher(s);
        if (m.find()) {
            m.reset();
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String rep;
                if (MINOR >= 16) {
                    StringBuilder b = new StringBuilder("§x");
                    for (char c : m.group(1).toCharArray()) b.append('§').append(c);
                    rep = b.toString();
                } else {
                    rep = ""; // eski surumde hex yok, sessizce dusur
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep));
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static List<String> color(List<String> in) {
        List<String> out = new ArrayList<String>();
        if (in == null) return out;
        for (String s : in) out.add(color(s));
        return out;
    }

    public static String strip(String in) {
        String c = color(in);
        String s = ChatColor.stripColor(c);
        return s == null ? "" : s;
    }

    /** Envanter basliklari 1.8-1.20 arasi 32 karakterle sinirli. */
    public static String title32(String in) {
        String s = color(in);
        return s.length() > 32 ? s.substring(0, 32) : s;
    }

    // ------------------------------------------------------------------
    // Oyuncular
    // ------------------------------------------------------------------

    private static Method getOnline;
    private static boolean onlineResolved;

    /** 1.8 Player[] ve 1.9+ Collection donuslerini tek listede toplar. */
    @SuppressWarnings("unchecked")
    public static List<Player> online() {
        List<Player> list = new ArrayList<Player>();
        try {
            if (!onlineResolved) {
                onlineResolved = true;
                getOnline = Bukkit.class.getMethod("getOnlinePlayers");
            }
            if (getOnline == null) return list;
            Object res = getOnline.invoke(null);
            if (res instanceof Player[]) {
                for (Player p : (Player[]) res) if (p != null) list.add(p);
            } else if (res instanceof Collection) {
                for (Object o : (Collection<Object>) res) if (o instanceof Player) list.add((Player) o);
            }
        } catch (Throwable ignored) {
        }
        return list;
    }

    /** 1.8'de getItemInHand, 1.9+ surumlerde ana el. */
    public static ItemStack handItem(Player p) {
        if (p == null) return null;
        try {
            Method m = p.getInventory().getClass().getMethod("getItemInMainHand");
            Object it = m.invoke(p.getInventory());
            if (it instanceof ItemStack) return (ItemStack) it;
        } catch (Throwable ignored) {
        }
        try {
            return p.getInventory().getItemInHand();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Maksimum can - 1.8 ve 1.21'de de Damageable uzerinden calisir. */
    public static double maxHealth(LivingEntity e) {
        if (e == null) return 0.0D;
        try {
            return e.getMaxHealth();
        } catch (Throwable ignored) {
        }
        // Cok yeni surumlerde getMaxHealth kalkarsa attribute'a dus
        try {
            Class<?> attrCls = Class.forName("org.bukkit.attribute.Attribute");
            Object attr = null;
            for (String n : new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"}) {
                try {
                    attr = attrCls.getField(n).get(null);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (attr != null) {
                Method get = e.getClass().getMethod("getAttribute", attrCls);
                Object inst = get.invoke(e, attr);
                if (inst != null) {
                    Object v = inst.getClass().getMethod("getValue").invoke(inst);
                    if (v instanceof Number) return ((Number) v).doubleValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 20.0D;
    }

    // ------------------------------------------------------------------
    // Action bar
    // ------------------------------------------------------------------

    private static Boolean spigotBarWorks;

    /** Action bar mesaji - once Spigot API, sonra 1.8 NMS paketi. */
    public static void actionBar(Player p, String text) {
        if (p == null) return;
        String msg = color(text);
        if (spigotBarWorks == null || spigotBarWorks) {
            if (spigotActionBar(p, msg)) {
                spigotBarWorks = Boolean.TRUE;
                return;
            }
            spigotBarWorks = Boolean.FALSE;
        }
        nmsActionBar(p, msg);
    }

    private static boolean spigotActionBar(Player p, String msg) {
        try {
            Class<?> typeCls = Class.forName("net.md_5.bungee.api.ChatMessageType");
            Object action = null;
            for (Object e : typeCls.getEnumConstants()) {
                if (((Enum<?>) e).name().equals("ACTION_BAR")) {
                    action = e;
                    break;
                }
            }
            if (action == null) return false;
            Class<?> baseCls = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> textCls = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Object comp = textCls.getConstructor(String.class).newInstance(msg);
            Object arr = Array.newInstance(baseCls, 1);
            Array.set(arr, 0, comp);
            Object spigot = Player.class.getMethod("spigot").invoke(p);
            spigot.getClass().getMethod("sendMessage", typeCls, arr.getClass()).invoke(spigot, action, arr);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean nmsActionBar(Player p, String msg) {
        if (NMS.isEmpty()) return false;
        try {
            String nms = "net.minecraft.server." + NMS + ".";
            Class<?> chatBase = Class.forName(nms + "IChatBaseComponent");
            Class<?> chatText = Class.forName(nms + "ChatComponentText");
            Class<?> packetChat = Class.forName(nms + "PacketPlayOutChat");
            Class<?> packetCls = Class.forName(nms + "Packet");
            Object comp = chatText.getConstructor(String.class).newInstance(msg);
            Object packet = packetChat.getConstructor(chatBase, byte.class).newInstance(comp, (byte) 2);
            Object handle = p.getClass().getMethod("getHandle").invoke(p);
            Object conn = handle.getClass().getField("playerConnection").get(handle);
            conn.getClass().getMethod("sendPacket", packetCls).invoke(conn, packet);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Title
    // ------------------------------------------------------------------

    private static Method sendTitle5;
    private static boolean title5Resolved;

    /** Ekran ortasi baslik. Sureler tick cinsinden; 1.8'de sureler yok sayilir. */
    public static void title(Player p, String title, String sub, int in, int stay, int out) {
        if (p == null) return;
        String t = color(title == null ? "" : title);
        String s = color(sub == null ? "" : sub);
        if (!title5Resolved) {
            title5Resolved = true;
            try {
                sendTitle5 = Player.class.getMethod("sendTitle", String.class, String.class,
                        int.class, int.class, int.class);
            } catch (Throwable ignored) {
                sendTitle5 = null;
            }
        }
        if (sendTitle5 != null) {
            try {
                sendTitle5.invoke(p, t, s, in, stay, out);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            p.sendTitle(t, s);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Sesler - isimler surumden surume degisti, mantiksal ad kullan
    // ------------------------------------------------------------------

    private static final Map<String, String[]> SOUNDS = new HashMap<String, String[]>();

    static {
        // {1.13+, 1.9-1.12, 1.8}
        SOUNDS.put("click", new String[]{"ui.button.click", "ui.button.click", "random.click"});
        SOUNDS.put("levelup", new String[]{"entity.player.levelup", "entity.player.levelup", "random.levelup"});
        SOUNDS.put("orb", new String[]{"entity.experience_orb.pickup", "entity.experience_orb.pickup", "random.orb"});
        SOUNDS.put("anvil", new String[]{"block.anvil.land", "block.anvil.land", "random.anvil_land"});
        SOUNDS.put("pling", new String[]{"block.note_block.pling", "block.note.pling", "note.pling"});
        SOUNDS.put("bass", new String[]{"block.note_block.bass", "block.note.bass", "note.bass"});
        SOUNDS.put("no", new String[]{"entity.villager.no", "entity.villager.no", "mob.villager.no"});
        SOUNDS.put("yes", new String[]{"entity.villager.yes", "entity.villager.yes", "mob.villager.yes"});
        SOUNDS.put("chest", new String[]{"block.chest.open", "block.chest.open", "random.chestopen"});
        SOUNDS.put("explode", new String[]{"entity.generic.explode", "entity.generic.explode", "random.explode"});
        SOUNDS.put("teleport", new String[]{"entity.enderman.teleport", "entity.endermen.teleport", "mob.endermen.portal"});
        SOUNDS.put("hurt", new String[]{"entity.player.hurt", "entity.player.hurt", "game.player.hurt"});
        SOUNDS.put("break", new String[]{"entity.item.break", "entity.item.break", "random.break"});
    }

    /** Mantiksal ses adini surume gore cevirir; bilinmeyen ad oldugu gibi kullanilir. */
    public static String soundName(String key) {
        String[] v = SOUNDS.get(key);
        if (v == null) return key;
        if (MINOR >= 13) return v[0];
        if (MINOR >= 9) return v[1];
        return v[2];
    }

    /** Oyuncuya ses calar. playSound(Location,String,float,float) her surumde var. */
    public static void sound(Player p, String key, float volume, float pitch) {
        if (p == null) return;
        try {
            p.playSound(p.getLocation(), soundName(key), volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    public static void sound(Player p, Location at, String key, float volume, float pitch) {
        if (p == null || at == null) return;
        try {
            p.playSound(at, soundName(key), volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Materyaller
    // ------------------------------------------------------------------

    private static final Map<String, Material> MAT_CACHE = new HashMap<String, Material>();

    /** Verilen adlardan sunucuda bulunan ilkini dondurur, hicbiri yoksa null. */
    public static Material material(String... names) {
        if (names == null || names.length == 0) return null;
        StringBuilder key = new StringBuilder();
        for (String n : names) key.append(n).append('|');
        String k = key.toString();
        if (MAT_CACHE.containsKey(k)) return MAT_CACHE.get(k);

        Material found = null;
        for (String n : names) {
            if (n == null || n.isEmpty()) continue;
            try {
                Material m = Material.matchMaterial(n);
                if (m != null) {
                    found = m;
                    break;
                }
            } catch (Throwable ignored) {
            }
            try {
                Material m = Material.valueOf(n.toUpperCase());
                found = m;
                break;
            } catch (Throwable ignored) {
            }
        }
        MAT_CACHE.put(k, found);
        return found;
    }

    /** Bulunamazsa guvenli bir varsayilana duser. */
    public static Material materialOr(Material fallback, String... names) {
        Material m = material(names);
        return m == null ? fallback : m;
    }

    /** Cam paneli - 1.13 oncesi STAINED_GLASS_PANE, sonrasi renkli adlar. */
    public static Material pane(String color13) {
        return materialOr(Material.AIR, color13, "STAINED_GLASS_PANE", "GLASS_PANE", "THIN_GLASS");
    }

    public static ItemStack item(Material mat, String name, List<String> lore) {
        ItemStack it = new ItemStack(mat == null ? Material.STONE : mat);
        try {
            ItemMeta meta = it.getItemMeta();
            if (meta != null) {
                if (name != null) meta.setDisplayName(color(name));
                if (lore != null && !lore.isEmpty()) meta.setLore(color(lore));
                it.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
        }
        return it;
    }

    // ------------------------------------------------------------------
    // Oyuncu kafasi - 1.13 oncesi SKULL_ITEM:3, sonrasi PLAYER_HEAD
    // ------------------------------------------------------------------

    /** Bos oyuncu kafasi itemi olusturur (1.13 oncesi SKULL_ITEM:3). */
    @SuppressWarnings("deprecation")
    public static ItemStack blankHead() {
        Material m = material("PLAYER_HEAD", "SKULL_ITEM");
        if (m == null) return new ItemStack(Material.STONE);
        return MINOR >= 13 ? new ItemStack(m, 1) : new ItemStack(m, 1, (short) 3);
    }

    /**
     * Kafaya sahibini yazar. Oyuncu cevrimiciyse gercek skin dokusunu tasir,
     * degilse isimle eslestirir.
     */
    public static void applySkullOwner(ItemMeta meta, Player online, String name) {
        if (meta == null) return;
        if (online != null) {
            // 1.18+ : PlayerProfile dokuyu da tasir
            try {
                Object profile = online.getClass().getMethod("getPlayerProfile").invoke(online);
                Class<?> ppCls = Class.forName("org.bukkit.profile.PlayerProfile");
                meta.getClass().getMethod("setOwnerProfile", ppCls).invoke(meta, profile);
                return;
            } catch (Throwable ignored) {
            }
            // 1.8 - 1.17 : CraftPlayer.getProfile() doku iceren GameProfile dondurur
            try {
                Object gameProfile = online.getClass().getMethod("getProfile").invoke(online);
                java.lang.reflect.Field f = meta.getClass().getDeclaredField("profile");
                f.setAccessible(true);
                f.set(meta, gameProfile);
                return;
            } catch (Throwable ignored) {
            }
        }
        String owner = online != null ? online.getName() : name;
        if (owner == null || owner.isEmpty()) return;
        try {
            Class<?> offline = Class.forName("org.bukkit.OfflinePlayer");
            Object op = online != null ? online : Bukkit.getOfflinePlayer(owner);
            meta.getClass().getMethod("setOwningPlayer", offline).invoke(meta, op);
            return;
        } catch (Throwable ignored) {
        }
        try {
            meta.getClass().getMethod("setOwner", String.class).invoke(meta, owner);
        } catch (Throwable ignored) {
        }
    }

    /** Sahibi belli, hazir bir oyuncu kafasi. */
    public static ItemStack playerHead(Player online, String name) {
        ItemStack head = blankHead();
        try {
            ItemMeta meta = head.getItemMeta();
            if (meta != null) {
                applySkullOwner(meta, online, name);
                head.setItemMeta(meta);
            }
        } catch (Throwable ignored) {
        }
        return head;
    }

    // ------------------------------------------------------------------
    // Varlik yardimcilari
    // ------------------------------------------------------------------

    /** ArmorStand.setMarker - 1.8.3+ var, yoksa sessizce atlanir. */
    public static void setMarker(Entity stand, boolean marker) {
        try {
            stand.getClass().getMethod("setMarker", boolean.class).invoke(stand, marker);
        } catch (Throwable ignored) {
        }
    }

    /** Entity.setGravity - 1.9+ var. 1.8'de NoGravity NBT'si yok, sessizce atlanir. */
    public static void setGravity(Entity e, boolean gravity) {
        try {
            e.getClass().getMethod("setGravity", boolean.class).invoke(e, gravity);
        } catch (Throwable ignored) {
        }
    }

    /** Entity.setInvulnerable - 1.9+ var. */
    public static void setInvulnerable(Entity e, boolean invulnerable) {
        try {
            e.getClass().getMethod("setInvulnerable", boolean.class).invoke(e, invulnerable);
        } catch (Throwable ignored) {
        }
    }

    /** Entity.setSilent - 1.10+ var. */
    public static void setSilent(Entity e, boolean silent) {
        try {
            e.getClass().getMethod("setSilent", boolean.class).invoke(e, silent);
        } catch (Throwable ignored) {
        }
    }

    /** Gorunmez, carpismasiz, yercekimsiz hologram tasiyicisi ayarlar. */
    public static void prepHologramStand(Entity stand) {
        try {
            stand.getClass().getMethod("setVisible", boolean.class).invoke(stand, false);
        } catch (Throwable ignored) {
        }
        setGravity(stand, false);
        setMarker(stand, true);
        setInvulnerable(stand, true);
        setSilent(stand, true);
        try {
            stand.getClass().getMethod("setBasePlate", boolean.class).invoke(stand, false);
        } catch (Throwable ignored) {
        }
        try {
            stand.getClass().getMethod("setArms", boolean.class).invoke(stand, false);
        } catch (Throwable ignored) {
        }
        try {
            stand.getClass().getMethod("setCanPickupItems", boolean.class).invoke(stand, false);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Dunya
    // ------------------------------------------------------------------

    /** World.getMinHeight() 1.17+ ile geldi; eski surumlerde 0. */
    public static int minHeight(org.bukkit.World world) {
        if (world == null) return 0;
        try {
            Object v = world.getClass().getMethod("getMinHeight").invoke(world);
            if (v instanceof Number) return ((Number) v).intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    // ------------------------------------------------------------------
    // Parcacik - 1.9+ Particle enum'u, 1.8'de Effect
    // ------------------------------------------------------------------

    private static Object particleEnumValue;
    private static Method spawnParticle;
    private static boolean particleResolved;
    private static String lastParticleName;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void resolveParticle(String... names) {
        String key = names.length > 0 ? names[0] : "";
        if (particleResolved && key.equals(lastParticleName)) return;
        particleResolved = true;
        lastParticleName = key;
        particleEnumValue = null;
        spawnParticle = null;
        try {
            Class<?> particleCls = Class.forName("org.bukkit.Particle");
            for (String n : names) {
                try {
                    particleEnumValue = Enum.valueOf((Class<Enum>) particleCls.asSubclass(Enum.class), n);
                    break;
                } catch (Throwable ignored) {
                }
            }
            spawnParticle = Player.class.getMethod("spawnParticle", particleCls,
                    double.class, double.class, double.class, int.class);
        } catch (Throwable ignored) {
        }
    }

    /** Tek oyuncuya parcacik gosterir. 1.8'de Effect yedegine duser. */
    public static void particle(Player p, Location loc, String... names) {
        if (p == null || loc == null || names.length == 0) return;
        resolveParticle(names);
        if (spawnParticle != null && particleEnumValue != null) {
            try {
                spawnParticle.invoke(p, particleEnumValue, loc.getX(), loc.getY(), loc.getZ(), 1);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            org.bukkit.Effect eff = org.bukkit.Effect.valueOf("HAPPY_VILLAGER");
            p.getWorld().playEffect(loc, eff, 0);
        } catch (Throwable ignored) {
        }
    }
}
