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
package org.commonjava.indy.koji.content;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.indy.koji.inject.KojiMavenVersionMetadataCache;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.pkg.maven.content.group.MavenMetadataProvider;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.maven.atlas.ident.ref.InvalidRefException;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectRef;
import org.commonjava.maven.atlas.ident.util.VersionUtils;
import org.commonjava.maven.atlas.ident.version.InvalidVersionSpecificationException;
import org.commonjava.maven.atlas.ident.version.SingleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.commonjava.indy.model.core.StoreType.group;

/**
 * Created by jdcasey on 11/1/16.
 */
@ApplicationScoped
public class KojiMavenMetadataProvider
        implements MavenMetadataProvider
{

    private static final java.lang.String LAST_UPDATED_FORMAT = "yyyyMMddHHmmss";

    @Inject
    @KojiMavenVersionMetadataCache
    private CacheHandle<ProjectRef, Metadata> versionMetadata;

    @Inject
    private KojiClient kojiClient;

    @Inject
    private IndyKojiConfig kojiConfig;

    private final Map<ProjectRef, ReentrantLock> versionMetadataLocks = new WeakHashMap<>();

    protected KojiMavenMetadataProvider(){}

    public KojiMavenMetadataProvider( CacheHandle<ProjectRef, Metadata> versionMetadata, KojiClient kojiClient,
                                      IndyKojiConfig kojiConfig )
    {
        this.versionMetadata = versionMetadata;
        this.kojiClient = kojiClient;
        this.kojiConfig = kojiConfig;
    }

    public Metadata getMetadata( StoreKey targetKey, String path )
            throws IndyWorkflowException
    {
        if ( group != targetKey.getType() )
        {
            return null;
        }

        if ( !kojiConfig.isEnabled() )
        {
            return null;
        }

        if ( !kojiConfig.isEnabledFor( targetKey.getName() ) )
        {
            return null;
        }

        File mdFile = new File( path );
        File artifactDir = mdFile.getParentFile();
        File groupDir = artifactDir == null ? null : artifactDir.getParentFile();

        if ( artifactDir == null || groupDir == null )
        {
            return null;
        }

        String groupId = groupDir.getPath().replace( File.separatorChar, '.' );
        String artifactId = artifactDir.getName();

        ProjectRef ga = null;
        try
        {
            ga = new SimpleProjectRef( groupId, artifactId );
        }
        catch ( InvalidRefException e )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.debug( "Not a valid Maven GA: {}:{}. Skipping Koji metadata retrieval.", groupId, artifactId );
        }

        if ( ga == null )
        {
            return null;
        }

        ReentrantLock lock;
        synchronized ( versionMetadataLocks )
        {
            lock = versionMetadataLocks.get( ga );
            if ( lock == null )
            {
                lock = new ReentrantLock();
                versionMetadataLocks.put( ga, lock );
            }
        }

        try
        {
            boolean locked = lock.tryLock( kojiConfig.getLockTimeoutSeconds(), TimeUnit.SECONDS );
            if ( !locked )
            {
                throw new IndyWorkflowException(
                        "Failed to acquire Koji GA version metadata lock on: %s in %d seconds.", ga,
                        kojiConfig.getLockTimeoutSeconds() );
            }

            Metadata metadata = versionMetadata.get( ga );
            ProjectRef ref = ga;
            if ( metadata == null )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );

                try
                {
                    metadata = kojiClient.withKojiSession( ( session ) -> {

                        List<KojiArchiveInfo> archives = kojiClient.listArchivesMatching( ref, session );

                        Set<SingleVersion> versions = new HashSet<>();
                        for ( KojiArchiveInfo archive : archives )
                        {
                            if ( !archive.getFilename().endsWith( ".pom" ) )
                            {
                                continue;
                            }

                            logger.debug( "Checking for builds/tags of: {}", archive );
                            List<KojiTagInfo> tags = kojiClient.listTags( archive.getBuildId(), session );

                            for ( KojiTagInfo tag : tags )
                            {
                                if ( kojiConfig.isTagAllowed( tag.getName() ) )
                                {
                                    try
                                    {
                                        versions.add( VersionUtils.createSingleVersion( archive.getVersion() ) );
                                    }
                                    catch ( InvalidVersionSpecificationException e )
                                    {
                                        logger.warn( String.format(
                                                "Encountered invalid version: %s for archive: %s. Reason: %s",
                                                archive.getVersion(), archive.getArchiveId(), e.getMessage() ), e );
                                    }
                                }
                            }
                        }

                        if ( versions.isEmpty() )
                        {
                            return null;
                        }

                        List<SingleVersion> sortedVersions = new ArrayList<>( versions );
                        Collections.sort( sortedVersions );

                        Metadata md = new Metadata();
                        md.setGroupId( ref.getGroupId() );
                        md.setArtifactId( ref.getArtifactId() );

                        Versioning versioning = new Versioning();
                        versioning.setRelease( sortedVersions.get( versions.size() - 1 ).renderStandard() );
                        versioning.setLatest( sortedVersions.get( versions.size() - 1 ).renderStandard() );
                        versioning.setVersions(
                                sortedVersions.stream().map( ( v ) -> v.renderStandard() ).collect( Collectors.toList() ) );

                        Date lastUpdated = Calendar.getInstance( TimeZone.getTimeZone( "UTC" ) ).getTime();
                        versioning.setLastUpdated( new SimpleDateFormat( LAST_UPDATED_FORMAT ).format( lastUpdated ) );

                        md.setVersioning( versioning );

                        return md;
                    } );
                }
                catch ( KojiClientException e )
                {
                    throw new IndyWorkflowException(
                            "Failed to retrieve version metadata for: %s from Koji. Reason: %s", e, ga,
                            e.getMessage() );
                }

                Metadata md = metadata;

                if ( metadata != null )
                {
                    // FIXME: Need a way to listen for cache expiration and re-request this?
                    versionMetadata.execute( ( cache ) -> cache.getAdvancedCache()
                                                               .put( ref, md, kojiConfig.getMetadataTimeoutSeconds(),
                                                                     TimeUnit.SECONDS ) );
                }
            }

            return metadata;
        }
        catch ( InterruptedException e )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.warn( "Interrupted waiting for Koji GA version metadata lock on target: {}", ga );
        }
        finally
        {
            lock.unlock();
        }

        return null;
    }
}
