package org.commonjava.aprox.client.core.module;

import org.commonjava.aprox.client.core.AproxClientException;
import org.commonjava.aprox.client.core.AproxClientModule;
import org.commonjava.aprox.client.core.util.UrlUtils;
import org.commonjava.aprox.model.core.ArtifactStore;
import org.commonjava.aprox.model.core.Group;
import org.commonjava.aprox.model.core.HostedRepository;
import org.commonjava.aprox.model.core.RemoteRepository;
import org.commonjava.aprox.model.core.StoreType;
import org.commonjava.aprox.model.core.dto.StoreListingDTO;

import com.fasterxml.jackson.core.type.TypeReference;

public class AproxStoresClientModule
    extends AproxClientModule
{
    public <T extends ArtifactStore> T create( final T value, final Class<T> type )
        throws AproxClientException
    {
        return http.postWithResponse( UrlUtils.buildUrl( "admin", value.getKey()
                                                                                        .getType()
                                                                                        .singularEndpointName() ),
                                      value, type );
    }

    public boolean exists( final StoreType type, final String name )
        throws AproxClientException
    {
        return http.exists( UrlUtils.buildUrl( "admin", type.singularEndpointName(), name ) );
    }

    public void delete( final StoreType type, final String name )
        throws AproxClientException
    {
        http.delete( UrlUtils.buildUrl( "admin", type.singularEndpointName(), name ) );
    }

    public boolean update( final ArtifactStore store )
        throws AproxClientException
    {
        return http.put( UrlUtils.buildUrl( "admin", store.getKey()
                                                                           .getType()
                                                                           .singularEndpointName(), store.getName() ),
                         store );
    }

    public <T extends ArtifactStore> T load( final StoreType type, final String name, final Class<T> cls )
        throws AproxClientException
    {
        return http.get( UrlUtils.buildUrl( "admin", type.singularEndpointName(), name ), cls );
    }

    public StoreListingDTO<HostedRepository> listHostedRepositories()
        throws AproxClientException
    {
        return http.get( UrlUtils.buildUrl( "admin", StoreType.hosted.singularEndpointName() ),
                         new TypeReference<StoreListingDTO<HostedRepository>>()
                         {
                         } );
    }

    public StoreListingDTO<RemoteRepository> listRemoteRepositories()
        throws AproxClientException
    {
        return http.get( UrlUtils.buildUrl( "admin", StoreType.remote.singularEndpointName() ),
                         new TypeReference<StoreListingDTO<RemoteRepository>>()
                         {
                         } );
    }

    public StoreListingDTO<Group> listGroups()
        throws AproxClientException
    {
        return http.get( UrlUtils.buildUrl( "admin", StoreType.group.singularEndpointName() ),
                         new TypeReference<StoreListingDTO<Group>>()
                         {
                         } );
    }

}