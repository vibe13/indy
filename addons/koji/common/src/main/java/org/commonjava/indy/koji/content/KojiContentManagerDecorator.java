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
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildArchiveCollection;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiSessionInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.subsys.template.IndyGroovyException;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.inject.Inject;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@link ContentManager} decorator that watches the retrieve() methods. If the result is going to be a null {@link Transfer}
 * this decorator will attempt the following:
 * <ol>
 *     <li>Parse the path into a GAV</li>
 *     <li>Lookup matching builds in Koji</li>
 *     <li>Sort the builds by creation timestamp, with earliest Date first.</li>
 *     <li>Iterate through the builds until we find a build we can proxy:
 *       <ol>
 *           <li>List the tags that contain the build</li>
 *           <li>Check that the tag matches a {@link IndyKojiConfig} tag-whitelist pattern</li>
 *           <li>If the tag is accepted, format the storage URL to the build and create a {@link RemoteRepository} to proxy it.</li>
 *       </ol>
 *     </li>
 *     <li>If we have a proxied build repository from above, lookup the target group whose membership should be modified, based on the entrypoint group's name (using {@link IndyKojiConfig} target-groups</li>
 *     <li>Modify the target group's membership. Store the remote repository and the target group.</li>
 *     <li>Attempt to retrieve the requested path from the new proxy repository.</li>
 *     <li>Return the results.</li>
 * </ol>
 *
 * <b>NOTE:</b> Currently we're not attempting to wrap retrieveAll() or retrieveFirst() methods, since these are only
 * used by the PromotionValidationTools class, which exposes methods to promotion validation scripts. This is not the
 * place to be adding new Koji build proxies...
 *
 * Created by jdcasey on 5/20/16.
 */
@Decorator
public abstract class KojiContentManagerDecorator
        implements ContentManager
{
    private static final String CREATION_TRIGGER_GAV = "creation-trigger-GAV";

    private static final String NVR = "koji-NVR";

    public static final String KOJI_ORIGIN = "koji";

    private static final String KOJI_REPO_CREATOR_SCRIPT = "koji-repo-creator.groovy";

    @Delegate
    @Inject
    private ContentManager delegate;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private KojiClient kojiClient;

    @Inject
    private IndyKojiConfig config;

    @Inject
    private ScriptEngine scriptEngine;

    private KojiRepositoryCreator creator;

    @PostConstruct
    public void init()
    {
        try
        {
            creator = scriptEngine.parseStandardScriptInstance( ScriptEngine.StandardScriptType.store_creators,
                                                                KOJI_REPO_CREATOR_SCRIPT, KojiRepositoryCreator.class );
        }
        catch ( IndyGroovyException e )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.error( String.format( "Cannot create KojiRepositoryCreator instance: %s. Disabling Koji support.",
                                         e.getMessage() ), e );
            config.setEnabled( false );
        }
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return retrieve( store, path, new EventMetadata() );
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Transfer result = delegate.retrieve( store, path, eventMetadata );
        if ( result == null && StoreType.group == store.getKey().getType() )
        {
            if ( !config.getEnabled() )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.info( "Koji content-manager decorator is disabled." );
                return null;
            }

            Group group = (Group) store;

            if ( !config.isEnabledFor( group.getName() ) )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.info( "Koji content-manager decorator not enabled for: {}.", store.getKey() );
                return null;
            }

            Logger logger = LoggerFactory.getLogger( getClass() );

            // TODO: This won't work for maven-metadata.xml files! We need to hit a POM or jar or something first.
            ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
            if ( pathInfo != null )
            {
                ArtifactRef artifactRef = pathInfo.getArtifact();
                logger.info( "Searching for Koji build: {}", artifactRef );

                RemoteRepository proxyResult = proxyKojiBuild( artifactRef );
                if ( proxyResult != null )
                {
                    adjustTargetGroup(proxyResult, group);
                    result = delegate.retrieve( proxyResult, path, eventMetadata );
                }
            }
            else
            {
                logger.info( "Path is not a maven artifact reference: {}", path );
            }
        }

        // Finally, pass the Transfer back.
        return result;
    }

    private RemoteRepository proxyKojiBuild( final ArtifactRef artifactRef )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        try
        {
            return kojiClient.withKojiSession( ( session ) -> {
                List<KojiBuildInfo> builds = kojiClient.listBuildsContaining( artifactRef, session );

                Collections.sort( builds, ( build1, build2 ) -> build1.getCreationTime()
                                                                      .compareTo( build2.getCreationTime() ) );

                logger.debug( "Got {} builds from koji. Looking for best match.", builds.size() );

                for ( KojiBuildInfo build : builds )
                {
                    if ( build.getTaskId() == null )
                    {
                        logger.debug( "Build: {} is not a real build. It looks like a binary import. Skipping.",
                                      build.getNvr() );
                        // This is not a real build, it's a binary import.
                        continue;
                    }

                    logger.info( "Trying build: {} with id: {}", build.getNvr(), build.getId() );
                    List<KojiTagInfo> tags = kojiClient.listTags( build.getId(), session );
                    logger.debug( "Build is in {} tags...", tags.size() );
                    for ( KojiTagInfo tag : tags )
                    {
                        // If the tags match patterns configured in whitelist, construct a new remote repo.
                        if ( config.isTagAllowed( tag.getName() ) )
                        {
                            logger.info( "Koji tag is on whitelist: {}", tag.getName() );
                            return createRemoteRepository(artifactRef, build, session);
                        }
                        else
                        {
                            logger.debug( "Tag: {} is not in the whitelist.", tag.getName() );
                        }
                    }

                    logger.debug( "No whitelisted tags found for: {}", build.getNvr() );
                }

                logger.debug( "No builds were found that matched the restrictions." );

                return null;
            } );
        }
        catch ( KojiClientException e )
        {
            throw new IndyWorkflowException( "Cannot retrieve builds for: %s. Error: %s", e, artifactRef,
                                             e.getMessage() );
        }
    }

    private RemoteRepository createRemoteRepository(ArtifactRef artifactRef, final KojiBuildInfo build, final KojiSessionInfo session)
            throws KojiClientException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        try
        {
            KojiBuildArchiveCollection archiveCollection = kojiClient.listArchivesForBuild( build.getId(), session );

            String name = KOJI_ORIGIN + "-" + build.getNvr();

            // Using a RemoteRepository allows us to use the higher-level APIs in Indy, as opposed to TransferManager
            RemoteRepository remote = creator.createRemoteRepository( name, formatStorageUrl( build ),
                                                                      config.getDownloadTimeoutSeconds() );

            remote.setServerCertPem( config.getServerPemContent() );
            remote.setMetadata( ArtifactStore.METADATA_ORIGIN, KOJI_ORIGIN );
            // set pathMaskPatterns using build output paths
            Set<String> patterns = new HashSet<>();
            patterns.addAll( archiveCollection.getArchives()
                                              .stream()
                                              .map( a -> a.getGroupId().replace( '.', '/' ) + "/" + a.getArtifactId()
                                                      + "/" + a.getVersion() + "/" + a.getFilename() )
                                              .collect( Collectors.toSet() ) );

            remote.setPathMaskPatterns(patterns);

            remote.setMetadata( CREATION_TRIGGER_GAV, artifactRef.toString());
            remote.setMetadata( NVR, build.getNvr() );

            storeDataManager.storeArtifactStore( remote, new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                            "Creating remote repository for Koji build: "
                                                                                    + build.getNvr() ) );

            logger.debug("Koji {}, add pathMaskPatterns: {}", name, patterns);

            return remote;
        }
        catch ( MalformedURLException e )
        {
            throw new KojiClientException( "Koji add-on seems misconifigured. Could not generate URL to repo for "
                                                   + "build: %s\nBase URL: %s\nError: %s", e, build.getNvr(),
                                           config.getStorageRootUrl(), e.getMessage() );
        }
        catch ( IOException e )
        {
            throw new KojiClientException(
                    "Failed to read server SSL PEM information from Koji configuration for new hosted repo: %s", e,
                    e.getMessage() );
        }
        catch ( IndyDataException e )
        {
            throw new KojiClientException( "Failed to store temporary remote repo: %s", e, e.getMessage() );
        }
    }

    private Group adjustTargetGroup( final RemoteRepository buildRepo, final Group group )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );

        // try to lookup the group -> targetGroup mapping in config, using the
        // entry-point group as the lookup key. If that returns null, the targetGroup is
        // the entry-point group.
        Group targetGroup = group;

        String targetName = config.getTargetGroup( group.getName() );
        if ( targetName != null )
        {
            try
            {
                targetGroup = storeDataManager.getGroup( targetName );
            }
            catch ( IndyDataException e )
            {
                throw new IndyWorkflowException(
                        "Cannot lookup koji-addition target group: %s (source group: %s). Reason: %s", e, targetName,
                        group.getName(), e.getMessage() );
            }
        }

        logger.info( "Adding Koji build proxy: {} to group: {}", buildRepo.getKey(), targetGroup.getKey() );

        // Append the new remote repo as a member of the targetGroup.
        targetGroup.addConstituent( buildRepo );
        try
        {
            storeDataManager.storeArtifactStore( targetGroup, new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                                 "Adding remote repository for Koji build: "
                                                                                         + buildRepo.getMetadata(
                                                                                         NVR ) ) );
        }
        catch ( IndyDataException e )
        {
            throw new IndyWorkflowException( "Cannot store target-group: %s changes for: %s. Error: %s", e,
                                             targetGroup.getName(), buildRepo.getMetadata( NVR ), e.getMessage() );
        }

        logger.info( "Retrieving GAV: {} from: {}", buildRepo.getMetadata( CREATION_TRIGGER_GAV ), buildRepo );

        return targetGroup;

        // TODO: how to index it for the group...?
    }

    private String formatStorageUrl( final KojiBuildInfo buildInfo )
            throws MalformedURLException
    {
        String url =
                UrlUtils.buildUrl( config.getStorageRootUrl(), "packages", buildInfo.getName(), buildInfo.getVersion(),
                                   buildInfo.getRelease(), "maven" );

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Using Koji URL: {}", url );

        return url;
    }

}
