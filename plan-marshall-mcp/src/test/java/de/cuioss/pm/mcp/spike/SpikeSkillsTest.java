/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: FSL-1.1-ALv2
 *
 * Licensed under the Functional Source License, Version 1.1, ALv2 Future License
 * (the "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License in the LICENSE.md file at the root of this
 * repository or at https://github.com/cuioss/plan-marshall-mcp/blob/main/LICENSE.md
 */
package de.cuioss.pm.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import de.cuioss.pm.mcp.spike.SpikeQueue.Caller;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SpikeSkills")
class SpikeSkillsTest {

    private static final String TRIAGE = "skill://pm/role/triage/SKILL.md";
    private static final String EXAMPLES = "skill://pm/role/triage/examples.md";
    private static final String CORE = "skill://pm/core/SKILL.md";
    private static final String TRIAGE_TEXT = "---\nname: triage\ndescription: Triage rules\n---\nA bump is a fix.\n";
    private static final Caller A1 = new Caller("a", 1, "worker");
    private static final Caller A2 = new Caller("a", 2, "worker");

    @TempDir
    Path directory;

    private SpikeSkills skills;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(directory.resolve("role/triage"));
        Files.createDirectories(directory.resolve("core"));
        Files.writeString(directory.resolve("role/triage/SKILL.md"), TRIAGE_TEXT);
        Files.writeString(directory.resolve("role/triage/examples.md"), "example");
        Files.writeString(directory.resolve("core/SKILL.md"), "no frontmatter");
        skills = SpikeSkills.load(directory);
    }

    @Nested
    @DisplayName("catalogue")
    class Catalogue {

        @Test
        @DisplayName("loads every file with its URI, digest and size")
        void shouldLoad() {
            var file = skills.file(EXAMPLES).orElseThrow();

            assertEquals(3, skills.files().size());
            assertEquals("example", file.content());
            assertEquals(7, file.size());
            assertEquals("sha256:50d858e0985ecc7f60418aaf0cc5ab587f42c2570a884095a9e8ccacd0f6545c", file.digest());
            assertTrue(skills.file("skill://pm/none").isEmpty());
        }

        @Test
        @DisplayName("reports a missing catalogue directory")
        void shouldFailOnMissingDirectory() {
            var missing = directory.resolve("missing");

            assertThrows(IOException.class, () -> SpikeSkills.load(missing));
        }

        @Test
        @DisplayName("lists one manifest per skill with its frontmatter and files")
        void shouldListManifests() {
            var manifests = skills.manifests();

            assertEquals(2, manifests.size());
            var core = manifests.getJsonObject(0);
            assertEquals(CORE, core.getString("uri"));
            assertNull(core.getString("name"));
            assertEquals(1, core.getJsonArray("files").size());
            var triage = manifests.getJsonObject(1);
            assertEquals("triage", triage.getString("name"));
            assertEquals("Triage rules", triage.getString("description"));
            assertEquals(2, triage.getJsonArray("files").size());
            assertEquals(skills.file(TRIAGE).orElseThrow().digest(),
                    triage.getJsonArray("files").getJsonObject(0).getString("digest"));
            assertEquals(7, triage.getJsonArray("files").getJsonObject(1).getInteger("size"));
        }

        @Test
        @DisplayName("reads no frontmatter field from an unterminated or foreign header")
        void shouldTolerateOddFrontmatter() {
            skills.put("skill://pm/x/SKILL.md", "---\ntitle: x\n---\nname: late\n");
            skills.put("skill://pm/y/SKILL.md", "");

            var manifests = skills.manifests();

            assertNull(manifests.getJsonObject(2).getString("name"));
            assertNull(manifests.getJsonObject(3).getString("name"));
        }
    }

    @Nested
    @DisplayName("delivery")
    class Delivery {

        @Test
        @DisplayName("delivers a skill in-band once per generation, then names it only")
        void shouldDeliverOnce() {
            var first = skills.deliver(A1, List.of(TRIAGE), false);
            var second = skills.deliver(A1, List.of(TRIAGE), false);

            assertEquals(TRIAGE_TEXT, first.skills().getJsonObject(0).getString("content"));
            assertEquals("skill_delivered", first.events().getFirst().getString("event"));
            assertEquals("inband", first.events().getFirst().getString("form"));
            assertFalse(second.skills().getJsonObject(0).containsKey("content"));
            assertEquals(first.skills().getJsonObject(0).getString("digest"),
                    second.skills().getJsonObject(0).getString("digest"));
            assertTrue(second.events().isEmpty());
        }

        @Test
        @DisplayName("delivers again to a new generation, after a compaction and after a digest change")
        void shouldDeliverAgain() {
            skills.deliver(A1, List.of(TRIAGE), false);

            var successor = skills.deliver(A2, List.of(TRIAGE), false);
            skills.compacted("a");
            var compacted = skills.deliver(A1, List.of(TRIAGE), false);
            var before = skills.file(TRIAGE).orElseThrow().digest();
            var updated = skills.put(TRIAGE, "changed");
            var changed = skills.deliver(A1, List.of(TRIAGE), false);

            assertEquals(1, successor.events().size());
            assertEquals(1, compacted.events().size());
            assertNotEquals(before, updated.digest());
            assertEquals("changed", changed.skills().getJsonObject(0).getString("content"));
        }

        @Test
        @DisplayName("names a skill by URI and digest only for a connection that activates skills itself")
        void shouldDeliverByUri() {
            var delivery = skills.deliver(A1, List.of(TRIAGE), true);
            var again = skills.deliver(A1, List.of(TRIAGE), true);

            assertFalse(delivery.skills().getJsonObject(0).containsKey("content"));
            assertEquals("uri", delivery.events().getFirst().getString("form"));
            assertTrue(again.events().isEmpty());
        }

        @Test
        @DisplayName("marks a skill the catalogue does not hold")
        void shouldMarkMissing() {
            var delivery = skills.deliver(A1, List.of("skill://pm/none/SKILL.md"), false);

            assertTrue(delivery.skills().getJsonObject(0).getBoolean("missing"));
            assertEquals("skill_missing", delivery.events().getFirst().getString("event"));
        }

        @Test
        @DisplayName("counts a skill the worker read itself as delivered")
        void shouldRecordRead() {
            skills.read(A1, skills.file(TRIAGE).orElseThrow());

            var delivery = skills.deliver(A1, List.of(TRIAGE), false);

            assertTrue(delivery.events().isEmpty());
            assertFalse(delivery.skills().getJsonObject(0).containsKey("content"));
        }
    }
}
