/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.mem.data;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.change.event.ArtifactStoreUpdateType;
import org.commonjava.indy.conf.DefaultIndyConfiguration;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.NoOpStoreEventDispatcher;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.data.StoreEventDispatcher;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.commonjava.indy.model.core.StoreType.remote;

@ApplicationScoped
@Alternative
public class MemoryStoreDataManager
        implements StoreDataManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Map<StoreKey, ArtifactStore> stores = new ConcurrentHashMap<>();

    private final Map<StoreKey, ReentrantLock> opLocks = new WeakHashMap<>();

    //    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private StoreEventDispatcher dispatcher;

    @Inject
    private IndyConfiguration config;

    protected MemoryStoreDataManager()
    {
    }

    public MemoryStoreDataManager( final boolean unitTestUsage )
    {
        this.dispatcher = new NoOpStoreEventDispatcher();
        this.config = new DefaultIndyConfiguration();
    }

    public MemoryStoreDataManager( final StoreEventDispatcher dispatcher, final IndyConfiguration config )
    {
        this.dispatcher = dispatcher;
        this.config = config;
    }

    @Override
    public HostedRepository getHostedRepository( final String name )
            throws IndyDataException
    {
        return (HostedRepository) stores.get( new StoreKey( StoreType.hosted, name ) );
    }

    @Override
    public ArtifactStore getArtifactStore( final StoreKey key )
            throws IndyDataException
    {
        return stores.get( key );
    }

    @Override
    public RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException
    {
        final StoreKey key = new StoreKey( remote, name );

        RemoteRepository repo = (RemoteRepository) stores.get( key );
        if ( repo == null )
        {
            return null;
        }

        if ( repo.getTimeoutSeconds() < 1 )
        {
            repo.setTimeoutSeconds( config.getRequestTimeoutSeconds() );
        }

        return repo;
    }

    @Override
    public Group getGroup( final String name )
            throws IndyDataException
    {
        return (Group) stores.get( new StoreKey( StoreType.group, name ) );
    }

    @Override
    public List<Group> getAllGroups()
            throws IndyDataException
    {
        return getAll( StoreType.group, Group.class );
    }

    @Override
    public List<RemoteRepository> getAllRemoteRepositories()
            throws IndyDataException
    {
        return getAll( remote, RemoteRepository.class );
    }

    @Override
    public List<HostedRepository> getAllHostedRepositories()
            throws IndyDataException
    {
        return getAll( StoreType.hosted, HostedRepository.class );
    }

    @Override
    public List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName, final boolean enabledOnly )
            throws IndyDataException
    {
        return getGroupOrdering( groupName, false, true, enabledOnly );
    }

    @Override
    public List<ArtifactStore> getOrderedStoresInGroup( final String groupName, final boolean enabledOnly )
            throws IndyDataException
    {
        return getGroupOrdering( groupName, true, false, enabledOnly );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary )
            throws IndyDataException
    {
        return storeArtifactStore( store, summary, new EventMetadata() );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final EventMetadata eventMetadata )
            throws IndyDataException
    {
        return storeArtifactStore( store, summary, false, true, new EventMetadata() );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final boolean skipIfExists )
            throws IndyDataException
    {
        return storeArtifactStore( store, summary, skipIfExists, new EventMetadata() );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final boolean skipIfExists, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        return storeArtifactStore( store, summary, skipIfExists, true, new EventMetadata() );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final boolean skipIfExists, final boolean fireEvents )
            throws IndyDataException
    {
        return storeArtifactStore( store, summary, skipIfExists, fireEvents, new EventMetadata() );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final boolean skipIfExists, final boolean fireEvents,
                                       final EventMetadata eventMetadata )
            throws IndyDataException
    {
        return store( store, summary, skipIfExists, fireEvents, eventMetadata );
    }

    protected void preStore( final ArtifactStore store, final ArtifactStore original, final ChangeSummary summary,
                             final boolean exists, final boolean fireEvents, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            logger.debug( "Firing store pre-update event for: {} (originally: {})", store, original );
            dispatcher.updating( exists ? ArtifactStoreUpdateType.UPDATE : ArtifactStoreUpdateType.ADD, eventMetadata,
                                 Collections.singletonMap( store, original ) );

            if ( exists )
            {
                if ( store.isDisabled() && !original.isDisabled() )
                {
                    dispatcher.disabling( eventMetadata, store );
                }
                else if ( !store.isDisabled() && original.isDisabled() )
                {
                    dispatcher.enabling( eventMetadata, store );
                }
            }
        }
    }

    protected void postStore( final ArtifactStore store, final ArtifactStore original, final ChangeSummary summary,
                              final boolean exists, final boolean fireEvents, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            logger.debug( "Firing store post-update event for: {} (originally: {})", store, original );
            dispatcher.updated( exists ? ArtifactStoreUpdateType.UPDATE : ArtifactStoreUpdateType.ADD, eventMetadata,
                                Collections.singletonMap( store, original ) );

            if ( exists )
            {
                if ( store.isDisabled() && !original.isDisabled() )
                {
                    dispatcher.disabled( eventMetadata, store );
                }
                else if ( !store.isDisabled() && original.isDisabled() )
                {
                    dispatcher.enabled( eventMetadata, store );
                }
            }
        }
    }

    protected void preDelete( final ArtifactStore store, final ChangeSummary summary, final boolean fireEvents,
                              final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            dispatcher.deleting( eventMetadata, store );
        }
    }

    protected void postDelete( final ArtifactStore store, final ChangeSummary summary, final boolean fireEvents,
                               final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            dispatcher.deleted( eventMetadata, store );
        }
    }

    @Override
    public void deleteArtifactStore( final StoreKey key, final ChangeSummary summary )
            throws IndyDataException
    {
        deleteArtifactStore( key, summary, new EventMetadata() );
    }

    @Override
    public void deleteArtifactStore( final StoreKey key, final ChangeSummary summary,
                                                  final EventMetadata eventMetadata )
            throws IndyDataException
    {
        ReentrantLock opLock = getOpLock( key );
        try
        {
            opLock.lock();

            final ArtifactStore store = stores.get( key );
            if ( store == null )
            {
                return;
            }

            preDelete( store, summary, true, eventMetadata );

            ArtifactStore removed = stores.remove( key );

            postDelete( store, summary, true, eventMetadata );
        }
        finally
        {
            opLock.unlock();
        }
    }

    @Override
    public void install()
            throws IndyDataException
    {
    }

    @Override
    public void clear( final ChangeSummary summary )
            throws IndyDataException
    {
        stores.clear();
    }

    @Override
    public Set<Group> getGroupsContaining( final StoreKey repo )
            throws IndyDataException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Getting groups containing: {}", repo );

        Set<ArtifactStore> all = new HashSet<>( stores.values() );

        Set<Group> result = all.parallelStream()
                               .filter( ( store ) -> ( ( store instanceof Group ) && ( (Group) store ).getConstituents()
                                                                                                      .contains(
                                                                                                              repo ) ) )
                               .map( ( store ) -> (Group) store )
                               .collect( Collectors.toSet() );

        return result;
    }

    @Override
    public RemoteRepository findRemoteRepository( final String url )
    {
        List<Map.Entry<StoreKey, ArtifactStore>> copy = new ArrayList<>( stores.entrySet() );

        Optional<RemoteRepository> found = copy.stream()
                                               .filter( e -> ( ( remote == e.getValue().getKey().getType() )
                                                       && ( (RemoteRepository) e.getValue() ).getUrl().equals( url ) ) )
                                               .map( ( e ) -> (RemoteRepository) e.getValue() )
                                               .findFirst();
        return found.isPresent() ? found.get() : null;
    }

    @Override
    public List<ArtifactStore> getAllArtifactStores()
            throws IndyDataException
    {
        return new ArrayList<ArtifactStore>( stores.values() );
    }

    @Override
    public List<ArtifactStore> getAllConcreteArtifactStores()
    {
        return getAll( StoreType.hosted, remote );
    }

    @Override
    public List<? extends ArtifactStore> getAllArtifactStores( final StoreType type )
            throws IndyDataException
    {
        return getAll( type, type.getStoreClass() );
    }

    @Override
    public boolean hasRemoteRepository( final String name )
    {
        return hasArtifactStore( new StoreKey( remote, name ) );
    }

    @Override
    public boolean hasGroup( final String name )
    {
        return hasArtifactStore( new StoreKey( StoreType.group, name ) );
    }

    @Override
    public boolean hasHostedRepository( final String name )
    {
        return hasArtifactStore( new StoreKey( StoreType.hosted, name ) );
    }

    @Override
    public boolean hasArtifactStore( final StoreKey key )
    {
        return stores.containsKey( key );
    }

    @Override
    public void reload()
            throws IndyDataException
    {
    }

    @Override
    public boolean isStarted()
    {
        return true;
    }

    private boolean store( final ArtifactStore store, final ChangeSummary summary,
                           final boolean skipIfExists, final boolean fireEvents,
                           final EventMetadata eventMetadata )
            throws IndyDataException
    {
        ReentrantLock opLock = getOpLock( store.getKey() );
        try
        {
            opLock.lock();

            ArtifactStore original = stores.get( store.getKey() );
            if ( original == store )
            {
                // if they're the same instance, warn that preUpdate events may not work correctly!
                logger.warn(
                        "Storing changes on existing instance of: {}! You forgot to call {}.copyOf(), so preUpdate events may not accurately reflect before/after differences for this change!",
                        store, store.getClass().getSimpleName() );
            }

            if ( !skipIfExists || original == null )
            {
                preStore( store, original, summary, original != null, fireEvents, eventMetadata );
                final ArtifactStore old = stores.put( store.getKey(), store );
                try
                {
                    postStore( store, original, summary, original != null, fireEvents, eventMetadata );
                    return true;
                }
                catch ( final IndyDataException e )
                {
                    logger.error( "postStore() failed for: {}. Rolling back to old value: {}", store, old );
                    stores.put( old.getKey(), old );
                }
            }

            return false;
        }
        finally
        {
            opLock.unlock();
        }
    }

    private ReentrantLock getOpLock( StoreKey key )
    {
        ReentrantLock opLock;
        synchronized ( opLocks )
        {
            opLock = opLocks.get( key );
            if ( opLock == null )
            {
                opLock = new ReentrantLock();
                opLocks.put( key, opLock );
            }
        }

        return opLock;
    }

    private List<ArtifactStore> getGroupOrdering( final String groupName, final boolean includeGroups,
                                                  final boolean recurseGroups, final boolean enabledOnly )
            throws IndyDataException
    {
        final Group master = (Group) stores.get( new StoreKey( StoreType.group, groupName ) );
        if ( master == null )
        {
            return Collections.emptyList();
        }

        final List<ArtifactStore> result = new ArrayList<ArtifactStore>();
        recurseGroup( master, result, new HashSet<>(), includeGroups, recurseGroups, enabledOnly );

        return result;
    }

    private void recurseGroup( final Group master, final List<ArtifactStore> result, final Set<StoreKey> seen,
                               final boolean includeGroups, final boolean recurseGroups, final boolean enabledOnly )
    {
        if ( master == null || master.isDisabled() && enabledOnly )
        {
            return;
        }

        List<StoreKey> members = new ArrayList<>( master.getConstituents() );
        if ( includeGroups )
        {
            result.add( master );
        }

        members.forEach( ( key ) -> {
            if ( !seen.contains( key ) )
            {
                seen.add( key );
                final StoreType type = key.getType();
                if ( recurseGroups && type == StoreType.group )
                {
                    // if we're here, we're definitely recursing groups...
                    recurseGroup( (Group) stores.get( key ), result, seen, includeGroups, true, enabledOnly );
                }
                else
                {
                    final ArtifactStore store = stores.get( key );
                    if ( store != null && !( store.isDisabled() && enabledOnly ) )
                    {
                        result.add( store );
                    }
                }
            }
        } );
    }

    private <T extends ArtifactStore> List<T> getAll( final StoreType storeType, final Class<T> type )
    {
        List<Map.Entry<StoreKey, ArtifactStore>> copy = new ArrayList<>( stores.entrySet() );

        return copy.stream()
                   .filter( ( entry ) -> storeType == entry.getKey().getType() && type.isAssignableFrom(
                           entry.getValue().getClass() ) )
                   .map( ( entry ) -> type.cast( entry.getValue() ) )
                   .collect( Collectors.toList() );
    }

    private List<ArtifactStore> getAll( final StoreType... storeTypes )
    {
        List<Map.Entry<StoreKey, ArtifactStore>> copy = new ArrayList<>( stores.entrySet() );

        return copy.stream()
                   .filter( ( entry ) -> Arrays.binarySearch( storeTypes, entry.getKey().getType() ) > -1 )
                   .map( ( entry ) -> entry.getValue() )
                   .collect( Collectors.toList() );
    }

}
