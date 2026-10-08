/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.mcp.server.credentials.dbus;

import java.util.ArrayList;
import java.util.List;


import lombok.experimental.UtilityClass;

/**
 * Splits D-Bus signatures into single complete types and gives their alignment.
 */
@UtilityClass
class Signatures {

    /**
     * @param signature a signature of zero or more complete types
     * @return the single complete types in order
     * @throws DbusException for an unbalanced or truncated signature
     */
    static List<String> split(String signature) {
        var types = new ArrayList<String>();
        int i = 0;
        while (i < signature.length()) {
            int end = end(signature, i);
            types.add(signature.substring(i, end));
            i = end;
        }
        return types;
    }

    /**
     * @param signature the signature
     * @param start     the index a complete type starts at
     * @return the index after that complete type
     */
    static int end(String signature, int start) {
        if (start >= signature.length()) {
            throw new DbusException(DbusException.LOCAL, "truncated signature: " + signature);
        }
        char c = signature.charAt(start);
        if (c == 'a') {
            return end(signature, start + 1);
        }
        if (c == '(' || c == '{') {
            char close = c == '(' ? ')' : '}';
            int i = start + 1;
            while (i < signature.length() && signature.charAt(i) != close) {
                i = end(signature, i);
            }
            if (i >= signature.length()) {
                throw new DbusException(DbusException.LOCAL, "unbalanced signature: " + signature);
            }
            return i + 1;
        }
        return start + 1;
    }

    /**
     * @param type a single complete type
     * @return its alignment in bytes
     */
    static int alignment(String type) {
        return switch (type.charAt(0)) {
            case 'y', 'g', 'v' -> 1;
            case 'n', 'q' -> 2;
            case 'x', 't', 'd', '(', '{' -> 8;
            default -> 4;
        };
    }
}
