/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("LinuxConfinement: no-new-privileges and the Landlock ruleset")
class LinuxConfinementTest {

    @TempDir
    Path temp;

    private Path root;
    private Path base;
    private Path project;
    private Path cacheFile;
    private Path jobDir;

    @BeforeEach
    void tree() throws IOException {
        root = temp.toRealPath();
        Files.createDirectories(root.resolve("usr"));
        project = Files.createDirectories(root.resolve("home/u/project"));
        cacheFile = Files.writeString(root.resolve("home/u/.netrc-like"), "x");
        base = Files.createDirectories(root.resolve("home/u/.plan-marshall-mcp"));
        jobDir = Files.createDirectories(base.resolve("run/jobs/l1"));
    }

    private Command.Launch launch(Optional<Path> denied, List<Path> writes) {
        return new Command.Launch(writes, List.of(jobDir), denied, List.of("/usr/bin/true"));
    }

    @Test
    @DisplayName("ABI 3: write set with all rights, read set read-only, files restricted to file rights")
    void confined() throws Exception {
        var kernel = new FakeKernel(3);
        var confinement = new LinuxConfinement(kernel, root).apply(launch(Optional.of(base), List.of(project, cacheFile)));

        assertEquals(new Confinement("confined", 3, true), confinement);
        assertEquals(List.of("prctl", "abi", "create:7fff", "restrict:100"), kernel.calls);
        assertEquals(0x7fffL, kernel.accessOf(project));
        assertEquals(0x4007L, kernel.accessOf(cacheFile));
        assertEquals(0xdL, kernel.accessOf(root.resolve("usr")));
        assertEquals(0xdL, kernel.accessOf(jobDir));
        assertEquals(-1L, kernel.accessOf(base));
        assertEquals(-1L, kernel.accessOf(root.resolve("home")));
        assertTrue(kernel.closed.contains(100));
        assertEquals(kernel.rules.size() + 1, kernel.closed.size());
    }

    @Test
    @DisplayName("skips a rule whose path cannot be opened")
    void skipsUnopenable() throws Exception {
        var kernel = new FakeKernel(2);
        kernel.unopenable.add(project);

        new LinuxConfinement(kernel, root).apply(launch(Optional.of(base), List.of(project)));

        assertEquals(-1L, kernel.accessOf(project));
        assertEquals(0xdL, kernel.accessOf(root.resolve("usr")));
    }

    @ParameterizedTest(name = "ABI {0} -> {1}")
    @CsvSource({"1, partial(abi=1)", "0, unavailable"})
    @DisplayName("applies no ruleset below ABI 2")
    void noRuleset(int abi, String state) throws Exception {
        var kernel = new FakeKernel(abi);

        var confinement = new LinuxConfinement(kernel, root).apply(launch(Optional.of(base), List.of(project)));

        assertEquals(state, confinement.state());
        assertFalse(confinement.isConfined());
        assertEquals("null", confinement.abiJson());
        assertEquals(List.of("prctl", "abi"), kernel.calls);
    }

    @Test
    @DisplayName("refuses a launch without --deny-read before any native call")
    void requiresDeniedBase() {
        var kernel = new FakeKernel(3);
        var confinement = new LinuxConfinement(kernel, root);

        assertThrows(UsageException.class, () -> confinement.apply(launch(Optional.empty(), List.of())));
        assertTrue(kernel.calls.isEmpty());
    }

    @Test
    @DisplayName("aborts when no-new-privileges is refused")
    void noNewPrivsRefused() {
        var kernel = new FakeKernel(3);
        kernel.refuseNoNewPrivs = true;
        var confinement = new LinuxConfinement(kernel, root);

        assertThrows(NativeCallException.class, () -> confinement.apply(launch(Optional.of(base), List.of())));
    }

    @Test
    @DisplayName("aborts on a refused rule and still closes the ruleset")
    void ruleRefused() {
        var kernel = new FakeKernel(3);
        kernel.refuseRule = true;
        var confinement = new LinuxConfinement(kernel, root);

        var e = assertThrows(NativeCallException.class,
                () -> confinement.apply(launch(Optional.of(base), List.of(project))));
        assertEquals("landlock_add_rule", e.stage());
        assertEquals(22, e.errno());
        assertTrue(kernel.closed.contains(100));
        assertFalse(kernel.calls.contains("restrict:100"));
    }
}
