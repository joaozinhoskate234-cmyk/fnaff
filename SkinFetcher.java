package me.mlgbot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Busca a skin de um jogador pelo nick (API da Mojang), sem travar o servidor. */
public class SkinFetcher {

    public interface Callback {
        void done(String nick, String value, String signature);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "MLGBot");
        if (c.getResponseCode() != 200) {
            return null;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        r.close();
        return sb.toString();
    }

    public static void fetch(final MLGBotPlugin plugin, final Player asker, final String nick, final Callback cb) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            public void run() {
                String value = null;
                String sig = null;
                String realNick = nick;
                try {
                    String id = get("https://api.mojang.com/users/profiles/minecraft/" + nick);
                    if (id != null) {
                        Matcher mi = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]{32})\"").matcher(id);
                        Matcher mn = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"").matcher(id);
                        if (mn.find()) {
                            realNick = mn.group(1);
                        }
                        if (mi.find()) {
                            String json = get("https://sessionserver.mojang.com/session/minecraft/profile/"
                                    + mi.group(1) + "?unsigned=false");
                            if (json != null) {
                                Matcher mv = Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                                Matcher ms = Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
                                if (mv.find()) {
                                    value = mv.group(1);
                                }
                                if (ms.find()) {
                                    sig = ms.group(1);
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Falha ao buscar a skin de " + nick + ": " + ex.getMessage());
                }
                final String fv = value;
                final String fs = sig;
                final String fn = realNick;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    public void run() {
                        cb.done(fn, fv, fs);
                    }
                });
            }
        });
    }
}
