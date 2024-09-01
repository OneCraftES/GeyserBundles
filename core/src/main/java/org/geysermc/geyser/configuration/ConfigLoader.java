/*
 * Copyright (c) 2024 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.configuration;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.geysermc.geyser.Constants;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.NodePath;
import org.spongepowered.configurate.interfaces.InterfaceDefaultOptions;
import org.spongepowered.configurate.transformation.ConfigurationTransformation;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.spongepowered.configurate.NodePath.path;
import static org.spongepowered.configurate.transformation.TransformAction.remove;
import static org.spongepowered.configurate.transformation.TransformAction.rename;

public final class ConfigLoader {
    private static final String HEADER = """
            --------------------------------
            Geyser Configuration File

            A bridge between Minecraft: Bedrock Edition and Minecraft: Java Edition.

            GitHub: https://github.com/GeyserMC/Geyser
            Discord: https://discord.gg/geysermc
            Wiki: https://wiki.geysermc.org/

            NOTICE: See https://wiki.geysermc.org/geyser/setup/ for the setup guide. Many video tutorials are outdated.
            In most cases, especially with server hosting providers, further hosting-specific configuration is required.
            --------------------------------""";

    private static final String ADVANCED_HEADER = """
            --------------------------------
            Geyser ADVANCED Configuration File
            
            In most cases, you do *not* need to mess with this file to get Geyser running.
            Tread with caution.
            --------------------------------
            """;

    public static <T extends GeyserConfig> T load(File file, Class<T> configClass) throws IOException {
        return load(file, configClass, null);
    }

    public static <T extends GeyserConfig> T load(File file, Class<T> configClass, @Nullable Consumer<CommentedConfigurationNode> transformer) throws IOException {
        var loader = createLoader(file, HEADER);

        CommentedConfigurationNode node = loader.load();
        boolean originallyEmpty = !file.exists() || node.isNull();

        // Note for Tim? Needed or else Configurate breaks.
        var migrations = ConfigurationTransformation.versionedBuilder()
            .versionKey("config-version")
                // Pre-Configurate
                .addVersion(5, ConfigurationTransformation.builder()
                    .addAction(path("legacy-ping-passthrough"), configClass == GeyserRemoteConfig.class ? remove() : (path, value) -> {
                        // Invert value
                        value.set(!value.getBoolean());
                        return new Object[]{"integrated-ping-passthrough"};
                    })
                    .addAction(path("remote"), rename("java"))
                    .addAction(path("floodgate-key-file"), (path, value) -> {
                        // Elimate any legacy config values
                        if ("public-key.pem".equals(value.getString())) {
                            value.set("key.pem");
                        }
                        return null;
                    })
                    .addAction(path("default-locale"), (path, value) -> {
                        if (value.getString() == null) {
                            value.set("system");
                        }
                        return null;
                    })
                    .addAction(path("show-cooldown"), (path, value) -> {
                        String s = value.getString();
                        if (s != null) {
                            switch (s) {
                                case "true" -> value.set("title");
                                case "false" -> value.set("disabled");
                            }
                        }
                        return null;
                    })
                    .addAction(path("metrics", "uuid"), (path, value) -> {
                        if ("generateduuid".equals(value.getString())) {
                            // Manually copied config without Metrics UUID creation?
                            value.set(UUID.randomUUID());
                        }
                        return null;
                    })
                    .addAction(path("remote", "address"), (path, value) -> {
                        if ("auto".equals(value.getString())) {
                            // Auto-convert back to localhost
                            value.set("127.0.0.1");
                        }
                        return null;
                    })
                    .addAction(path("metrics", "enabled"), (path, value) -> {
                        // Move to the root, not in the Metrics class.
                        return new Object[]{"enable-metrics"};
                    })
                    .addAction(path("bedrock", "motd1"), rename("primary-motd"))
                    .addAction(path("bedrock", "motd2"), rename("secondary-motd"))
                    // Legacy config values
                    .addAction(path("emote-offhand-workaround"), remove())
                    .addAction(path("allow-third-party-capes"), remove())
                    .addAction(path("allow-third-party-ears"), remove())
                    .addAction(path("general-thread-pool"), remove())
                    .addAction(path("cache-chunks"), remove())
                    .build())
                .build();

        int currentVersion = migrations.version(node);
        migrations.apply(node);
        int newVersion = migrations.version(node);

        T config = node.get(configClass);

        // Serialize the instance to ensure strict field ordering. Additionally, if we serialized back
        // to the old node, existing nodes would only have their value changed, keeping their position
        // at the top of the ordered map, forcing all new nodes to the bottom (regardless of field order).
        // For that reason, we must also create a new node.
        CommentedConfigurationNode newRoot = CommentedConfigurationNode.root(loader.defaultOptions());
        newRoot.set(config);

        // Create the path in a way that Standalone changing the config name will be fine.
        int extensionIndex = file.getName().lastIndexOf(".");
        File advancedConfigPath = new File(file.getParent(), file.getName().substring(0, extensionIndex) + "_advanced" + file.getName().substring(extensionIndex));
        AdvancedConfig advancedConfig = null;

        if (originallyEmpty || currentVersion != newVersion) {

            if (!originallyEmpty && currentVersion > 4) {
                // Only copy comments over if the file already existed, and we are going to replace it

                // Second case: Version 4 is pre-configurate where there were commented out nodes.
                // These get treated as comments on lower nodes, which produces very undesirable results.

                ConfigurationCommentMover.moveComments(node, newRoot);
            } else if (currentVersion <= 4) {
                advancedConfig = migrateToAdvancedConfig(advancedConfigPath, node);
            }

            loader.save(newRoot);
        }
        if (advancedConfig == null) {
            advancedConfig = loadAdvancedConfig(advancedConfigPath);
        }

        if (transformer != null) {
            // We transform AFTER saving so that these specific transformations aren't applied to file.
            transformer.accept(newRoot);
            config = newRoot.get(configClass);
        }

        config.advanced(advancedConfig);

        return config;
    }

    private static AdvancedConfig migrateToAdvancedConfig(File file, ConfigurationNode configRoot) throws IOException {
        List<NodePath> copyFromOldConfig = Stream.of("max-visible-custom-skulls", "custom-skull-render-distance", "scoreboard-packet-threshold", "mtu",
                "floodgate-key-file", "use-direct-connection", "disable-compression")
            .map(NodePath::path).toList();

        var loader = createLoader(file, ADVANCED_HEADER);

        CommentedConfigurationNode advancedNode = CommentedConfigurationNode.root(loader.defaultOptions());
        copyFromOldConfig.forEach(path -> {
            ConfigurationNode node = configRoot.node(path);
            if (!node.virtual()) {
                advancedNode.node(path).mergeFrom(node);
                configRoot.removeChild(path);
            }
        });

        ConfigurationNode metricsUuid = configRoot.node("metrics", "uuid");
        if (!metricsUuid.virtual()) {
            advancedNode.node("metrics-uuid").set(metricsUuid.get(UUID.class));
        }

        advancedNode.node("version").set(Constants.ADVANCED_CONFIG_VERSION);

        AdvancedConfig advancedConfig = advancedNode.get(AdvancedConfig.class);
        // Ensure all fields get populated
        CommentedConfigurationNode newNode = CommentedConfigurationNode.root(loader.defaultOptions());
        newNode.set(advancedConfig);
        loader.save(newNode);
        return advancedConfig;
    }

    private static AdvancedConfig loadAdvancedConfig(File file) throws IOException {
        var loader = createLoader(file, ADVANCED_HEADER);
        if (file.exists()) {
            ConfigurationNode node = loader.load();
            return node.get(AdvancedConfig.class);
        } else {
            ConfigurationNode node = CommentedConfigurationNode.root(loader.defaultOptions());
            node.node("version").set(Constants.ADVANCED_CONFIG_VERSION);
            AdvancedConfig advancedConfig = node.get(AdvancedConfig.class);
            node.set(advancedConfig);
            loader.save(node);
            return advancedConfig;
        }
    }

    private static YamlConfigurationLoader createLoader(File file, String header) {
        return YamlConfigurationLoader.builder()
            .file(file)
            .indent(2)
            .nodeStyle(NodeStyle.BLOCK)
            .defaultOptions(options -> InterfaceDefaultOptions.addTo(options)
                .shouldCopyDefaults(false) // If we use ConfigurationNode#get(type, default), do not write the default back to the node.
                .header(header)
                .serializers(builder -> builder.register(new LowercaseEnumSerializer())))
            .build();
    }

    private ConfigLoader() {
    }
}