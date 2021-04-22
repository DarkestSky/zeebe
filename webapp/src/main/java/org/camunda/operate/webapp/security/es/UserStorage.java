/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a commercial license.
 * You may not use this file except in compliance with the commercial license.
 */
package org.camunda.operate.webapp.security.es;

import java.io.IOException;
import org.camunda.operate.util.ElasticsearchUtil;
import org.camunda.operate.entities.UserEntity;
import org.camunda.operate.webapp.es.reader.AbstractReader;
import org.camunda.operate.exceptions.OperateRuntimeException;
import org.camunda.operate.schema.indices.UserIndex;
import org.camunda.operate.webapp.rest.exception.NotFoundException;
import org.camunda.operate.webapp.security.OperateURIs;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;


@Component
@Profile("!" + OperateURIs.LDAP_AUTH_PROFILE + " & ! " + OperateURIs.SSO_AUTH_PROFILE)
@DependsOn("schemaStartup")
public class UserStorage extends AbstractReader {

  private static final Logger logger = LoggerFactory.getLogger(UserStorage.class);

  private static final XContentType XCONTENT_TYPE = XContentType.JSON;

  @Autowired
  private UserIndex userIndex;

  public UserEntity getByName(String username) {
    final SearchRequest searchRequest = new SearchRequest(userIndex.getAlias())
        .source(new SearchSourceBuilder()
          .query(QueryBuilders.termQuery(UserIndex.USERNAME, username)));
      try {
        final SearchResponse response = esClient.search(searchRequest,RequestOptions.DEFAULT);
        if (response.getHits().getTotalHits().value == 1) {
          return ElasticsearchUtil.fromSearchHit(response.getHits().getHits()[0].getSourceAsString(), objectMapper, UserEntity.class);
        } else if (response.getHits().getTotalHits().value > 1) {
          throw new NotFoundException(String.format("Could not find unique user with username '%s'.", username));
        } else {
          throw new NotFoundException(String.format("Could not find user with username '%s'.", username));
        }
      } catch (IOException e) {
        final String message = String.format("Exception occurred, while obtaining the user: %s", e.getMessage());
        throw new OperateRuntimeException(message, e);
      }
  }

  public void create(UserEntity user) {
    try {
      IndexRequest request = new IndexRequest(userIndex.getFullQualifiedName()).id(user.getId())
          .source(userEntityToJSONString(user), XCONTENT_TYPE);
      esClient.index(request,RequestOptions.DEFAULT);
    } catch (Exception t) {
      logger.error("Could not create user with username {}", user.getUsername(), t);
    }
  }

  protected String userEntityToJSONString(UserEntity aUser) throws JsonProcessingException {
    return objectMapper.writeValueAsString(aUser);
  }

}
