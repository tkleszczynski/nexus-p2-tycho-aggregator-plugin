/**
 * Sonatype Nexus (TM) Open Source Version Copyright (c) 2007-2012 Sonatype, Inc. All rights reserved. Includes the
 * third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 * 
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version
 * 1.0, which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 * 
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are
 * trademarks of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark
 * of the Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package com.qualitype.nexus.plugins.tycho.internal;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.getRelativePath;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.isHidden;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.localStorageOfRepositoryAsFile;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.retrieveFile;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.retrieveItem;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.safeRetrieveFile;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.safeRetrieveItem;
import static com.qualitype.nexus.plugins.tycho.internal.NexusUtils.storeItem;
import static com.qualitype.nexus.plugins.tycho.internal.P2TychoArtifactsEventsInspector.isP2ArtifactsXML;
import static com.qualitype.nexus.plugins.tycho.internal.P2TychoMetadataEventsInspector.isP2ContentXML;
import static org.codehaus.plexus.util.FileUtils.deleteDirectory;
import static org.sonatype.nexus.plugins.p2.repository.P2Constants.P2_REPOSITORY_ROOT_PATH;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.slf4j.Logger;
import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.nexus.plugins.p2.repository.P2Constants;
import org.sonatype.nexus.proxy.NoSuchRepositoryException;
import org.sonatype.nexus.proxy.ResourceStoreRequest;
import org.sonatype.nexus.proxy.access.Action;
import org.sonatype.nexus.proxy.item.RepositoryItemUid;
import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.registry.RepositoryRegistry;
import org.sonatype.nexus.proxy.repository.Repository;
import org.sonatype.p2.bridge.ArtifactRepository;
import org.sonatype.p2.bridge.MetadataRepository;
import org.sonatype.p2.bridge.model.InstallableArtifact;
import org.sonatype.p2.bridge.model.InstallableUnit;
import org.sonatype.sisu.resource.scanner.helper.ListenerSupport;
import org.sonatype.sisu.resource.scanner.scanners.SerialScanner;

import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregator;
import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregatorConfiguration;

@Named
@Singleton
public class DefaultP2TychoRepositoryAggregator implements P2TychoRepositoryAggregator {

    private static final String UNDERSCORE = "_";

    private static final String JAR = ".jar";

    private static final String ARTIFACTS_XML = "artifacts.xml";

    private static final String CONTENT_XML = "content.xml";

    @Inject
    private Logger logger;

    private final Map<String, P2TychoRepositoryAggregatorConfiguration> configurations;

    private final RepositoryRegistry repositories;

    private final MimeSupport mimeUtil;

    private final ArtifactRepository artifactRepository;

    private final MetadataRepository metadataRepository;

    @Inject
    public DefaultP2TychoRepositoryAggregator(final RepositoryRegistry repositories, final MimeSupport mimeUtil,
            final ArtifactRepository artifactRepository, final MetadataRepository metadataRepository) {
        this.repositories = repositories;
        this.mimeUtil = mimeUtil;
        this.artifactRepository = artifactRepository;
        this.metadataRepository = metadataRepository;
        configurations = new HashMap<String, P2TychoRepositoryAggregatorConfiguration>();
    }

    @Override
    public P2TychoRepositoryAggregatorConfiguration getConfiguration(final String repositoryId) {
        return configurations.get(repositoryId);
    }

    @Override
    public void addConfiguration(final P2TychoRepositoryAggregatorConfiguration configuration) {
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final StorageItem p2Dir = safeRetrieveItem(repository, P2_REPOSITORY_ROOT_PATH);
            // create if it does not exist
            if (p2Dir == null) {
                final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
                try {
                    p2RepoUid.getLock().lock(Action.create);
                    createP2Repository(repository);
                } finally {
                    p2RepoUid.getLock().unlock();
                }
            }
        } catch (final NoSuchRepositoryException e) {
            logger.warn("Could not delete P2 repository [{}] as repository could not be found");
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void removeConfiguration(final P2TychoRepositoryAggregatorConfiguration configuration) {
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            try {
                p2RepoUid.getLock().lock(Action.create);
                final ResourceStoreRequest request = new ResourceStoreRequest(P2_REPOSITORY_ROOT_PATH);
                repository.deleteItem(request);
            } finally {
                p2RepoUid.getLock().unlock();
            }
        } catch (final Exception e) {
            logger.warn(String.format("Could not delete P2 repository [%s:%s] due to [%s]",
                    configuration.repositoryId(), P2_REPOSITORY_ROOT_PATH, e.getMessage()), e);
        }
    }

    @Override
    public void enableAggregationFor(final P2TychoRepositoryAggregatorConfiguration configuration) {
        configurations.put(checkNotNull(configuration).repositoryId(), configuration);
    }

    @Override
    public void disableAggregationFor(final P2TychoRepositoryAggregatorConfiguration configuration) {
        configurations.remove(checkNotNull(configuration).repositoryId());
    }

    @Override
    public void updateP2Artifacts(final StorageItem item) {
        final P2TychoRepositoryAggregatorConfiguration configuration = getConfiguration(item.getRepositoryId());
        if (configuration == null) {
            return;
        }
        logger.debug("Updating P2 repository artifacts (update) for [{}:{}]", item.getRepositoryId(), item.getPath());
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            File destinationP2Repository = null;
            try {
                p2RepoUid.getLock().lock(Action.update);

                // copy repository artifacts to a temporary location
                destinationP2Repository = createTemporaryP2Repository();
                final File artifactsInRepository = getP2Artifacts(configuration, repository);
                final File tempArtifacts = new File(destinationP2Repository, artifactsInRepository.getName());
                FileUtils.copyFile(artifactsInRepository, tempArtifacts);
                updateP2Artifacts(repository, retrieveFile(repository, item.getPath()), destinationP2Repository);

                // copy repository artifacts back to exposed location
                FileUtils.copyFile(tempArtifacts, artifactsInRepository);
            } finally {
                p2RepoUid.getLock().unlock();
                deleteDirectory(destinationP2Repository);
            }
        } catch (final Exception e) {
            logger.warn(
                    String.format("Could not update P2 repository [%s:%s] with [%s] due to [%s]",
                            configuration.repositoryId(), P2_REPOSITORY_ROOT_PATH, item.getPath(), e.getMessage()), e);
        }
    }

    @Override
    public void removeP2Artifacts(final StorageItem item) {
        final P2TychoRepositoryAggregatorConfiguration configuration = getConfiguration(item.getRepositoryId());
        if (configuration == null) {
            return;
        }
        logger.debug("Updating P2 repository artifacts (remove) for [{}:{}]", item.getRepositoryId(), item.getPath());
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            File destinationP2Repository = null;
            try {
                p2RepoUid.getLock().lock(Action.update);

                // copy repository artifacts to a temporary location
                destinationP2Repository = createTemporaryP2Repository();
                final File artifacts = getP2Artifacts(configuration, repository);
                final File tempArtifacts = new File(destinationP2Repository, artifacts.getName());
                FileUtils.copyFile(artifacts, tempArtifacts);
                removeP2Artifacts(repository, retrieveFile(repository, item.getPath()), destinationP2Repository);

                // copy repository artifacts back to exposed location
                FileUtils.copyFile(tempArtifacts, artifacts);
            } finally {
                p2RepoUid.getLock().unlock();
                deleteDirectory(destinationP2Repository);
            }
        } catch (final Exception e) {
            logger.warn(
                    String.format("Could not update P2 repository [%s:%s] with [%s] due to [%s]",
                            configuration.repositoryId(), P2_REPOSITORY_ROOT_PATH, item.getPath(), e.getMessage()), e);
        }
    }

    @Override
    public void updateP2Metadata(final StorageItem item) {
        final P2TychoRepositoryAggregatorConfiguration configuration = getConfiguration(item.getRepositoryId());
        if (configuration == null) {
            return;
        }
        logger.debug("Updating P2 repository metadata (update) for [{}:{}]", item.getRepositoryId(), item.getPath());
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            File destinationP2Repository = null;
            try {
                p2RepoUid.getLock().lock(Action.update);

                // copy repository content to a temporary location
                destinationP2Repository = createTemporaryP2Repository();
                final File content = getP2Content(configuration, repository);
                final File tempContent = new File(destinationP2Repository, content.getName());
                FileUtils.copyFile(content, tempContent);

                updateP2Metadata(repository, retrieveFile(repository, item.getPath()), destinationP2Repository);

                // copy repository content back to exposed location
                FileUtils.copyFile(tempContent, content);
            } finally {
                p2RepoUid.getLock().unlock();
                deleteDirectory(destinationP2Repository);
            }
        } catch (final Exception e) {
            logger.warn(
                    String.format("Could not update P2 repository [%s:%s] with [%s] due to [%s]",
                            configuration.repositoryId(), P2_REPOSITORY_ROOT_PATH, item.getPath(), e.getMessage()), e);
        }
    }

    @Override
    public void removeP2Metadata(final StorageItem item) {
        final P2TychoRepositoryAggregatorConfiguration configuration = getConfiguration(item.getRepositoryId());
        if (configuration == null) {
            return;
        }
        logger.debug("Updating P2 repository metadata (remove) for [{}:{}]", item.getRepositoryId(), item.getPath());
        try {
            final Repository repository = repositories.getRepository(configuration.repositoryId());
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            File destinationP2Repository = null;
            try {
                p2RepoUid.getLock().lock(Action.update);

                // copy repository content to a temporary location
                destinationP2Repository = createTemporaryP2Repository();
                final File content = getP2Content(configuration, repository);
                final File tempContent = new File(destinationP2Repository, content.getName());
                FileUtils.copyFile(content, tempContent);

                removeP2Metadata(repository, retrieveFile(repository, item.getPath()), destinationP2Repository);

                // copy repository content back to exposed location
                FileUtils.copyFile(tempContent, content);
            } finally {
                p2RepoUid.getLock().unlock();
                deleteDirectory(destinationP2Repository);
            }
        } catch (final Exception e) {
            logger.warn(
                    String.format("Could not update P2 repository [%s:%s] with [%s] due to [%s]",
                            configuration.repositoryId(), P2_REPOSITORY_ROOT_PATH, item.getPath(), e.getMessage()), e);
        }
    }

    @Override
    public void scanAndRebuild(final String repositoryId) {
        logger.debug("Rebuilding P2 repository for repository [{}]", repositoryId);

        final P2TychoRepositoryAggregatorConfiguration configuration = getConfiguration(repositoryId);
        if (configuration == null) {
            logger.warn(
                    "Rebuilding P2 repository for [{}] not executed as P2 Repository Generator capability is not enabled for this repository",
                    repositoryId);
            return;
        }

        try {
            final Repository repository = repositories.getRepository(repositoryId);
            final File scanPath = localStorageOfRepositoryAsFile(repository);
            final RepositoryItemUid p2RepoUid = repository.createUid(P2_REPOSITORY_ROOT_PATH);
            final File destinationP2Repository = createTemporaryP2Repository();
            try {
                p2RepoUid.getLock().lock(Action.update);

                // copy repository artifacts to a temporary location
                final File artifacts = getP2Artifacts(configuration, repository);
                final File tempArtifacts = new File(destinationP2Repository, artifacts.getName());
                FileUtils.copyFile(artifacts, tempArtifacts);

                // copy repository content to a temporary location
                final File content = getP2Content(configuration, repository);
                final File tempContent = new File(destinationP2Repository, content.getName());
                FileUtils.copyFile(content, tempContent);

                new SerialScanner().scan(scanPath, new ListenerSupport() {

                    @Override
                    public void onFile(final File file) {
                        try {
                            if (!isHidden(getRelativePath(scanPath, file))) {
                                if (isP2ArtifactsXML(file.getPath())) {
                                    updateP2Artifacts(repository, file, destinationP2Repository);
                                } else if (isP2ContentXML(file.getPath())) {
                                    updateP2Metadata(repository, file, destinationP2Repository);
                                }
                            }
                        } catch (final Exception e) {
                            throw new RuntimeException(e);
                        }
                    }

                });

                // copy artifacts back to exposed location
                FileUtils.copyFile(tempArtifacts, artifacts);
                // copy content back to exposed location
                FileUtils.copyFile(tempContent, content);
            } finally {
                p2RepoUid.getLock().unlock();

                deleteDirectory(destinationP2Repository);
            }
        } catch (final Exception e) {
            logger.warn(String.format(
                    "Rebuilding P2 repository not executed as repository [%s] could not be scanned due to [%s]",
                    repositoryId, e.getMessage()), e);
        }
    }

    @Override
    public void scanAndRebuild() {
        for (final Repository repository : repositories.getRepositories()) {
            scanAndRebuild(repository.getId());
        }
    }

    private void updateP2Artifacts(final Repository repository, final File sourceArtifacts,
            final File destinationP2Repository) throws Exception {
        logger.debug("Updating p2 artifacts for " + sourceArtifacts.getName());
        processP2Artifacts(repository, sourceArtifacts, destinationP2Repository, new P2MetadataProcessor() {

            @Override
            public void processMetadata(final File sourceP2Repository, final File destinationP2Repository) {
                artifactRepository.merge(sourceP2Repository.toURI(), destinationP2Repository.toURI());
            }
        }, new InstallableArtifactsHandler() {

            @Override
            public void handleArtifact(final Repository repository, final StorageItem bundle, final String link)
                    throws Exception {
                NexusUtils.createLink(repository, bundle, link);
            }
        });
    }

    private void removeP2Artifacts(final Repository repository, final File sourceArtifacts,
            final File destinationP2Repository) throws Exception {
        logger.debug("Removing p2 artifacts for " + sourceArtifacts.getName());
        processP2Artifacts(repository, sourceArtifacts, destinationP2Repository, new P2MetadataProcessor() {

            @Override
            public void processMetadata(final File sourceP2Repository, final File destinationP2Repository) {
                artifactRepository.remove(sourceP2Repository.toURI(), destinationP2Repository.toURI());
            }
        }, new InstallableArtifactsHandler() {

            @Override
            public void handleArtifact(final Repository repository, final StorageItem bundle, final String link)
                    throws Exception {
                NexusUtils.deleteLink(repository, bundle, link);
            }
        });
    }

    private void processP2Artifacts(final Repository repository, final File sourceArtifacts,
            final File destinationP2Repository, final P2MetadataProcessor processor,
            final InstallableArtifactsHandler artifactHandler) throws IOException, FileNotFoundException {
        final File sourceP2Repository = createTemporaryP2Repository();
        List<String> lines = readLinesFromFile(sourceArtifacts);
        try {
            File fileToMerge = sourceArtifacts;
            // if there is no proper repository header (like from Tycho builds), we're adding one
            if (!hasArtifactsRepositoryHeader(lines)) {
                fileToMerge = fixArtifactsRepositoryHeader(sourceP2Repository, lines);
            }

            // copy content to a temp location
            FileUtils.copyFile(fileToMerge, new File(sourceP2Repository, ARTIFACTS_XML));
            processor.processMetadata(sourceP2Repository, destinationP2Repository);

            // handle deployed artifacts
            handleArtifacts(repository, sourceArtifacts, sourceP2Repository, artifactHandler);
        } catch (Exception e) {
            logger.debug("Updating p2 Artifacts failed: " + e.getMessage());
        } finally {
            deleteDirectory(sourceP2Repository);
        }
    }

    private boolean hasArtifactsRepositoryHeader(final List<String> lines) {
        return lines.get(2).contains("<repository");
    }

    /**
     * Creates new file with additional lines in artifacts metadata (those lines allows to use merge / remove operations
     * using p2 plugins).
     * @param sourceP2Repository
     * @param lines
     * @return
     * @throws FileNotFoundException
     */
    private File fixArtifactsRepositoryHeader(final File sourceP2Repository, final List<String> lines)
            throws FileNotFoundException {
        lines.add(2,
                "<repository name=\"temporary\" type=\"org.eclipse.equinox.p2.artifact.repository.simpleRepository\" version=\"1\">");
        lines.add(
                3,
                "<properties size=\"1\"><property name=\"p2.timestamp\" value=\""
                        + String.valueOf(new Date().getTime()) + "\"/> </properties>");
        lines.add(lines.size(), "</repository>");
        File tempFile = FileUtils.createTempFile("temporary-p2artifacts", ".xml", sourceP2Repository);

        final PrintWriter writer = new PrintWriter(tempFile);
        try {
            for (String line : lines) {
                logger.debug(line);
                writer.println(line);
            }
            writer.flush();
        } finally {
            writer.close();
        }
        return tempFile;
    }

    /**
     * Method that allows to perform some operations defined by handler on each artifact (plugin, feature) deployed with
     * -p2artifacts.xml
     * @param repository repository where artifacts are deployed
     * @param sourceArtifacts file deployed to repository
     * @param sourceP2Repository path to p2 repository
     * @param handler artifact handler
     * @throws Exception error while processing artifacts
     */
    private void handleArtifacts(final Repository repository, final File sourceArtifacts,
            final File sourceP2Repository, final InstallableArtifactsHandler handler) throws Exception {
        // create a link in /plugins directory back to original jar
        final Collection<InstallableArtifact> installableArtifacts = artifactRepository
                .getInstallableArtifacts(sourceP2Repository.toURI());

        logger.debug("InstallableArtifacts: " + installableArtifacts);

        for (final InstallableArtifact installableArtifact : installableArtifacts) {
            // do handle plug-ins and features, but not binaries
            String subDirectory = null;
            if (installableArtifact.getClassifier().equals("osgi.bundle")) {
                subDirectory = "/plugins/";
            } else if (installableArtifact.getClassifier().equals("org.eclipse.update.feature")) {
                subDirectory = "/features/";
            }

            if (subDirectory != null) {
                final String linkPath = P2_REPOSITORY_ROOT_PATH + subDirectory + installableArtifact.getId()
                        + UNDERSCORE + installableArtifact.getVersion() + JAR;

                // We need to create a path to the physical jar in the repository, relative to the repository.
                // XXX: This is a hack.
                String artifactPath = sourceArtifacts.getPath().replace("-p2artifacts.xml", JAR);
                artifactPath = artifactPath.substring(artifactPath.indexOf(repository.getId())
                        + repository.getId().length(), artifactPath.length());

                final StorageItem bundle = retrieveItem(repository, artifactPath);
                handler.handleArtifact(repository, bundle, linkPath);
            }
        }
    }

    private void updateP2Metadata(final Repository repository, final File sourceContent,
            final File destinationP2Repository) throws Exception {
        processP2Metadata(sourceContent, destinationP2Repository, new P2MetadataProcessor() {

            @Override
            public void processMetadata(final File sourceP2Repository, final File destinationP2Repository) {
                metadataRepository.merge(sourceP2Repository.toURI(), destinationP2Repository.toURI());
            }
        });
    }

    private void removeP2Metadata(final Repository repository, final File sourceContent,
            final File destinationP2Repository) throws Exception {
        processP2Metadata(sourceContent, destinationP2Repository, new P2MetadataProcessor() {

            @Override
            public void processMetadata(final File sourceP2Repository, final File destinationP2Repository) {
                metadataRepository.remove(sourceP2Repository.toURI(), destinationP2Repository.toURI());
            }
        });
    }

    private void processP2Metadata(final File sourceContent, final File destinationP2Repository,
            final P2MetadataProcessor metadataProcessor) throws IOException, FileNotFoundException {
        final File sourceP2Repository = createTemporaryP2Repository();
        List<String> lines = readLinesFromFile(sourceContent);
        try {
            File fileToMerge = sourceContent;
            // if there is no proper repository header (like from Tycho builds), we're adding one
            if (!hasMetadataRepositoryHeader(lines)) {
                fileToMerge = fixMetadataRepositoryHeader(sourceP2Repository, lines);
            }

            // copy content to a temp location
            FileUtils.copyFile(fileToMerge, new File(sourceP2Repository, CONTENT_XML));
            metadataProcessor.processMetadata(sourceP2Repository, destinationP2Repository);
        } finally {
            deleteDirectory(sourceP2Repository);
        }
    }

    private File fixMetadataRepositoryHeader(final File sourceP2Repository, final List<String> lines)
            throws FileNotFoundException {
        File fileToMerge;
        lines.add(1, "<?metadataRepository version='1.1.0'?>");
        lines.add(
                2,
                "<repository name=\"temporary\" type=\"org.eclipse.equinox.internal.p2.metadata.repository.LocalMetadataRepository\" version=\"1\">");
        lines.add(
                3,
                "<properties size=\"1\"><property name=\"p2.timestamp\" value=\""
                        + String.valueOf(new Date().getTime()) + "\"/> </properties>");
        lines.add(lines.size(), "</repository>");
        fileToMerge = FileUtils.createTempFile("temporary-p2content", ".xml", sourceP2Repository);

        final PrintWriter writer = new PrintWriter(fileToMerge);
        try {
            for (String line : lines) {
                logger.debug(line);
                writer.println(line);
            }
            writer.flush();
        } finally {
            writer.close();
        }
        return fileToMerge;
    }

    private boolean hasMetadataRepositoryHeader(final List<String> lines) {
        return lines.get(1).contains("<?metadataRepository");
    }

    private List<String> readLinesFromFile(final File sourceContent) throws FileNotFoundException {
        final Scanner scanner = new Scanner(sourceContent);
        List<String> lines = new ArrayList<String>();
        while (scanner.hasNext()) {
            lines.add(scanner.nextLine());
        }
        scanner.close();
        return lines;
    }

    private void storeItemFromFile(final String path, final File file, final Repository repository) throws Exception {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            final ResourceStoreRequest request = new ResourceStoreRequest(path);
            storeItem(repository, request, in, mimeUtil.guessMimeTypeFromPath(request.getRequestPath()), null
            /* attributes */);
        } finally {
            IOUtil.close(in);
        }
    }

    private File getP2Artifacts(final P2TychoRepositoryAggregatorConfiguration configuration,
            final Repository repository) throws Exception {
        final String path = P2_REPOSITORY_ROOT_PATH + P2Constants.ARTIFACTS_XML;
        File file = safeRetrieveFile(repository, path);
        if (!file.exists()) {
            createP2Repository(repository);
            file = retrieveFile(repository, path);
        }
        return file;
    }

    private File getP2Content(final P2TychoRepositoryAggregatorConfiguration configuration, final Repository repository)
            throws Exception {
        final String path = P2_REPOSITORY_ROOT_PATH + P2Constants.CONTENT_XML;
        File file = safeRetrieveFile(repository, path);
        if (!file.exists()) {
            createP2Repository(repository);
            file = retrieveFile(repository, path);
        }
        return file;
    }

    private void createP2Repository(final Repository repository) throws Exception {
        File tempP2Repository = null;
        try {
            tempP2Repository = createTemporaryP2Repository();
            // create empty artifact.xml file
            artifactRepository.write(tempP2Repository.toURI(), Collections.<InstallableArtifact> emptyList(),
                    repository.getId(), null /** repository properties */
                    , null /* mappings */);
            final String p2ArtifactsPath = P2_REPOSITORY_ROOT_PATH + P2Constants.ARTIFACTS_XML;
            storeItemFromFile(p2ArtifactsPath, new File(tempP2Repository, ARTIFACTS_XML), repository);

            // create empty content.xml file
            metadataRepository.write(tempP2Repository.toURI(), Collections.<InstallableUnit> emptyList(),
                    repository.getId(), null /** repository properties */
            );
            final String p2ContentPath = P2_REPOSITORY_ROOT_PATH + "/" + P2Constants.CONTENT_XML;
            storeItemFromFile(p2ContentPath, new File(tempP2Repository, CONTENT_XML), repository);
        } finally {
            FileUtils.deleteDirectory(tempP2Repository);
        }
    }

    private File createTemporaryP2Repository() throws IOException {
        File tempP2Repository = File.createTempFile("nexus-p2-tycho-repository-plugin", "");
        tempP2Repository.delete();
        tempP2Repository.mkdirs();
        return tempP2Repository;
    }

}
