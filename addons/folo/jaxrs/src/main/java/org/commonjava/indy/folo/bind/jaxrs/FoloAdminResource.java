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
package org.commonjava.indy.folo.bind.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.folo.ctl.FoloAdminController;
import org.commonjava.indy.folo.data.FoloContentException;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.io.File;

import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithJsonEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.throwError;
import static org.commonjava.indy.util.ApplicationContent.application_zip;

@Api( value = "FOLO Tracking Record Access",
      description = "Manages FOLO tracking records." )
@Path( "/api/folo/admin" )
@ApplicationScoped
public class FoloAdminResource
        implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private IndyObjectMapper objectMapper;

    @Inject
    private FoloAdminController controller;

    @Inject
    private ContentController contentController;

    @ApiOperation(
            "Retrieve the content referenced in a tracking record as a ZIP-compressed Maven repository directory." )
    @ApiResponses( { @ApiResponse( code = 200, response = File.class, message = "ZIP repository content" ),
                           @ApiResponse( code = 404, message = "No such tracking record" ) } )
    @Path( "/{id}/repo/zip" )
    @GET
    @Produces( application_zip )
    public File getZipRepository( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) String id )
    {
        try
        {
            File zip = controller.renderRepositoryZip( id );
            return zip;
            //
            //            final Response.ResponseBuilder builder = Response.ok( zip );
            //            return setInfoHeaders( builder, zip, false, application_zip ).build();
        }
        catch ( IndyWorkflowException e)
        {
            throwError( e );
        }

        return null;
    }

    @ApiOperation( "Alias of /{id}/record, returns the tracking record for the specified key" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/report" )
    @GET
    public Response getReport( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                               @Context final UriInfo uriInfo )
    {
        Response response;
        try
        {
            //            final String baseUrl = uriInfo.getAbsolutePathBuilder()
            final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();
            //                                          .path( ContentAccessHandler.class )
            //                                          .build( st.singularEndpointName(), name )
            //                                          .toString();

            final TrackedContentDTO report = controller.renderReport( id, baseUrl );

            if ( report == null )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                response = formatOkResponseWithJsonEntity( report, objectMapper );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error(
                    String.format( "Failed to serialize tracking report for: %s. Reason: %s", id, e.getMessage() ), e );

            response = formatResponse( e );
        }

        return response;
    }

    @ApiOperation(
            "Explicitly setup a new tracking record for the specified key, to prevent 404 if the record is never used." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Tracking record was created",
                                   response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @PUT
    public Response initRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                                @Context final UriInfo uriInfo )
    {
        Response.ResponseBuilder rb = Response.created( uriInfo.getRequestUri() );
        // [jdcasey] I think there are still use cases where this makes sense...un-deprecating it.
        //                                              .entity( "Tracking records no longer require initialization." );
        //        ResponseUtils.markDeprecated( rb, "NONE" );
        return rb.build();
    }

    @ApiOperation( "Seal the tracking record for the specified key, to prevent further content logging" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @POST
    public Response sealRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                                @Context final UriInfo uriInfo )
    {
        TrackedContentDTO record = controller.seal( id );
        if ( record == null )
        {
            return Response.status( Status.NOT_FOUND ).build();
        }
        else
        {
            return Response.ok().build();
        }
    }

    @ApiOperation( "Alias of /{id}/record, returns the tracking record for the specified key" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @GET
    public Response getRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id )
    {
        Response response;
        try
        {
            final TrackedContentDTO record = controller.getRecord( id );
            if ( record == null )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                response = formatOkResponseWithJsonEntity( record, objectMapper );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( String.format( "Failed to retrieve tracking report for: %s. Reason: %s", id, e.getMessage() ),
                          e );

            response = formatResponse( e );
        }

        return response;
    }

    @Path( "/{id}/record" )
    @DELETE
    public Response clearRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id )
    {
        Response response;
        try
        {
            controller.clearRecord( id );
            response = Response.status( Status.NO_CONTENT ).build();
        }
        catch ( FoloContentException e )
        {
            response = formatResponse( e );
        }

        return response;
    }

}
