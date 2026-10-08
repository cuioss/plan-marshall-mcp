/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;


import org.eclipse.jgit.internal.JGitText;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Drift test of the GraalVM reachability metadata for JGit.
 * <p>
 * JGit loads its {@code JGitText} translation bundle reflectively and reads configuration enums by their
 * constants. The expected metadata is computed from the jar on the classpath; run with {@code -Dpm.native-metadata.write=true} to rewrite the
 * committed files after a version change.
 */
@DisplayName("Native reachability metadata for JGit")
class JgitNativeMetadataTest {

    static final String DIRECTORY = "META-INF/native-image/de.planmarshall/pm-mcp-server-jgit/";

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"reflect-config.json", "resource-config.json"})
    @DisplayName("the committed metadata matches the jars on the classpath")
    void upToDate(String file) throws Exception {
        String expected = "reflect-config.json".equals(file) ? reflectConfig() : resourceConfig();
        if (Boolean.getBoolean("pm.native-metadata.write")) {
            Path target = Path.of("src/main/resources").resolve(DIRECTORY + file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, expected, StandardCharsets.UTF_8);
        }

        assertEquals(expected, committed(file));
    }

    @Test
    @DisplayName("covers the JGit translation bundle and a configuration enum")
    void coversKnownTypes() throws Exception {
        String reflect = committed("reflect-config.json");

        for (String type : List.of(JGitText.class.getName(), "org.eclipse.jgit.lib.CoreConfig$AutoCRLF")) {
            assertTrue(reflect.contains("\"name\":\"" + type + "\""), type);
        }
    }

    private static String committed(String file) throws IOException {
        try (InputStream in = JgitNativeMetadataTest.class.getClassLoader().getResourceAsStream(DIRECTORY + file)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String reflectConfig() throws IOException, URISyntaxException {
        var entries = new TreeSet<String>();
        entries.add("{\"name\":\"" + JGitText.class.getName() + "\",\"allDeclaredConstructors\":true,"
                + "\"allPublicFields\":true}");
        ClassLoader loader = JgitNativeMetadataTest.class.getClassLoader();
        for (String name : classNames(JGitText.class)) {
            if (isEnum(name, loader)) {
                entries.add("{\"name\":\"" + name + "\",\"allDeclaredFields\":true,\"allPublicMethods\":true}");
            }
        }
        return "[\n  " + String.join(",\n  ", entries) + "\n]\n";
    }

    private static boolean isEnum(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader).isEnum();
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static String resourceConfig() {
        return """
                {
                  "resources": {
                    "includes": [
                      {"pattern": "\\\\Qorg/eclipse/jgit/internal/JGitText.properties\\\\E"},
                      {"pattern": "de/planmarshall/provider/github/graphql/.*\\\\.graphql"}
                    ]
                  },
                  "bundles": [
                    {"name": "org.eclipse.jgit.internal.JGitText"}
                  ]
                }
                """;
    }

    /** All named (non-anonymous) classes of the jar holding {@code anchor}. */
    private static List<String> classNames(Class<?> anchor) throws IOException, URISyntaxException {
        Path jar = Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
        var names = new ArrayList<String>();
        try (var file = new JarFile(jar.toFile())) {
            for (JarEntry entry : file.stream().toList()) {
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("-info") && !name.startsWith("META-INF")
                        && !name.matches(".*\\$[0-9].*")) {
                    names.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
                }
            }
        }
        return names;
    }
}
