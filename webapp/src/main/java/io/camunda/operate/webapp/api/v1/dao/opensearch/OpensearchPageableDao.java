/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. Licensed under a proprietary license.
 * See the License.txt file for more information. You may not use this file
 * except in compliance with the proprietary license.
 */
package io.camunda.operate.webapp.api.v1.dao.opensearch;

import io.camunda.operate.store.opensearch.client.sync.RichOpenSearchClient;
import io.camunda.operate.util.CollectionUtil;
import io.camunda.operate.webapp.api.v1.entities.Query;
import io.camunda.operate.webapp.api.v1.entities.Results;
import io.camunda.operate.webapp.api.v1.exceptions.ServerException;
import io.camunda.operate.webapp.opensearch.OpensearchQueryDSLWrapper;
import io.camunda.operate.webapp.opensearch.OpensearchRequestDSLWrapper;
import org.opensearch.client.opensearch._types.SortOrder;
import org.opensearch.client.opensearch.core.SearchRequest;
import org.opensearch.client.opensearch.core.search.Hit;
import org.opensearch.client.opensearch.core.search.HitsMetadata;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public abstract class OpensearchPageableDao<T> extends OpensearchDao {

  protected OpensearchPageableDao(OpensearchQueryDSLWrapper queryDSLWrapper, OpensearchRequestDSLWrapper requestDSLWrapper, RichOpenSearchClient richOpenSearchClient) {
    super(queryDSLWrapper, requestDSLWrapper, richOpenSearchClient);
  }

  public Results<T> search(Query<T> query) {
    var request = buildSearchRequest(query);

    buildSorting(query, getUniqueSortKey(), request);
    buildFiltering(query, request);
    buildPaging(query, request);

    try {
      HitsMetadata<T> results = richOpenSearchClient.doc().search(request, getModelClass()).hits();

      return formatHitsIntoResults(results);
    } catch (Exception e) {
      throw new ServerException("Error in reading incidents", e);
    }
  }

  protected SearchRequest.Builder buildSearchRequest(Query<T> query) {
    return requestDSLWrapper.searchRequestBuilder(getIndexName())
        .query(queryDSLWrapper.withTenantCheck(queryDSLWrapper.matchAll()));
  }

  protected abstract String getUniqueSortKey();

  protected abstract Class<T> getModelClass();

  protected abstract String getIndexName();

  protected void buildSorting(Query<T> query, String uniqueSortKey, SearchRequest.Builder request) {
    List<Query.Sort> sorts = query.getSort();
    if (sorts != null) {
      sorts.forEach(sort -> {
        Query.Sort.Order order = sort.getOrder();
        if (order.equals(Query.Sort.Order.DESC)) {
          request.sort(queryDSLWrapper.sortOptions(sort.getField(), SortOrder.Desc));
        } else {
          // if not specified always assume ASC order
          request.sort(queryDSLWrapper.sortOptions(sort.getField(), SortOrder.Asc));
        }
      });
    }
    request.sort(queryDSLWrapper.sortOptions(uniqueSortKey, SortOrder.Asc));
  }

  protected void buildPaging(Query<T> query, SearchRequest.Builder request) {
    Object[] searchAfter = query.getSearchAfter();
    if (searchAfter != null) {
      request.searchAfter(CollectionUtil.toSafeListOfStrings(searchAfter));
    }
    request.size(query.getSize());
  }

  protected abstract void buildFiltering(Query<T> query, SearchRequest.Builder request);

  protected Results<T> formatHitsIntoResults(HitsMetadata<T> results) {
    List<Hit<T>> hits = results.hits();

    if (!hits.isEmpty()) {
      List<T> items = hits.stream().map(this::transformHitToItem)
          .filter(Objects::nonNull).collect(Collectors.toList());

      List<String> sortValues = hits.get(hits.size() - 1).sort();

      return new Results<T>()
          .setTotal(results.total().value())
          .setItems(items)
          .setSortValues(sortValues.toArray());
    } else {
      return new Results<T>()
          .setTotal(results.total().value());
    }
  }

  protected T transformHitToItem(Hit<T> hit) {
    return hit.source();
  }
}
