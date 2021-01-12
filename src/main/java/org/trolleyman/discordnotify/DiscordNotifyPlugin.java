package org.trolleyman.discordnotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.BasicHttpContext;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;

public class DiscordNotifyPlugin extends JavaPlugin implements Listener {
    private final FileConfiguration config = getConfig();
    private final String defaultWebhookString = config.getDefaults() == null ? null : config.getDefaults().getString("discord-notify.webhook");
    private CloseableHttpClient client;
    private URL discordWebhook;
    private URI discordWebhookUri;
    private Set<DiscordEvent> subscribedEvents;
    private boolean enabled;

    private Plugin getPlugin() {
        return getPlugin(this.getClass());
    }

    private DiscordEvent parseDiscordEvent(String eventName) {
        for (DiscordEvent event : DiscordEvent.values()) {
            if (eventName.equals(event.name().toLowerCase(Locale.ROOT).replace("_", "-"))) {
                return event;
            }
        }
        return null;
    }

    @Override
    public void onEnable() {
        // Save defaults
        saveDefaultConfig();
        config.options().copyDefaults(true);
        saveConfig();

        // Get config options
        subscribedEvents = new HashSet<>();
        for (String eventName : config.getStringList("discord-notify.events")) {
            DiscordEvent event = parseDiscordEvent(eventName);
            if (event == null) {
                getLogger().warning("Unknown event \"" + eventName + "\"");
                continue;
            }
            subscribedEvents.add(event);
        }

        String webhookString = config.getString("discord-notify.webhook");
        if (webhookString == null) {
            getLogger().warning("Discord webhook not set");
        } else if (webhookString.equals(defaultWebhookString)) {
            getLogger().warning("Discord webhook URL must be set to something other than the default");
        } else {
            try {
                discordWebhook = new URL(webhookString);
                discordWebhookUri = new URI(webhookString);
            } catch (MalformedURLException | URISyntaxException ex) {
                getLogger().log(Level.WARNING, "Discord webhook URL invalid: \"" + webhookString + "\"", ex);
                discordWebhook = null;
            }
        }
        enabled = discordWebhook != null && !subscribedEvents.isEmpty();

        // Create HTTP client
        client = HttpClients.custom().build();

        // Register handler
        getServer().getPluginManager().registerEvents(this, this);

        // Debug logging
        String logMessage = getPlugin().getName() + (enabled ? "" : " not") + " enabled (webhook=" + discordWebhook + ", events=" + String.join(", ", (Iterable<String>) subscribedEvents.stream().map(Enum::name)::iterator) + ")";
        if (enabled) {
            getLogger().info(logMessage);
        } else {
            getLogger().warning(logMessage);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled)
            return;
        if (!subscribedEvents.contains(DiscordEvent.PLAYER_JOIN))
            return;

        sendDiscordMessage(event.getPlayer().getDisplayName() + " has joined.");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!enabled)
            return;
        if (!subscribedEvents.contains(DiscordEvent.PLAYER_QUIT))
            return;

        sendDiscordMessage(event.getPlayer().getDisplayName() + " has quit.");
    }

    private void sendDiscordMessage(String message) {
        Bukkit.getScheduler().runTaskAsynchronously(getPlugin(), () -> {
            getLogger().info("Sending Discord message: \"" + message + "\"...");

            // Construct JSON (https://discord.com/developers/docs/resources/webhook#execute-webhook)
            JsonObject json = new JsonObject();
            json.addProperty("content", message);
            JsonObject allowedMentionsJson = new JsonObject();
            allowedMentionsJson.add("parse", new JsonArray());
            json.add("allowed_mentions", allowedMentionsJson);

            // Post JSON
            HttpEntity httpEntity = new StringEntity(json.toString(), ContentType.APPLICATION_JSON);
            HttpPost post = new HttpPost(discordWebhookUri);
            post.setEntity(httpEntity);

            try (final CloseableHttpResponse response = client.execute(post, new BasicHttpContext())) {
                if (response.getStatusLine().getStatusCode() >= 200 && response.getStatusLine().getStatusCode() < 300) {
                    getLogger().warning("Sent Discord message: \"" + message + "\".");
                } else {
                    getLogger().warning("HTTP POST to Discord webhook returned status code: " + response.getStatusLine());
                }
            } catch (IOException ex) {
                getLogger().log(Level.WARNING, "HTTP POST to Discord webhook encountered an exception", ex);
            }
        });
    }

    @Override
    public void onDisable() {
        try {
            client.close();
        } catch (IOException e) {
            getLogger().warning("HTTP client errored on exit: " + e.getMessage());
        }
        client = null;

        getLogger().info(getPlugin().getName() + " disabled");
    }
}
