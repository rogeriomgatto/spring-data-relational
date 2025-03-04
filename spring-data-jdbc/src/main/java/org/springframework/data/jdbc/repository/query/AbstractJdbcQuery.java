/*
 * Copyright 2020-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jdbc.repository.query;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.RowMapperResultSetExtractor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

/**
 * Base class for queries based on a repository method. It holds the infrastructure for executing a query and knows how
 * to execute a query based on the return type of the method. How to construct the query is left to subclasses.
 *
 * @author Jens Schauder
 * @author Kazuki Shimizu
 * @author Oliver Gierke
 * @author Maciej Walkowiak
 * @author Mark Paluch
 * @author Dennis Effing
 * @author Mikhail Polivakha
 * @since 2.0
 */
public abstract class AbstractJdbcQuery implements RepositoryQuery {

	private final JdbcQueryMethod queryMethod;
	private final NamedParameterJdbcOperations operations;

	/**
	 * Creates a new {@link AbstractJdbcQuery} for the given {@link JdbcQueryMethod} and
	 * {@link NamedParameterJdbcOperations}.
	 *
	 * @param queryMethod must not be {@literal null}.
	 * @param operations must not be {@literal null}.
	 */
	AbstractJdbcQuery(JdbcQueryMethod queryMethod, NamedParameterJdbcOperations operations) {

		Assert.notNull(queryMethod, "Query method must not be null");
		Assert.notNull(operations, "NamedParameterJdbcOperations must not be null");

		this.queryMethod = queryMethod;
		this.operations = operations;
	}

	@Override
	public JdbcQueryMethod getQueryMethod() {
		return queryMethod;
	}

	/**
	 * Creates a {@link JdbcQueryExecution} given a {@link JdbcQueryMethod}, and ac{@link ResultSetExtractor} or a
	 * {@link RowMapper}. Prefers the given {@link ResultSetExtractor} over {@link RowMapper}.
	 *
	 * @param queryMethod must not be {@literal null}.
	 * @param extractor must not be {@literal null}.
	 * @param rowMapper must not be {@literal null}.
	 * @return a JdbcQueryExecution appropriate for {@literal queryMethod}. Guaranteed to be not {@literal null}.
	 * @deprecated use {@link #createReadingQueryExecution(ResultSetExtractor, RowMapper)} instead.
	 */
	@Deprecated(since = "3.1", forRemoval = true)
	// a better name would be createReadingQueryExecution
	protected JdbcQueryExecution<?> getQueryExecution(JdbcQueryMethod queryMethod,
			@Nullable ResultSetExtractor<?> extractor, RowMapper<?> rowMapper) {
		return createReadingQueryExecution(extractor, rowMapper);
	}

	/**
	 * Creates a {@link JdbcQueryExecution} given a {@link ResultSetExtractor} or a {@link RowMapper}. Prefers the given
	 * {@link ResultSetExtractor} over {@link RowMapper}.
	 *
	 * @param extractor must not be {@literal null}.
	 * @param rowMapper must not be {@literal null}.
	 * @return a JdbcQueryExecution appropriate for {@literal queryMethod}. Guaranteed to be not {@literal null}.
	 */
	protected JdbcQueryExecution<?> createReadingQueryExecution(@Nullable ResultSetExtractor<?> extractor,
			RowMapper<?> rowMapper) {

		if (getQueryMethod().isCollectionQuery()) {
			return extractor != null ? createSingleReadingQueryExecution(extractor) : collectionQuery(rowMapper);
		}

		if (getQueryMethod().isStreamQuery()) {
			return extractor != null ? createSingleReadingQueryExecution(extractor) : streamQuery(rowMapper);
		}

		return extractor != null ? createSingleReadingQueryExecution(extractor) : singleObjectQuery(rowMapper);
	}

	protected JdbcQueryExecution<Object> createModifyingQueryExecutor() {

		return (query, parameters) -> {

			int updatedCount = operations.update(query, parameters);
			Class<?> returnedObjectType = queryMethod.getReturnedObjectType();

			return (returnedObjectType == boolean.class || returnedObjectType == Boolean.class) //
					? updatedCount != 0 //
					: updatedCount;
		};
	}

	JdbcQueryExecution<Object> singleObjectQuery(RowMapper<?> rowMapper) {

		return (query, parameters) -> {
			try {
				return operations.queryForObject(query, parameters, rowMapper);
			} catch (EmptyResultDataAccessException e) {
				return null;
			}
		};
	}

	<T> JdbcQueryExecution<List<T>> collectionQuery(RowMapper<T> rowMapper) {
		return createSingleReadingQueryExecution(new RowMapperResultSetExtractor<>(rowMapper));
	}

	/**
	 * Obtain the result type to read from {@link ResultProcessor}.
	 *
	 * @param resultProcessor the {@link ResultProcessor} used to determine the result type. Must not be {@literal null}.
	 * @return the type that should get loaded from the database before it gets converted into the actual return type of a
	 *         method. Guaranteed to be not {@literal null}.
	 */
	protected Class<?> resolveTypeToRead(ResultProcessor resultProcessor) {

		ReturnedType returnedType = resultProcessor.getReturnedType();

		if (returnedType.getReturnedType().isAssignableFrom(returnedType.getDomainType())) {
			return returnedType.getDomainType();
		}
		// Slight deviation from R2DBC: Allow direct mapping into DTOs
		return returnedType.isProjecting() && returnedType.getReturnedType().isInterface() ? returnedType.getDomainType()
				: returnedType.getReturnedType();
	}

	private <T> JdbcQueryExecution<Stream<T>> streamQuery(RowMapper<T> rowMapper) {
		return (query, parameters) -> operations.queryForStream(query, parameters, rowMapper);
	}

	private <T> JdbcQueryExecution<T> createSingleReadingQueryExecution(ResultSetExtractor<T> resultSetExtractor) {
		return (query, parameters) -> operations.query(query, parameters, resultSetExtractor);
	}

	/**
	 * Factory to create a {@link RowMapper} for a given class.
	 *
	 * @since 2.3
	 */
	public interface RowMapperFactory {
		RowMapper<Object> create(Class<?> result);
	}

	/**
	 * Delegating {@link RowMapper} that reads a row into {@code T} and converts it afterwards into {@code Object}.
	 *
	 * @param <T>
	 * @since 2.3
	 */
	protected static class ConvertingRowMapper<T> implements RowMapper<Object> {

		private final RowMapper<T> delegate;
		private final Converter<Object, Object> converter;

		public ConvertingRowMapper(RowMapper<T> delegate, Converter<Object, Object> converter) {
			this.delegate = delegate;
			this.converter = converter;
		}

		@Override
		public Object mapRow(ResultSet rs, int rowNum) throws SQLException {

			T object = delegate.mapRow(rs, rowNum);

			return object == null ? null : converter.convert(object);
		}
	}
}
