/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.cuioss.pm.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;

/**
 * A recording {@link Kernel} without native calls.
 */
class FakeKernel implements Kernel {

    /** A rule added to the ruleset. */
    record Rule(Path path, long access) {
    }

    final List<String> calls = new ArrayList<>();
    final List<Rule> rules = new ArrayList<>();
    final Set<Path> unopenable = new HashSet<>();
    final Set<Integer> closed = new HashSet<>();
    int abi;
    boolean refuseNoNewPrivs;
    boolean refuseProcessGroup;
    boolean refuseRule;
    List<String> executed;

    private final List<Path> openPaths = new ArrayList<>();

    FakeKernel(int abi) {
        this.abi = abi;
    }

    @Override
    public void setNoNewPrivs() throws NativeCallException {
        calls.add("prctl");
        if (refuseNoNewPrivs) {
            throw new NativeCallException("prctl", 22, "refused");
        }
    }

    @Override
    public int landlockAbi() {
        calls.add("abi");
        return abi;
    }

    @Override
    public int createRuleset(long handledAccessFs) {
        calls.add("create:" + Long.toHexString(handledAccessFs));
        return 100;
    }

    @Override
    public OptionalInt openPath(Path path) {
        if (unopenable.contains(path)) {
            return OptionalInt.empty();
        }
        openPaths.add(path);
        return OptionalInt.of(200 + openPaths.size() - 1);
    }

    @Override
    public void addPathBeneath(int rulesetFd, int parentFd, long allowedAccess) throws NativeCallException {
        if (refuseRule) {
            throw new NativeCallException("landlock_add_rule", 22, "refused");
        }
        rules.add(new Rule(openPaths.get(parentFd - 200), allowedAccess));
    }

    @Override
    public void restrictSelf(int rulesetFd) {
        calls.add("restrict:" + rulesetFd);
    }

    @Override
    public void close(int fd) {
        closed.add(fd);
    }

    @Override
    public void setProcessGroup() throws NativeCallException {
        calls.add("setpgid");
        if (refuseProcessGroup) {
            throw new NativeCallException("setpgid", 1, "refused");
        }
    }

    @Override
    public int execve(List<String> argv) {
        calls.add("execve");
        executed = argv;
        return 2;
    }

    long accessOf(Path path) {
        return rules.stream().filter(r -> r.path().equals(path)).mapToLong(Rule::access).findFirst().orElse(-1);
    }
}
