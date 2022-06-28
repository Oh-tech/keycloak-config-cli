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

import de.adorsys.keycloak.config.exception.ImportProcessingException;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class CompactStringsUtil {

    private static final int MIN_COMPRESSED_ARRAY_LENGTH = 2;

    CompactStringsUtil() {
        throw new IllegalStateException("Utility class");
    }

    public static String compress(String value) {
        byte[] jsonBytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream(jsonBytes.length);
        try (GZIPOutputStream os = new GZIPOutputStream(outputStream)) {
            IOUtils.write(jsonBytes, os);
        } catch (IOException e) {
            throw new ImportProcessingException(e);
        }
        return Base64.encodeBase64String(outputStream.toByteArray());
    }

    private static boolean isNotBase64(String value) {
        return !StringUtils.hasLength(value) || !Base64.isBase64(value);
    }

    public static boolean isCompressed(String value) {
        if (isNotBase64(value)) {
            return false;
        }

        byte[] compressedContent = Base64.decodeBase64(value);
        return compressedContent.length > MIN_COMPRESSED_ARRAY_LENGTH
                && compressedContent[0] == (byte) GZIPInputStream.GZIP_MAGIC
                && compressedContent[1] == (byte) (GZIPInputStream.GZIP_MAGIC >> 8);
    }

    public static String decompress(String value) {
        if (isNotBase64(value)) {
            // The value is not Base64
            return value;
        }

        try {
            byte[] compressedContent = Base64.decodeBase64(value);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPInputStream is = new GZIPInputStream(new ByteArrayInputStream(compressedContent))) {
                IOUtils.copy(is, bos);
            } catch (IOException e) {
                throw new ImportProcessingException(e);
            }
            return bos.toString(StandardCharsets.UTF_8.toString());
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            throw new ImportProcessingException(e);
        }
    }
}
