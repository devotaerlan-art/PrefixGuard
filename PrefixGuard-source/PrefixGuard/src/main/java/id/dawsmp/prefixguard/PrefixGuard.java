package id.dawsmp.prefixguard;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Menolak koneksi yang namanya berawalan prefix Floodgate tetapi bukan
 * pemain Floodgate/Bedrock. Floodgate diakses lewat reflection supaya
 * plugin ini tidak butuh dependensi Floodgate saat dikompilasi.
 */
public final class PrefixGuard extends JavaPlugin implements Listener {

    private String prefix;
    private String kickMessage;
    private boolean logBlocked;

    private Object floodgateApi;
    private Method isFloodgatePlayerMethod;
    private boolean reflectionWarned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = getConfig().getString("prefix", ".");
        kickMessage = getConfig().getString("kick-message",
                "Nama yang diawali '%prefix%' hanya untuk pemain Bedrock.");
        logBlocked = getConfig().getBoolean("log-blocked", true);

        if (prefix == null || prefix.isEmpty()) {
            getLogger().warning("'prefix' kosong di config.yml, PrefixGuard tidak melakukan apa-apa.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PrefixGuard aktif. Awalan yang dijaga: '" + prefix + "'");
    }

    /** Pemeriksaan utama, sebelum pemain benar-benar masuk. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (shouldBlock(player)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, buildMessage());
            logBlock(player);
        }
    }

    /** Lapisan cadangan: kalau entah bagaimana lolos dari pemeriksaan di atas. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (shouldBlock(player)) {
            logBlock(player);
            player.kick(buildMessage());
        }
    }

    private boolean shouldBlock(Player player) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        if (!player.getName().startsWith(prefix)) {
            return false;
        }
        return !isFloodgatePlayer(player.getUniqueId());
    }

    private Component buildMessage() {
        return Component.text(kickMessage.replace("%prefix%", prefix));
    }

    private void logBlock(Player player) {
        if (logBlocked) {
            getLogger().warning("Ditolak: pemain Java non-Floodgate memakai nama berawalan '"
                    + prefix + "': " + player.getName());
        }
    }

    /**
     * True hanya kalau Floodgate mengenali UUID ini sebagai pemain Bedrock.
     * Kalau Floodgate tidak ada atau tidak bisa dipanggil, hasilnya false
     * (artinya nama berawalan akan ditolak, jadi aman).
     */
    private boolean isFloodgatePlayer(UUID uuid) {
        try {
            if (isFloodgatePlayerMethod == null) {
                Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");
                if (floodgate == null || !floodgate.isEnabled()) {
                    return false;
                }
                Class<?> apiClass = Class.forName(
                        "org.geysermc.floodgate.api.FloodgateApi",
                        true,
                        floodgate.getClass().getClassLoader());
                floodgateApi = apiClass.getMethod("getInstance").invoke(null);
                isFloodgatePlayerMethod = apiClass.getMethod("isFloodgatePlayer", UUID.class);
            }
            return (boolean) isFloodgatePlayerMethod.invoke(floodgateApi, uuid);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!reflectionWarned) {
                reflectionWarned = true;
                getLogger().warning("Gagal memanggil Floodgate API: " + e
                        + ". Semua nama berawalan '" + prefix + "' akan ditolak.");
            }
            isFloodgatePlayerMethod = null;
            return false;
        }
    }
}
