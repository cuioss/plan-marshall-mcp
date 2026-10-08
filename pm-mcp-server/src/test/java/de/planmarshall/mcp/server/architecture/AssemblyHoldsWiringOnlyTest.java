/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;


import de.planmarshall.mcp.server.architecture.fixture.FrameworkBoundFixture;
import de.planmarshall.mcp.server.runtime.PmMcpd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Architecture test of the daemon assembly (PM-IMPL-1): {@code pm-mcp-server} holds wiring only.
 * <p>
 * Hazard: a framework-free class that stays in the assembly grows the repository that is closed first back
 * into the one that holds the logic, where it has no plain JUnit test and no public review. The test reads the
 * compiled classes of the module and fails for every top-level class (with its nested classes) that references
 * no Quarkus, CDI ({@code jakarta.*}), Vert.x, Netty or MCP type and is not a record exchanged by a class that
 * does. Such a class belongs in {@code pm-runtime} or another library module.
 */
@DisplayName("The assembly holds wiring only")
class AssemblyHoldsWiringOnlyTest {

    private static final String BASE = "de/planmarshall/mcp/server/";

    /** The framework namespaces, as internal names. */
    private static final List<String> FRAMEWORKS = List.of("io/quarkus/", "io/quarkiverse/mcp/", "jakarta/",
            "io/vertx/", "io/netty/");

    private static final Pattern DESCRIPTOR_TYPE = Pattern.compile("L([\\w/$]+);");

    @Test
    @DisplayName("every production class references a framework type or is a record such classes exchange")
    void productionClassesAreWiring() throws Exception {
        var violations = violations(classesOf(PmMcpd.class));

        assertEquals(Set.of(), violations, "framework-free classes belong in pm-runtime");
    }

    @Test
    @DisplayName("control: a framework-free class and an unreferenced record trip the rule")
    void fixturesTripTheRule() throws Exception {
        var violations = violations(classesOf(FrameworkBoundFixture.class));

        assertEquals(Set.of(BASE + "architecture/fixture/FrameworkFreeFixture",
                BASE + "architecture/fixture/OrphanRecordFixture"), fixturesIn(violations));
        assertFalse(violations.contains(BASE + "architecture/fixture/FrameworkBoundFixture"));
        assertFalse(violations.contains(BASE + "architecture/fixture/ExchangedRecordFixture"));
    }

    private static Set<String> fixturesIn(Set<String> violations) {
        var fixtures = new TreeSet<String>();
        for (var name : violations) {
            if (name.startsWith(BASE + "architecture/fixture/")) {
                fixtures.add(name);
            }
        }
        return fixtures;
    }

    private static Path classesOf(Class<?> anchor) throws URISyntaxException {
        return Path.of(anchor.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    /**
     * @param root a directory of compiled classes
     * @return the internal names of the top-level classes below the assembly package that break the rule
     */
    static Set<String> violations(Path root) throws IOException {
        var framework = new HashSet<String>();
        var records = new HashSet<String>();
        var references = new HashMap<String, Set<String>>();
        try (var files = Files.walk(root.resolve(BASE))) {
            files.filter(file -> file.toString().endsWith(".class"))
                    .filter(file -> !"package-info.class".equals(file.getFileName().toString()))
                    .forEach(file -> read(file, framework, records, references));
        }
        var exchanged = new HashSet<String>();
        for (var owner : framework) {
            exchanged.addAll(references.get(owner));
        }
        var violations = new TreeSet<String>();
        for (var owner : references.keySet()) {
            if (!framework.contains(owner) && !(records.contains(owner) && exchanged.contains(owner))) {
                violations.add(owner);
            }
        }
        return violations;
    }

    private static void read(Path file, Set<String> framework, Set<String> records,
            Map<String, Set<String>> references) {
        try {
            var model = ClassFile.of().parse(Files.readAllBytes(file));
            var name = model.thisClass().asInternalName();
            var owner = topLevel(name);
            var referenced = references.computeIfAbsent(owner, _ -> new HashSet<>());
            if (name.equals(owner) && model.superclass().filter(type -> type.name().equalsString("java/lang/Record"))
                    .isPresent()) {
                records.add(owner);
            }
            for (var entry : model.constantPool()) {
                switch (entry) {
                    case ClassEntry type -> note(type.asInternalName(), owner, framework, referenced);
                    case Utf8Entry text -> {
                        var matcher = DESCRIPTOR_TYPE.matcher(text.stringValue());
                        while (matcher.find()) {
                            note(matcher.group(1), owner, framework, referenced);
                        }
                    }
                    default -> {
                        // no type reference
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void note(String type, String owner, Set<String> framework, Set<String> referenced) {
        var name = type.replaceFirst("^\\[+L?", "");
        if (FRAMEWORKS.stream().anyMatch(name::startsWith)) {
            framework.add(owner);
        } else if (name.startsWith(BASE) && !topLevel(name).equals(owner)) {
            referenced.add(topLevel(name));
        }
    }

    private static String topLevel(String name) {
        int nested = name.indexOf('$');
        return nested < 0 ? name : name.substring(0, nested);
    }
}
