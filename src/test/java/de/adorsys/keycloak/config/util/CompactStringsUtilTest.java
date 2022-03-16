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

package de.adorsys.keycloak.config.util;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompactStringsUtilTest {

    @Test
    void testCompressedJson() {
        // Given
        List<String> data = IntStream.range(0, 3)
                .mapToObj(i -> "read.some.events.datapoints.validated" + i)
                .collect(Collectors.toList());

        // When
        String compressed = CompactStringsUtil.compress(JsonUtil.toJson(data));

        // Then
        assertThat(JsonUtil.fromJson(CompactStringsUtil.decompress(compressed))).isEqualTo(data);
        assertThat(JsonUtil.fromJson(CompactStringsUtil.decompress(JsonUtil.toJson(data)))).isEqualTo(data);

    }

}
