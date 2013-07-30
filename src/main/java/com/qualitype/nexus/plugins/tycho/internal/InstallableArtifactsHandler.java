/*
 * Copyright (C) 2005 - 2013 by TESIS DYNAware GmbH
 */
package com.qualitype.nexus.plugins.tycho.internal;

import org.sonatype.nexus.proxy.item.StorageItem;
import org.sonatype.nexus.proxy.repository.Repository;

/**
 * Allows perform some action on artifacts related with P2 metadata.
 */
public interface InstallableArtifactsHandler {

    /**
     * @param repository M2 repository
     * @param bundle storage item
     * @param link path to file in ./meta/p2 directory in repository
     * @throws Exception error while handling artifact
     */
    void handleArtifact(final Repository repository, StorageItem bundle, String link) throws Exception;

}
