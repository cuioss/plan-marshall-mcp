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

/**
 * One credential backend. Exactly one is active per machine (PM-CRED-2); a secret never exists in
 * two backends. Implementations never log or put a secret into an exception message.
 */
public interface SecretStore {

    /** Name of the macOS Keychain backend. */
    String KEYCHAIN = "keychain";
    /** Name of the Linux Secret Service backend. */
    String SECRET_SERVICE = "secret-service";
    /** Name of the fallback file store. */
    String FILE = "file";

    /** @return {@link #KEYCHAIN}, {@link #SECRET_SERVICE} or {@link #FILE} */
    String name();

    /**
     * Stores or replaces a secret.
     *
     * @param account the account
     * @param secret  the secret
     * @throws SecretStoreException if the backend fails
     */
    void put(CredentialAccount account, String secret);

    /**
     * @param account the account
     * @return the secret, or empty if the entry does not exist
     * @throws SecretStoreException if the backend fails
     */
    Optional<String> get(CredentialAccount account);

    /**
     * @param account the account
     * @return whether an entry was deleted
     * @throws SecretStoreException if the backend fails
     */
    boolean delete(CredentialAccount account);

    /**
     * Resolves a credential the way every consumer does: the project entry before the global entry.
     *
     * @param project the enrolment identifier
     * @param key     the credential key
     * @return the secret, or empty if neither entry exists
     * @throws SecretStoreException if the backend fails
     */
    default Optional<String> resolve(String project, String key) {
        return get(CredentialAccount.project(project, key)).or(() -> get(CredentialAccount.global(key)));
    }
}
