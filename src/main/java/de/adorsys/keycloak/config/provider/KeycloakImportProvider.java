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

package de.adorsys.keycloak.config.provider;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.adorsys.keycloak.config.exception.InvalidImportException;
import de.adorsys.keycloak.config.model.KeycloakImport;
import de.adorsys.keycloak.config.model.RealmImport;
import de.adorsys.keycloak.config.properties.ImportConfigProperties;
import de.adorsys.keycloak.config.util.ChecksumUtil;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookup;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.PathMatcher;
import org.springframework.util.ResourceUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class KeycloakImportProvider {
    private final PathMatchingResourcePatternResolver patternResolver;
    private final Comparator<File> fileComparator;
    private final Collection<ResourceExtractor> resourceExtractors;
    private final ImportConfigProperties importConfigProperties;

    private StringSubstitutor interpolator = null;

    private static final Logger logger = LoggerFactory.getLogger(KeycloakImportProvider.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Autowired
    public KeycloakImportProvider(
            Environment environment,
            PathMatchingResourcePatternResolver patternResolver,
            Comparator<File> fileComparator,
            Collection<ResourceExtractor> resourceExtractors,
            ImportConfigProperties importConfigProperties
    ) {
        this.patternResolver = patternResolver;
        this.fileComparator = fileComparator;
        this.resourceExtractors = resourceExtractors;
        this.importConfigProperties = importConfigProperties;

        if (importConfigProperties.isVarSubstitution()) {
            String prefix = importConfigProperties.getVarSubstitutionPrefix();
            String suffix = importConfigProperties.getVarSubstitutionSuffix();

            StringLookup variableResolver = StringLookupFactory.INSTANCE.interpolatorStringLookup(
                    StringLookupFactory.INSTANCE.functionStringLookup(environment::getProperty)
            );

            this.interpolator = StringSubstitutor.createInterpolator()
                    .setVariableResolver(variableResolver)
                    .setVariablePrefix(prefix)
                    .setVariableSuffix(suffix)
                    .setEnableSubstitutionInVariables(importConfigProperties.isVarSubstitutionInVariables())
                    .setEnableUndefinedVariableException(
                            importConfigProperties.isVarSubstitutionUndefinedThrowsExceptions());
        }
    }

    public KeycloakImport get() {
        KeycloakImport keycloakImport;

        Collection<String> path = importConfigProperties.getPath();
        keycloakImport = readFromPaths(path.toArray(new String[0]));

        return keycloakImport;
    }

    public KeycloakImport readFromPaths(String... paths) {
        Set<File> files = new LinkedHashSet<>();
        for (String path : paths) {
            // backward compatibility to correct a possible missing prefix "file:" in path

            if (!ResourceUtils.isUrl(path)) {
                path = "file:" + path;
            }

            Resource[] resources;

            try {
                resources = this.patternResolver.getResources(path);
            } catch (IOException e) {
                throw new InvalidImportException("import.path does not exists: " + path, e);
            }

            boolean found = false;
            for (Resource resource : resources) {
                Optional<ResourceExtractor> maybeMatchingExtractor = resourceExtractors.stream()
                        .filter(r -> {
                            try {
                                return r.canHandleResource(resource);
                            } catch (IOException e) {
                                return false;
                            }
                        }).findFirst();

                if (maybeMatchingExtractor.isPresent()) {
                    try {
                        Collection<File> extractedFiles = maybeMatchingExtractor.get()
                                .extract(resource)
                                .stream()
                                .map(de.adorsys.keycloak.config.provider.FileUtils::relativize)
                                .collect(Collectors.toList());

                        files.addAll(extractedFiles);
                    } catch (IOException e) {
                        throw new InvalidImportException("import.path does not exists: " + path, e);
                    }

                    found = true;
                }
            }

            if (!found) {
                throw new InvalidImportException("No resource extractor found to handle config property import.path=" + path
                        + "! Check your settings.");
            }
        }

        Stream<File> filesStream = files.stream();

        Collection<String> excludes = this.importConfigProperties.getExclude();
        if (excludes != null && !excludes.isEmpty()) {
            PathMatcher pathMatcher = this.patternResolver.getPathMatcher();

            for (String exclude : excludes) {
                filesStream = filesStream.filter(f -> {
                    boolean match = pathMatcher.match(exclude, f.getPath());
                    if (match) {
                        logger.debug("Excluding resource file '{}' (match {})", f.getPath(), exclude);
                        return false;
                    }
                    return true;
                });
            }
        }

        List<File> sortedFiles = filesStream
                .map(File::getAbsoluteFile)
                .sorted(this.fileComparator)
                .collect(Collectors.toList());

        logger.info("{} configuration files found.", sortedFiles.size());

        return readRealmImportsFromResource(sortedFiles);
    }

    private KeycloakImport readRealmImportsFromResource(List<File> importResources) {
        Map<String, List<RealmImport>> realmImports = importResources.stream()
                // https://stackoverflow.com/a/52130074/8087167
                .collect(Collectors.toMap(
                        File::getAbsolutePath,
                        this::readRealmImport,
                        (u, v) -> {
                            throw new IllegalStateException(String.format("Duplicate key %s", u));
                        },
                        LinkedHashMap::new
                ));
        return new KeycloakImport(realmImports);
    }

    public KeycloakImport readRealmImportFromFile(File importFile) {
        Map<String, List<RealmImport>> realmImports = new HashMap<>();

        List<RealmImport> realmImport = readRealmImport(importFile);
        realmImports.put(importFile.getAbsolutePath(), realmImport);

        return new KeycloakImport(realmImports);
    }

    private List<RealmImport> readRealmImport(File importFile) {
        String importConfig;

        logger.info("Loading file '{}'", importFile);

        try {
            importConfig = FileUtils.readFileToString(importFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }

        if (importConfigProperties.isVarSubstitution()) {
            importConfig = interpolator.replace(importConfig);
        }

        String checksum = ChecksumUtil.checksum(importConfig.getBytes(StandardCharsets.UTF_8));

        ImportConfigProperties.ImportFileType fileType = importConfigProperties.getFileType();

        List<RealmImport> realmImports;

        switch (fileType) {
            case YAML:
                realmImports = readYaml(importConfig);
                break;
            case JSON:
                realmImports = readJson(importConfig);
                break;
            case AUTO:
                String fileExt = FilenameUtils.getExtension(importFile.getName());
                switch (fileExt) {
                    case "yaml":
                    case "yml":
                        realmImports = readYaml(importConfig);
                        break;
                    case "json":
                        realmImports = readJson(importConfig);
                        break;
                    default:
                        throw new InvalidImportException("Unknown file extension: " + fileExt);
                }
                break;
            default:
                throw new InvalidImportException("Unknown import file type: " + fileType);
        }

        realmImports.forEach(realmImport -> realmImport.setChecksum(checksum));

        return realmImports;
    }

    private List<RealmImport> readJson(String data) {
        try {
            RealmImport realmImport = OBJECT_MAPPER.readValue(data, RealmImport.class);

            return Collections.singletonList(realmImport);
        } catch (IOException e) {
            throw new InvalidImportException(e);
        }
    }

    private List<RealmImport> readYaml(String data) {
        List<RealmImport> realmImports = new ArrayList<>();

        Yaml yaml = new Yaml();
        Iterable<Object> yamlDocuments = yaml.loadAll(data);

        try {
            for (Object yamlDocument : yamlDocuments) {
                realmImports.add(OBJECT_MAPPER.convertValue(yamlDocument, RealmImport.class));
            }
        } catch (IllegalArgumentException e) {
            throw new InvalidImportException(e.getMessage());
        }

        return realmImports;
    }
}
