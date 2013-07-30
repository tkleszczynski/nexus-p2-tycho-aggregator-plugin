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
package com.qualitype.nexus.plugins.tycho.internal.capabilities;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.qualitype.nexus.plugins.tycho.internal.capabilities.P2TychoRepositoryAggregatorCapabilityDescriptor.TYPE_ID;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.plugins.capabilities.Condition;
import org.sonatype.nexus.plugins.capabilities.support.CapabilitySupport;
import org.sonatype.nexus.plugins.capabilities.support.condition.Conditions;
import org.sonatype.nexus.plugins.capabilities.support.condition.RepositoryConditions;
import org.sonatype.nexus.proxy.NoSuchRepositoryException;
import org.sonatype.nexus.proxy.registry.RepositoryRegistry;

import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregator;
import com.qualitype.nexus.plugins.tycho.P2TychoRepositoryAggregatorConfiguration;

@Named(TYPE_ID)
public class P2TychoRepositoryAggregatorCapability extends CapabilitySupport {

    private final P2TychoRepositoryAggregator service;

    private final Conditions conditions;

    private final RepositoryRegistry repositoryRegistry;

    private P2TychoRepositoryAggregatorConfiguration configuration;

    @Inject
    public P2TychoRepositoryAggregatorCapability(final P2TychoRepositoryAggregator service,
            final Conditions conditions, final RepositoryRegistry repositoryRegistry) {
        this.service = checkNotNull(service);
        this.conditions = checkNotNull(conditions);
        this.repositoryRegistry = checkNotNull(repositoryRegistry);
    }

    @Override
    public String description() {
        if (configuration != null) {
            try {
                return repositoryRegistry.getRepository(configuration.repositoryId()).getName();
            } catch (NoSuchRepositoryException e) {
                return configuration.repositoryId();
            }
        }
        return null;
    }

    @Override
    public void onCreate() throws Exception {
        configuration = createConfiguration(context().properties());
        service.addConfiguration(configuration);
    }

    @Override
    public void onLoad() throws Exception {
        onCreate();
    }

    @Override
    public void onUpdate() throws Exception {
        onRemove();
        onCreate();
    }

    @Override
    public void onRemove() throws Exception {
        service.removeConfiguration(configuration);
        configuration = null;
    }

    @Override
    public void onActivate() throws Exception {
        service.enableAggregationFor(configuration);
    }

    @Override
    public void onPassivate() throws Exception {
        service.disableAggregationFor(configuration);
    }

    @Override
    public Condition activationCondition() {
        return conditions.logical().and(
                conditions.repository().repositoryIsInService(new RepositoryConditions.RepositoryId() {
                    @Override
                    public String get() {
                        return configuration != null ? configuration.repositoryId() : null;
                    }
                }), conditions.capabilities().passivateCapabilityDuringUpdate());
    }

    @Override
    public Condition validityCondition() {
        return conditions.repository().repositoryExists(new RepositoryConditions.RepositoryId() {
            @Override
            public String get() {
                return configuration != null ? configuration.repositoryId() : null;
            }
        });
    }

    private P2TychoRepositoryAggregatorConfiguration createConfiguration(final Map<String, String> properties) {
        return new P2TychoRepositoryAggregatorConfiguration(properties);
    }

}
