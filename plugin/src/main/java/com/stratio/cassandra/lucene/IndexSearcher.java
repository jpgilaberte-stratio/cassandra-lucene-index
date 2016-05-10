/*
 * Copyright (C) 2014 Stratio (http://stratio.com)
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
package com.stratio.cassandra.lucene;

import static org.apache.cassandra.cql3.Operator.*;

import java.nio.*;
import java.util.*;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.index.*;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.utils.*;
import org.slf4j.*;

import com.stratio.cassandra.lucene.schema.*;
import com.stratio.cassandra.lucene.search.*;
import com.stratio.cassandra.lucene.service.*;
import com.stratio.cassandra.lucene.util.*;

/**
 * A {@link SecondaryIndexSearcher} for {@link Index}.
 *
 * @author Andres de la Pena {@literal <adelapena@stratio.com>}
 */
public class IndexSearcher extends SecondaryIndexSearcher {

    private static final Logger logger = LoggerFactory.getLogger(IndexSearcher.class);

    /** The name of the {@link IndexExpression} containing the last search {@link RowKey}. */
    public static final ByteBuffer AFTER = UTF8Type.instance.fromString("search_after_doc");

    private final RowService rowService;
    private final Schema schema;
    private final ByteBuffer indexedColumnName;

    /**
     * Returns a new {@code RowIndexSearcher}.
     *
     * @param indexManager the 2i manager
     * @param index the index
     * @param columns a set of columns
     * @param rowService the row service
     */
    public IndexSearcher(SecondaryIndexManager indexManager,
                         Index index,
                         Set<ByteBuffer> columns,
                         RowService rowService) {
        super(indexManager, columns);
        this.rowService = rowService;
        schema = rowService.getSchema();
        indexedColumnName = index.getColumnDefinition().name.bytes;
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> search(ExtendedFilter extendedFilter) {
        try {
            boolean distinct = isDistinct(extendedFilter);
            RowKey after = after(extendedFilter.getClause());
            long timestamp = extendedFilter.timestamp;
            int limit = extendedFilter.currentLimit();
            DataRange dataRange = extendedFilter.dataRange;
            List<IndexExpression> clause = extendedFilter.getClause();
            List<IndexExpression> expressions = filteredExpressions(clause);
            Search search = search(clause);
            return rowService.search(search, expressions, dataRange, limit, timestamp, after, distinct);
        } catch (Exception e) {
            throw new IndexException(e, "Error while searching: %s", extendedFilter);
        }
    }

    private boolean isDistinct(ExtendedFilter extendedFilter) {
        IDiskAtomFilter filter = extendedFilter.columnFilter(ByteBufferUtil.EMPTY_BYTE_BUFFER);
        if (filter instanceof SliceQueryFilter) {
            int compositesToGroup = ((SliceQueryFilter) filter).compositesToGroup;
            return compositesToGroup == SliceQueryFilter.IGNORE_TOMBSTONED_PARTITIONS;
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public boolean canHandleIndexClause(List<IndexExpression> clause) {
        for (IndexExpression expression : clause) {
            ByteBuffer columnName = expression.column;
            boolean sameName = indexedColumnName.equals(columnName);
            if (expression.operator.equals(EQ) && sameName) {
                return true;
            }
        }
        return false;
    }

    /** {@inheritDoc} */
    @Override
    public IndexExpression highestSelectivityPredicate(List<IndexExpression> clause, boolean trace) {
        for (IndexExpression expression : clause) {
            ByteBuffer columnName = expression.column;
            boolean sameName = indexedColumnName.equals(columnName);
            if (expression.operator.equals(EQ) && sameName) {
                return expression;
            }
        }
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void validate(IndexExpression indexExpression) throws InvalidRequestException {
        try {
            String json = UTF8Type.instance.compose(indexExpression.value);
            SearchBuilder.fromJson(json).build().validate(schema);
        } catch (Exception e) {
            logger.error("Error while validating search", e);
            throw new InvalidRequestException(e.getMessage());
        }
    }

    /**
     * Returns the {@link Search} contained in the specified list of {@link IndexExpression}s.
     *
     * @param clause A list of {@link IndexExpression}s.
     * @return The {@link Search} contained in the specified list of {@link IndexExpression}s.
     */
    private Search search(List<IndexExpression> clause) {
        IndexExpression indexedExpression = indexedExpression(clause);
        if (indexedExpression == null) {
            throw new IndexException("There is no index expression in the clause");
        }
        String json = UTF8Type.instance.compose(indexedExpression.value);
        return SearchBuilder.fromJson(json).build();
    }

    /**
     * Returns the {@link IndexExpression} relative to this index.
     *
     * @param clause A list of {@link IndexExpression}s.
     * @return The {@link IndexExpression} relative to this index.
     */
    private IndexExpression indexedExpression(List<IndexExpression> clause) {
        for (IndexExpression indexExpression : clause) {
            ByteBuffer columnName = indexExpression.column;
            if (indexedColumnName.equals(columnName)) {
                return indexExpression;
            }
        }
        return null;
    }

    /**
     * Returns the {@link IndexExpression} not relative to this index.
     *
     * @param clause A list of {@link IndexExpression}s.
     * @return The {@link IndexExpression} not relative to this index.
     */
    private List<IndexExpression> filteredExpressions(List<IndexExpression> clause) {
        List<IndexExpression> filteredExpressions = new ArrayList<>(clause.size());
        for (IndexExpression ie : clause) {
            ByteBuffer columnName = ie.column;
            if (!indexedColumnName.equals(columnName) && !AFTER.equals(columnName)) {
                filteredExpressions.add(ie);
            }
        }
        return filteredExpressions;
    }

    /** {@inheritDoc} */
    @Override
    public boolean requiresScanningAllRanges(List<IndexExpression> clause) {
        Search search = search(clause);
        return search.requiresFullScan();
    }

    /** {@inheritDoc} */
    @Override
    public List<Row> postReconciliationProcessing(List<IndexExpression> clause, List<Row> rows) {

        TimeCounter sortTime = TimeCounter.create().start();
        int startSize = rows.size();

        // Remove duplicates and sort
        Search search = search(clause);
        Comparator<Row> comparator = mapper().comparator(search);
        TreeSet<Row> set = new TreeSet<>(comparator);
        set.addAll(rows);
        List<Row> result = new ArrayList<>(set);

        int endSize = result.size();
        sortTime.stop();

        logger.debug("Processed {} rows to {} in {}", startSize, endSize, sortTime);

        return result;
    }

    /**
     * Returns the last search {@link RowKey} contained in the specified {@link IndexExpression}s, if any.
     *
     * @param expressions A list of {@link IndexExpression}s.
     * @return The last search {@link RowKey} contained in the specified {@link IndexExpression}s, if any.
     */
    private RowKey after(List<IndexExpression> expressions) {
        for (IndexExpression indexExpression : expressions) {
            ByteBuffer columnName = indexExpression.column;
            if (AFTER.equals(columnName)) {
                return mapper().rowKey(indexExpression.value);
            }
        }
        return null;
    }

    /**
     * Returns the used {@link RowMapper}.
     *
     * @return The used {@link RowMapper}.
     */
    public RowMapper mapper() {
        return rowService.mapper();
    }
}