/*
 * Copyright (C) 2005 - 2013 by TESIS DYNAware GmbH
 */
package com.qualitype.nexus.plugins.tycho.internal;

import java.io.File;

/**
 * Allows perform some operations on two P2 metadata repositories.
 */
public interface P2MetadataProcessor {

    /**
     * @param sourceP2Repository
     * @param destinationP2Repository
     */
    void processMetadata(File sourceP2Repository, File destinationP2Repository);

}
