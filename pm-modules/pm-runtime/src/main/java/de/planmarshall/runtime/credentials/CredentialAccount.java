/*
 * Copyright © 2026-present Oliver Wolff
 *
 * SPDX-License-Identifier: LicenseRef-Proprietary
 *
 * All rights reserved. This file is part of plan-marshall-mcp, which is proprietary software.
 * No right to use, copy, modify or distribute this file is granted; see the LICENSE.md file at
 * the root of this repository.
 */
package de.planmarshall.runtime.credentials;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The account of a credential entry (PM-CRED-2): {@code <credential_key>} for a global entry,
 * {@code <project>/<credential_key>} for a project entry, where {@code <project>} is the project's
 * enrolment identifier. Both parts are restricted to {@code [a-zA-Z0-9._-]} and are never
 * {@code .} or {@code ..} (PM-CRED-3), so an account is also a safe relative file path.
 *
 * @param project the enrolment identifier, empty for a global entry
 * @param key     the credential key
 */
public record CredentialAccount(Optional<String> project, String key) {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9._-]{1,128}");

    /**
     * Validates both parts.
     *
     * @param project the enrolment identifier
     * @param key     the credential key
     * @throws InvalidCredentialNameException for a part outside the allowed characters
     */
    public CredentialAccount {
        check(key);
        project.ifPresent(CredentialAccount::check);
    }

    /**
     * @param key the credential key
     * @return the global entry of the key
     */
    public static CredentialAccount global(String key) {
        return new CredentialAccount(Optional.empty(), key);
    }

    /**
     * @param project the enrolment identifier
     * @param key     the credential key
     * @return the project entry of the key
     */
    public static CredentialAccount project(String project, String key) {
        return new CredentialAccount(Optional.of(project), key);
    }

    /** @return the keyring account: {@code <key>} or {@code <project>/<key>} */
    public String account() {
        return project.map(p -> p + "/" + key).orElse(key);
    }

    private static void check(String name) {
        if (name == null || !NAME.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
            throw new InvalidCredentialNameException(name);
        }
    }
}
