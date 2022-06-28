/*-
 * ---license-start
 * keycloak-config-cli
 * ---
 * Copyright (C) 2017 - 2021 adorsys GmbH & Co. KG @ https://adorsys.com
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ---license-end
 */

package de.adorsys.keycloak.config.service;

import de.adorsys.keycloak.config.AbstractImportIT;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.util.CompactStringsUtil;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = {
        "import.remote-state.compact=true",
        "import.managed.role=full"
})
class ImportManagedWithCompactStateIT extends AbstractImportIT {
    private static final String REALM_NAME = "realmWithCompactSate";

    @Autowired
    public ImportConfigProperties importConfigProperties;

    ImportManagedWithCompactStateIT() {
        this.resourcePath = "import-files/managed-compact-state";
    }

    @Test
    @Order(0)
    void shouldCreateCompactState() throws IOException {
        doImport("0_create_realm.json");

        RealmRepresentation realm = keycloakProvider.getInstance().realm(REALM_NAME).toRepresentation();

        assertThat(realm.getRealm()).isEqualTo(REALM_NAME);
        assertThat(realm.isEnabled()).isTrue();


        String attributeKey = MessageFormat.format(
                ImportConfigProperties.REALM_STATE_ATTRIBUTE_PREFIX_KEY,
                importConfigProperties.getCache().getKey(),
                "roles-realm"
        );

        String compactStateAttribute = getAttribute(realm, attributeKey);
        assertThat(compactStateAttribute)
                .doesNotContain("role")
                .isBase64();
        String decompressedAttribute = CompactStringsUtil.decompress(compactStateAttribute);
        assertThat(decompressedAttribute).contains("role1", "role2", "role3", "role4");

    }

    @Test
    @Order(1)
    void shouldUpdate_And_UnpackState() throws IOException {
        ReflectionTestUtils.setField(importConfigProperties.getRemoteState(), "compact", false);

        doImport("1_update_realm.json");

        RealmRepresentation realm = keycloakProvider.getInstance().realm(REALM_NAME).toRepresentation();
        assertThat(realm.getRealm()).isEqualTo(REALM_NAME);
        assertThat(realm.isEnabled()).isTrue();

        String attributeKey = MessageFormat.format(
                ImportConfigProperties.REALM_STATE_ATTRIBUTE_PREFIX_KEY,
                importConfigProperties.getCache().getKey(),
                "roles-realm"
        );

        assertThat(getAttribute(realm, attributeKey)).contains("role1", "role2", "role3", "role4", "role5");
    }

    @Test
    @Order(2)
    void shouldUpdate_And_CompactStateAgain() throws IOException {
        ReflectionTestUtils.setField(importConfigProperties.getRemoteState(), "compact", true);

        doImport("2_update_realm.json");

        RealmRepresentation realm = keycloakProvider.getInstance().realm(REALM_NAME).toRepresentation();
        assertThat(realm.getRealm()).isEqualTo(REALM_NAME);
        assertThat(realm.isEnabled()).isTrue();

        String attributeKey = MessageFormat.format(
                ImportConfigProperties.REALM_STATE_ATTRIBUTE_PREFIX_KEY,
                importConfigProperties.getCache().getKey(),
                "roles-realm"
        );

        String compactStateAttribute = getAttribute(realm, attributeKey);
        assertThat(compactStateAttribute)
                .doesNotContain("role")
                .isBase64();
        String decompressedAttribute = CompactStringsUtil.decompress(compactStateAttribute);
        assertThat(decompressedAttribute)
                .contains("role1", "role2", "role3", "role4", "role6")
                .doesNotContain("role5");
    }

    @NotNull
    static String getAttribute(RealmRepresentation realm, String attributeKey) {
        return realm.getAttributes().entrySet()
                .stream()
                .filter(e -> e.getKey().startsWith(attributeKey))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining());
    }

}
