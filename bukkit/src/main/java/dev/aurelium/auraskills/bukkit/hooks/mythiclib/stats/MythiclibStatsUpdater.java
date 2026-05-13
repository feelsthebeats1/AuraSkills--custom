package dev.aurelium.auraskills.bukkit.hooks.mythiclib.stats;

import dev.aurelium.auraskills.api.event.skill.SkillLevelUpEvent;
import dev.aurelium.auraskills.api.event.user.UserLoadEvent;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.stat.Stat;
import dev.aurelium.auraskills.api.stat.Stats;
import dev.aurelium.auraskills.api.trait.Trait;
import dev.aurelium.auraskills.bukkit.user.BukkitUser;
import dev.aurelium.auraskills.common.AuraSkillsPlugin;
import dev.aurelium.auraskills.common.hooks.Hook;
import dev.aurelium.auraskills.common.user.User;
import io.lumine.mythic.lib.api.player.MMOPlayerData;
import io.lumine.mythic.lib.api.stat.SharedStat;
import io.lumine.mythic.lib.player.modifier.ModifierType;
import net.Indyuce.mmocore.MMOCore;
import net.Indyuce.mmocore.api.player.PlayerData;
import net.Indyuce.mmocore.api.player.attribute.AttributeModifier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MythiclibStatsUpdater extends Hook implements Listener {

    private static final String MODIFIER_PREFIX = "AuraSkills.";
    private final Map<Stat, StatMapping> statMappings;

    public MythiclibStatsUpdater(AuraSkillsPlugin plugin, ConfigurationNode config) {
        super(plugin, config);
        this.statMappings = loadMappings(config);
    }

    @Override
    public Class<? extends Hook> getTypeClass() {
        return MythiclibStatsUpdater.class;
    }

    @EventHandler
    public void onUserLoad(UserLoadEvent event) {
        BukkitUser user = BukkitUser.getUser(event.getUser());
        plugin.getScheduler().scheduleSync(() -> update(user), 1L, TimeUnit.SECONDS);
    }

    @EventHandler
    public void onSkillLevelUp(SkillLevelUpEvent event) {
        update(BukkitUser.getUser(event.getUser()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    public void update(User user) {
        Player player = BukkitUser.getPlayer(user);
        if (player == null || !player.isOnline()) {
            return;
        }

        MMOPlayerData mythicData = MMOPlayerData.getOrNull(player.getUniqueId());
        if (mythicData == null) {
            mythicData = MMOPlayerData.setup(player);
        }

        updateMythicLibStats(user, mythicData);
        updateMMOCoreAttributes(user, mythicData, player);
    }

    private void updateMythicLibStats(User user, MMOPlayerData data) {
        for (Map.Entry<Stat, StatMapping> entry : statMappings.entrySet()) {
            Stat stat = entry.getKey();
            String key = modifierKey(stat);

            for (String mythicStat : entry.getValue().mythicLibStats()) {
                data.getStatMap().getInstance(mythicStat).removeIf(key::equals);
            }

            double value = getExportValue(user, stat);
            if (value == 0.0 || !stat.isEnabled()) {
                continue;
            }

            for (String mythicStat : entry.getValue().mythicLibStats()) {
                new io.lumine.mythic.lib.api.stat.modifier.StatModifier(key, mythicStat, value, ModifierType.FLAT).register(data);
            }
        }
    }

    private void updateMMOCoreAttributes(User user, MMOPlayerData mythicData, Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOCore") || !PlayerData.has(player)) {
            return;
        }

        for (Map.Entry<Stat, StatMapping> entry : statMappings.entrySet()) {
            Stat stat = entry.getKey();
            String key = modifierKey(stat);

            for (String attribute : entry.getValue().mmocoreAttributes()) {
                unregisterAttribute(mythicData, key, attribute);
            }

            if (!stat.isEnabled() || valueIsZero(user, stat)) {
                continue;
            }

            for (String attribute : entry.getValue().mmocoreAttributes()) {
                if (!MMOCore.plugin.attributeManager.has(attribute)) {
                    continue;
                }
                new AttributeModifier(key, attribute, getExportValue(user, stat), ModifierType.FLAT).register(mythicData);
            }
        }
    }

    private void clear(Player player) {
        MMOPlayerData data = MMOPlayerData.getOrNull(player.getUniqueId());
        if (data == null) {
            return;
        }

        for (StatMapping mapping : statMappings.values()) {
            for (String stat : mapping.mythicLibStats()) {
                data.getStatMap().getInstance(stat).removeIf(key -> key.startsWith(MODIFIER_PREFIX));
            }
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("MMOCore") || !PlayerData.has(player)) {
            return;
        }
        for (Map.Entry<Stat, StatMapping> entry : statMappings.entrySet()) {
            for (String attribute : entry.getValue().mmocoreAttributes()) {
                unregisterAttribute(data, modifierKey(entry.getKey()), attribute);
            }
        }
    }

    private void unregisterAttribute(MMOPlayerData data, String key, String attribute) {
        try {
            new AttributeModifier(key, attribute, 0.0, ModifierType.FLAT).unregister(data);
        } catch (RuntimeException ignored) {
            // MMOCore attributes are user-configurable; missing attributes are skipped.
        }
    }

    private double getExportValue(User user, Stat stat) {
        if (stat.hasDirectTrait()) {
            Trait trait = stat.getTraits().getFirst();
            return user.getEffectiveTraitLevel(trait);
        }
        return user.getStatLevel(stat);
    }

    private boolean valueIsZero(User user, Stat stat) {
        return getExportValue(user, stat) == 0.0;
    }

    private String modifierKey(Stat stat) {
        return MODIFIER_PREFIX + stat.getId().toString();
    }

    private Map<Stat, StatMapping> loadMappings(ConfigurationNode config) {
        Map<Stat, StatMapping> mappings = new HashMap<>();
        ConfigurationNode mappingsNode = config.node("stat_mappings");

        if (mappingsNode.virtual()) {
            return defaultMappings();
        }

        for (Map.Entry<Object, ? extends ConfigurationNode> entry : mappingsNode.childrenMap().entrySet()) {
            String statName = entry.getKey().toString().toLowerCase(Locale.ROOT);
            Stat stat = plugin.getStatRegistry().getOrNull(NamespacedId.fromDefault(statName));

            if (stat == null) {
                plugin.logger().warn("Unknown AuraSkills stat in MythicLib hook mapping: " + statName);
                continue;
            }

            try {
                ConfigurationNode statNode = entry.getValue();
                List<String> mythicStats = sanitizeList(statNode.node("mythiclib_stats").getList(String.class, new ArrayList<>()));
                List<String> attributes = sanitizeList(statNode.node("mmocore_attributes").getList(String.class, new ArrayList<>()));
                mappings.put(stat, new StatMapping(mythicStats, attributes));
            } catch (SerializationException e) {
                plugin.logger().warn("Invalid MythicLib hook mapping for AuraSkills stat " + statName + ": " + e.getMessage());
            }
        }

        return mappings;
    }

    private Map<Stat, StatMapping> defaultMappings() {
        Map<Stat, StatMapping> mappings = new HashMap<>();
        mappings.put(Stats.STRENGTH, mapping(List.of(SharedStat.ATTACK_DAMAGE), "strength"));
        mappings.put(Stats.HEALTH, mapping(List.of(SharedStat.MAX_HEALTH), "health"));
        mappings.put(Stats.REGENERATION, mapping(List.of(SharedStat.HEALTH_REGENERATION), "regeneration"));
        mappings.put(Stats.LUCK, mapping(List.of(SharedStat.LUCK), "luck"));
        mappings.put(Stats.WISDOM, mapping(List.of(SharedStat.MAX_MANA), "wisdom"));
        mappings.put(Stats.TOUGHNESS, mapping(List.of(SharedStat.ARMOR_TOUGHNESS), "toughness"));
        mappings.put(Stats.CRIT_CHANCE, mapping(List.of(SharedStat.CRITICAL_STRIKE_CHANCE), "crit_chance"));
        mappings.put(Stats.CRIT_DAMAGE, mapping(List.of(SharedStat.CRITICAL_STRIKE_POWER), "crit_damage"));
        mappings.put(Stats.SPEED, mapping(List.of(SharedStat.MOVEMENT_SPEED), "speed"));
        return mappings;
    }

    private StatMapping mapping(List<String> mythicStats, String attribute) {
        return new StatMapping(mythicStats, List.of(attribute));
    }

    private List<String> sanitizeList(List<String> values) {
        List<String> sanitized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            sanitized.add(value.trim());
        }
        return sanitized;
    }

    private record StatMapping(List<String> mythicLibStats, List<String> mmocoreAttributes) {
    }

}
