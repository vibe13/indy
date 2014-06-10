/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.aprox.autoprox.live.data;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.util.Collections;
import java.util.List;

import org.commonjava.aprox.autoprox.conf.FactoryMapping;
import org.commonjava.aprox.autoprox.live.fixture.TestAutoProxFactory;
import org.commonjava.aprox.autoprox.live.fixture.TestHttpServer;
import org.commonjava.aprox.autoprox.model.AutoProxCatalog;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.mem.data.MemoryStoreDataManager;
import org.commonjava.aprox.model.Group;
import org.commonjava.aprox.model.RemoteRepository;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.model.StoreType;
import org.commonjava.web.json.test.WebFixture;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class AutoProxDataManagerDecoratorTest
{

    public static final String REPO_ROOT_DIR = "repo.root.dir";

    protected StoreDataManager proxyManager = new MemoryStoreDataManager();

    @Rule
    public final TestHttpServer server = new TestHttpServer( "server-targets" );

    @Rule
    public final WebFixture http = new WebFixture();

    @Before
    public final void setup()
        throws Exception
    {
        proxyManager.install();
        proxyManager.clear();
    }

    @Test
    public void repositoryAutoCreated()
        throws Exception
    {
        final AutoProxCatalog catalog = simpleCatalog();

        final String testUrl = http.resourceUrl( "target", "test" );
        http.get( testUrl, 404 );
        //        targetResponder.approveTargets( "test" );
        http.get( testUrl, 200 );

        catalog.setEnabled( false );
        assertThat( proxyManager.getRemoteRepository( "test" ), nullValue() );
        catalog.setEnabled( true );

        final RemoteRepository repo = proxyManager.getRemoteRepository( "test" );

        assertThat( repo, notNullValue() );
        assertThat( repo.getName(), equalTo( "test" ) );
        assertThat( repo.getUrl(), equalTo( testUrl ) );

    }

    private AutoProxCatalog simpleCatalog()
    {
        final TestAutoProxFactory fac = new TestAutoProxFactory( http );

        return new AutoProxCatalog( true, Collections.singletonList( new FactoryMapping( "test.groovy", fac ) ) );
    }

    @Test
    public void groupAutoCreatedWithDeployPointAndTwoRepos()
        throws Exception
    {
        final AutoProxCatalog catalog = simpleCatalog();

        final String testUrl = http.resourceUrl( "target", "test" );
        http.get( testUrl, 404 );
        //        targetResponder.approveTargets( "test" );
        http.get( testUrl, 200 );

        catalog.setEnabled( false );
        assertThat( proxyManager.getGroup( "test" ), nullValue() );
        catalog.setEnabled( true );

        final Group group = proxyManager.getGroup( "test" );

        assertThat( group, notNullValue() );
        assertThat( group.getName(), equalTo( "test" ) );

        final List<StoreKey> constituents = group.getConstituents();

        assertThat( constituents, notNullValue() );
        assertThat( constituents.size(), equalTo( 4 ) );

        int idx = 0;
        StoreKey key = constituents.get( idx );

        assertThat( key.getType(), equalTo( StoreType.hosted ) );
        assertThat( key.getName(), equalTo( "test" ) );

        idx++;
        key = constituents.get( idx );

        assertThat( key.getType(), equalTo( StoreType.remote ) );
        assertThat( key.getName(), equalTo( "test" ) );

        idx++;
        key = constituents.get( idx );

        assertThat( key.getType(), equalTo( StoreType.remote ) );
        assertThat( key.getName(), equalTo( "first" ) );

        idx++;
        key = constituents.get( idx );

        assertThat( key.getType(), equalTo( StoreType.remote ) );
        assertThat( key.getName(), equalTo( "second" ) );
    }

    @Test
    public void repositoryNotAutoCreatedWhenTargetIsInvalid()
        throws Exception
    {
        final AutoProxCatalog catalog = simpleCatalog();

        final String testUrl = http.resourceUrl( "target", "test" );
        http.get( testUrl, 404 );

        catalog.setEnabled( false );
        assertThat( proxyManager.getRemoteRepository( "test" ), nullValue() );
        catalog.setEnabled( true );

        final RemoteRepository repo = proxyManager.getRemoteRepository( "test" );

        assertThat( repo, nullValue() );

    }

    @Test
    public void groupNotAutoCreatedWhenTargetIsInvalid()
        throws Exception
    {
        final AutoProxCatalog catalog = simpleCatalog();

        final String testUrl = http.resourceUrl( "target", "test" );
        http.get( testUrl, 404 );

        catalog.setEnabled( false );
        assertThat( proxyManager.getGroup( "test" ), nullValue() );
        catalog.setEnabled( true );

        final Group group = proxyManager.getGroup( "test" );

        assertThat( group, nullValue() );
    }

}
