/*
 * Copyright 2013 the original author or authors.
 *
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
 */

package org.gradle.api.publish.maven.internal.publisher;

import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.publish.internal.PublicationFieldValidator;
import org.gradle.api.publish.maven.InvalidMavenPublicationException;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.api.publish.maven.internal.artifact.MavenArtifactKey;
import org.gradle.internal.UncheckedException;
import org.gradle.mvn3.org.apache.maven.model.Model;
import org.gradle.mvn3.org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.gradle.mvn3.org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;

public class ValidatingMavenPublisher implements MavenPublisher {
    private static final java.lang.String ID_REGEX = "[A-Za-z0-9_\\-.]+";
    private final MavenPublisher delegate;

    public ValidatingMavenPublisher(MavenPublisher delegate) {
        this.delegate = delegate;
    }

    public void publish(MavenNormalizedPublication publication, MavenArtifactRepository artifactRepository) {
        validateIdentity(publication);
        validateArtifacts(publication);

        delegate.publish(publication, artifactRepository);
    }

    private void validateIdentity(MavenNormalizedPublication publication) {
        MavenProjectIdentity projectIdentity = publication.getProjectIdentity();
        Model model = parsePomFileIntoMavenModel(publication);
        field(publication, "groupId", projectIdentity.getGroupId())
                .validMavenIdentifier()
                .matches(model.getGroupId());
        field(publication, "artifactId", projectIdentity.getArtifactId())
                .validMavenIdentifier()
                .matches(model.getArtifactId());
        field(publication, "version", projectIdentity.getVersion())
                .notEmpty()
                .validInFileName()
                .matches(model.getVersion());
    }

    private Model parsePomFileIntoMavenModel(MavenNormalizedPublication publication) {
        try {
            FileReader reader = new FileReader(publication.getPomFile());
            Model model = new MavenXpp3Reader().read(reader);
            model.setPomFile(publication.getPomFile());
            return model;
        } catch (XmlPullParserException parseException) {
            throw new InvalidMavenPublicationException(publication.getName(),
                    "POM file is invalid. Check any modifications you have made to the POM file.",
                    parseException);
        } catch (Exception ex) {
            throw UncheckedException.throwAsUncheckedException(ex);
        }
    }

    private void validateArtifacts(MavenNormalizedPublication publication) {
        Set<MavenArtifactKey> keys = new HashSet<MavenArtifactKey>();
        for (MavenArtifact artifact : publication.getArtifacts()) {
            field(publication, "artifact extension", artifact.getExtension())
                    .notNull()
                    .validInFileName();
            field(publication, "artifact classifier", artifact.getClassifier())
                    .optionalNotEmpty()
                    .validInFileName();

            checkCanPublish(publication.getName(), artifact);

            MavenArtifactKey key = new MavenArtifactKey(artifact);
            if (keys.contains(key)) {
                throw new InvalidMavenPublicationException(publication.getName(),
                        String.format(
                                "multiple artifacts with the identical extension '%s' and classifier '%s'.",
                                artifact.getExtension(), artifact.getClassifier()
                        ));
            }
            keys.add(key);
        }
    }

    private void checkCanPublish(String publicationName, MavenArtifact artifact) {
        File artifactFile = artifact.getFile();
        if (artifactFile == null || !artifactFile.exists()) {
            throw new InvalidMavenPublicationException(publicationName, String.format("artifact file does not exist: '%s'", artifactFile));
        }
        if (artifactFile.isDirectory()) {
            throw new InvalidMavenPublicationException(publicationName, String.format("artifact file is a directory: '%s'", artifactFile));
        }
    }

    private MavenFieldValidator field(MavenNormalizedPublication publication, String name, String value) {
        return new MavenFieldValidator(publication.getName(), name, value);
    }

    private static class MavenFieldValidator extends PublicationFieldValidator<MavenFieldValidator> {

        private MavenFieldValidator(String publicationName, String name, String value) {
            super(MavenFieldValidator.class, publicationName, name, value);
        }

        public MavenFieldValidator validMavenIdentifier() {
            notEmpty();
            if (!value.matches(ID_REGEX)) {
                throw failure(String.format("%s is not a valid Maven identifier (%s).", name, ID_REGEX));
            }
            return this;
        }

        public MavenFieldValidator matches(String expectedValue) {
            if (!value.equals(expectedValue)) {
                throw failure(String.format("supplied %s does not match POM file (cannot edit %1$s directly in the POM file).", name));
            }
            return this;
        }

        @Override
        protected InvalidMavenPublicationException failure(String message) {
            return new InvalidMavenPublicationException(publicationName, message);
        }
    }
}