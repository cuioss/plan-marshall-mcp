/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.provider.gitlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;


import de.planmarshall.provider.ci.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("GlabCommands")
class GlabCommandsTest {

    private static final List<String> BASE = List.of("glab", "api", "--hostname", "gitlab.example.com");
    private static final List<String> BODY_FLAGS = List.of("--header", "Content-Type: application/json", "--input", "-");
    private final GlabCommands glab = new GlabCommands("gitlab.example.com", "group/project");

    private static List<String> argv(List<String> head, List<String> tail) {
        var all = new ArrayList<>(BASE);
        all.addAll(head);
        all.addAll(tail);
        return all;
    }

    @Test
    @DisplayName("merges and adds to the merge train with the sha on standard input")
    void mergePath() {
        var merge = glab.merge(12, "abc");
        var train = glab.mergeTrainAdd(12, "abc");

        assertEquals(argv(List.of("--method", "PUT"), concat(BODY_FLAGS,
                "projects/group%2Fproject/merge_requests/12/merge")), merge.argv());
        assertEquals(Optional.of("{\"sha\":\"abc\"}"), merge.stdin());
        assertEquals(argv(List.of("--method", "POST"), concat(BODY_FLAGS,
                "projects/group%2Fproject/merge_trains/merge_requests/12")), train.argv());
    }

    @Test
    @DisplayName("streams multi-line bodies on standard input, never in the argument vector")
    void bodiesOnStdin() {
        String reply = "--force\n# heading\n\"quoted\" $(rm -rf /)";

        var invocation = glab.reply(12, "6a9c1750b37d513a43987b574953fceb50b03ce7", reply);
        var create = glab.createMergeRequest("pm/plan-1", "main", "feat: x", "Body\n\nwith lines");

        assertTrue(invocation.argv().stream().noneMatch(a -> a.contains("rm -rf") || a.contains("heading")));
        assertEquals(Optional.of(reply), Json.string(Json.parse(invocation.stdin().orElseThrow()), "body"));
        assertEquals("projects/group%2Fproject/merge_requests/12/discussions/6a9c1750b37d513a43987b574953fceb50b03ce7/notes",
                invocation.argv().getLast());
        assertTrue(create.argv().stream().noneMatch(a -> a.contains("with lines")));
        assertEquals(Optional.of("Body\n\nwith lines"),
                Json.string(Json.parse(create.stdin().orElseThrow()), "description"));
        assertEquals(Optional.of("pm/plan-1"), Json.string(Json.parse(create.stdin().orElseThrow()), "source_branch"));
    }

    @Test
    @DisplayName("builds the read invocations without standard input")
    void reads() {
        assertEquals(argv(List.of("--paginate"), List.of("projects/group%2Fproject/merge_requests/12/discussions?per_page=100")),
                glab.discussions(12).argv());
        assertEquals(argv(List.of("--method", "PUT"),
                List.of("projects/group%2Fproject/merge_requests/12/discussions/d1?resolved=true")),
                glab.resolve(12, "d1").argv());
        assertEquals(argv(List.of(), List.of("projects/group%2Fproject/jobs/5/trace")), glab.jobTrace(5).argv());
        assertEquals(argv(List.of(), List.of("personal_access_tokens/self")), glab.tokenIdentity().argv());
        assertFalse(glab.tokenIdentity().stdin().isPresent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"-d1", "d1 --paginate", "../x", ""})
    @DisplayName("refuses identifiers that could be read as flags or paths")
    void refusesDiscussionIds(String id) {
        assertThrows(IllegalArgumentException.class, () -> glab.resolve(12, id));
    }

    @Test
    @DisplayName("refuses invalid hosts, branches and ids")
    void refusesOtherValues() {
        assertThrows(IllegalArgumentException.class, () -> new GlabCommands("--hostname", "p"));
        assertThrows(IllegalArgumentException.class, () -> glab.createMergeRequest("-x", "main", "t", "d"));
        assertThrows(IllegalArgumentException.class, () -> glab.jobTrace(0));
        assertThrows(IllegalArgumentException.class, () -> glab.merge(-1, "abc"));
    }

    private static List<String> concat(List<String> list, String last) {
        var all = new ArrayList<>(list);
        all.add(last);
        return all;
    }
}
