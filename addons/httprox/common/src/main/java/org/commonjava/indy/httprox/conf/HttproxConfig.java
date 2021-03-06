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
package org.commonjava.indy.httprox.conf;

import org.commonjava.indy.conf.IndyConfigInfo;
import org.commonjava.web.config.annotation.ConfigName;
import org.commonjava.web.config.annotation.SectionName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.InputStream;

@SectionName( "httprox" )
@ApplicationScoped
public class HttproxConfig
    implements IndyConfigInfo
{

    private static final int DEFAULT_PORT = 8081;

    private static final boolean DEFAULT_ENABLED = false;

    private static final String DEFAULT_PROXY_REALM = "httprox";

    private static final boolean DEFAULT_SECURED = false;

    private static final String DEFAULT_TRACKING_TYPE = TrackingType.SUFFIX.name();

    private String proxyRealm;

    private Boolean enabled;

    private Boolean secured;

    private Integer port;

    private String trackingType;

    public TrackingType getTrackingType()
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Using configured tracking type: '{}'", trackingType );
        return TrackingType.valueOf( trackingType == null ? DEFAULT_TRACKING_TYPE : trackingType.toUpperCase() );
    }

    @ConfigName( "tracking.type" )
    public void setTrackingType( final String option )
    {
        this.trackingType = option;
    }

    public void setTrackingType( final TrackingType type )
    {
        this.trackingType = type.name();
    }

    public boolean isEnabled()
    {
        return enabled == null ? DEFAULT_ENABLED : enabled;
    }

    @ConfigName( "enabled" )
    public void setEnabled( final Boolean enabled )
    {
        this.enabled = enabled;
    }

    public boolean isSecured()
    {
        return secured == null ? DEFAULT_SECURED : secured;
    }

    @ConfigName( "secured" )
    public void setSecured( final Boolean secured )
    {
        this.secured = secured;
    }

    public Integer getPort()
    {
        return port == null ? DEFAULT_PORT : port;
    }

    @ConfigName( "port" )
    public void setPort( final Integer port )
    {
        this.port = port;
    }

    public String getProxyRealm()
    {
        return proxyRealm == null ? DEFAULT_PROXY_REALM : proxyRealm;
    }

    @ConfigName( "proxy.realm" )
    public void setProxyRealm( final String proxyRealm )
    {
        this.proxyRealm = proxyRealm;
    }

    @Override
    public String getDefaultConfigFileName()
    {
        return new File( IndyConfigInfo.CONF_INCLUDES_DIR, "httprox.conf" ).getPath();
    }

    @Override
    public InputStream getDefaultConfig()
    {
        return Thread.currentThread()
                     .getContextClassLoader()
                     .getResourceAsStream( "default-httprox.conf" );
    }
}
