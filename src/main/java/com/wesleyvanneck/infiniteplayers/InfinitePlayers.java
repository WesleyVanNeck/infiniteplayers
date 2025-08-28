package com.wesleyvanneck.infiniteplayers.infiniteplayers;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class InfinitePlayers extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getLogger().info("InfinitePlayers enabled!");
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        getLogger().info("InfinitePlayers disabled!");
    }

    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        // If the server thinks it's full, always allow the player to join
        if (event.getResult() == PlayerLoginEvent.Result.KICK_FULL) {
            event.allow();
        }
    }
}
