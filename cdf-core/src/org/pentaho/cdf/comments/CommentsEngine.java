/*!
 * Copyright 2002 - 2015 Webdetails, a Pentaho company. All rights reserved.
 *
 * This software was developed by Webdetails and is provided under the terms
 * of the Mozilla Public License, Version 2.0, or any later version. You may not use
 * this file except in compliance with the license. If you need a copy of the license,
 * please go to http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
 *
 * Software distributed under the Mozilla Public License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied. Please refer to
 * the license for the specific language governing your rights and limitations.
 */

package org.pentaho.cdf.comments;

import java.text.SimpleDateFormat;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hibernate.Query;
import org.hibernate.Session;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pentaho.cdf.InvalidCdfOperationException;
import org.pentaho.cdf.PluginHibernateException;
import org.pentaho.cdf.environment.CdfEngine;
import org.pentaho.cdf.utils.JsonUtil;
import org.pentaho.cdf.utils.PluginHibernateUtil;

import pt.webdetails.cpf.Util;
import pt.webdetails.cpf.repository.api.IBasicFile;

public class CommentsEngine {
  private static final Log logger = LogFactory.getLog( CommentsEngine.class );
  private static CommentsEngine instance;

  public static enum Operation {
    ADD( "ADD" ), DELETE( "DELETE" ), ARCHIVE( "ARCHIVE" ), LIST( "LIST" ), UNKNOWN( "UNKNOWN" );

    @SuppressWarnings( "unused" )
    private final String operation;

    private Operation( String operation ) {
      this.operation = operation;
    }

    public static Operation get( String operation ) {
      try {
        return valueOf( operation.toUpperCase() );
      } catch ( Exception e ) {
        // do nothing
      }
      return UNKNOWN;
    }
  }

  private static final SimpleDateFormat format = new SimpleDateFormat( "yyyy-MM-dd HH:mm:ss" );

  public static CommentsEngine getInstance() {
    if ( instance == null ) {
      PluginHibernateUtil.initialize();
      instance = new CommentsEngine();
    }
    return instance;
  }

  public CommentsEngine() {
    try {
      logger.info( "Creating CommentsEngine instance" );
      initialize();
    } catch ( PluginHibernateException ex ) {
      logger.fatal( "Could not create CommentsEngine: " + Util.getExceptionDescription( ex ) ); //$NON-NLS-1$
      return;
    }
  }

  public JSONObject add( String page, String comment, String user ) throws JSONException, InvalidCdfOperationException,
    PluginHibernateException {

    if ( StringUtils.isEmpty( page ) || StringUtils.isEmpty( comment ) ) {
      logger.error( "Parameters 'page' and 'comment' are not optional" );
      throw new InvalidCdfOperationException( "Page cannot be null" );
    }

    logger.debug( "Adding comment" );

    CommentEntry commentEntry = new CommentEntry( page, user, comment );

    Session session = getSession();
    session.beginTransaction();
    session.save( commentEntry );
    session.flush();
    session.getTransaction().commit();

    return JsonUtil.makeJsonSuccessResponse( commentToJson( commentEntry, user ) );
  }

  public JSONObject list( String page, int firstResult, int maxResults, boolean isDeleted, boolean isArchived,
      String user ) throws JSONException, InvalidCdfOperationException, PluginHibernateException {
    logger.debug( "Listing messages" );

    if ( StringUtils.isEmpty( page ) ) {
      logger.error( "Parameter 'page' is not optional" );
      throw new InvalidCdfOperationException( "Page cannot be null" );
    }

    final String queryName;

    if ( isDeleted || isArchived ) {
      queryName = "getCommentsByPageWhere";
    } else {
      queryName = "getCommentsByPage"; // default query
    }

    logger.debug( "Adding comment" );
    Session session = getSession();

    Query query = session.getNamedQuery( "org.pentaho.cdf.comments.CommentEntry." + queryName );
    query.setString( "page", page );

    if ( isDeleted || isArchived ) {
      query.setBoolean( "deleted", isDeleted );
      query.setBoolean( "archived", isArchived );
    }

    query.setFirstResult( firstResult < 0 ? 0 : firstResult ); // default 0
    query.setMaxResults( maxResults < 0 ? 20 : maxResults ); // default 20

    // Get it and build the tree

    @SuppressWarnings( "unchecked" )
    List<CommentEntry> comments = query.list();

    JSONArray jsonArray = new JSONArray();
    for ( CommentEntry comment : comments ) {
      JSONObject commentJson = commentToJson( comment, user );
      jsonArray.put( commentJson );
    }

    return JsonUtil.makeJsonSuccessResponse( jsonArray );
  }

  public JSONObject delete( int commentId, boolean status, String user, boolean isAdmin ) throws JSONException,
    PluginHibernateException {
    logger.debug( "Deleting comment " + commentId );
    return changeCommentStatus( Operation.DELETE, commentId, status, user, isAdmin );
  }

  public JSONObject archive( int commentId, boolean status, String user, boolean isAdmin ) throws JSONException,
    PluginHibernateException {
    logger.debug( "Archiving comment " + commentId );
    return changeCommentStatus( Operation.ARCHIVE, commentId, status, user, isAdmin );
  }

  @SuppressWarnings( "incomplete-switch" )
  private JSONObject changeCommentStatus( Operation operation, int commentId, boolean status, String user,
      boolean isAdmin ) throws JSONException, PluginHibernateException {
    Session session = getSession();
    session.beginTransaction();
    CommentEntry comment = (CommentEntry) session.load( CommentEntry.class, commentId );

    Boolean isUser = comment.getUser().equals( user );

    if ( !isUser && !isAdmin ) {
      return JsonUtil.makeJsonErrorResponse( "Operation not authorized: not comment owner or administrator", false );
    }

    // this switch-case does not need enum's full spectrum
    switch ( operation ) {
      case DELETE:
        comment.setDeleted( status );
        break;
      case ARCHIVE:
        comment.setArchived( status );
        break;
    }
    session.save( comment );
    session.getTransaction().commit();

    return JsonUtil.makeJsonSuccessResponse( commentToJson( comment, user ) );
  }

  private JSONObject commentToJson( CommentEntry comment, String user ) throws JSONException {
    JSONObject commentJson = new JSONObject();
    commentJson.put( "id", comment.getCommentId() );
    commentJson.put( "user", comment.getUser() );
    commentJson.put( "page", comment.getPage() );
    commentJson.put( "createdOn", format.format( comment.getCreatedDate() ) );
    commentJson.put( "elapsedMinutes", comment.getMinutesSinceCreation() );
    commentJson.put( "comment", comment.getComment() );
    commentJson.put( "isMe", comment.getUser().equals( user ) );
    commentJson.put( "isDeleted", comment.isDeleted() );
    commentJson.put( "isArchived", comment.isArchived() );
    return commentJson;
  }

  private Session getSession() throws PluginHibernateException {
    return PluginHibernateUtil.getSession();
  }

  private void initialize() throws PluginHibernateException {

    try {
      Thread.currentThread().setContextClassLoader( this.getClass().getClassLoader() );

      // Get comments hbm file
      IBasicFile commentsHbmFile =
          CdfEngine.getEnvironment().getHibernateConfigurations().getCommentsConfigurationFile();

      if ( commentsHbmFile == null || commentsHbmFile.getContents() == null ) {
        logger.error( "Unable to find comments hbm file" );
        throw new PluginHibernateException( "Unable to find comments hbm file", null );
      }

      // Close session and rebuild
      PluginHibernateUtil.closeSession();
      PluginHibernateUtil.getConfiguration().addInputStream( commentsHbmFile.getContents() );
      PluginHibernateUtil.rebuildSessionFactory();

    } catch ( Exception e ) {
      logger.error( "Unable to initialize comments engine", e );
      throw new PluginHibernateException( "Unable to initialize comments engine", e );
    }
  }
}
