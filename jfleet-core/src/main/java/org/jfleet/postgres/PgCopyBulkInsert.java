/**
 * Copyright 2017 Jerónimo López Bezanilla
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jfleet.postgres;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.stream.Stream;

import org.jfleet.BulkInsert;
import org.jfleet.EntityInfo;
import org.jfleet.JFleetException;
import org.jfleet.WrappedException;
import org.jfleet.common.ContentWriter;
import org.jfleet.common.ParallelContentWriter;
import org.jfleet.common.TransactionPolicy;
import org.jfleet.inspection.JpaEntityInspector;
import org.postgresql.copy.CopyManager;
import org.postgresql.jdbc.PgConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PgCopyBulkInsert<T> implements BulkInsert<T> {

    private static Logger logger = LoggerFactory.getLogger(PgCopyBulkInsert.class);
    private static final int DEFAULT_BATCH_SIZE = 10 * 1_024 * 1_024;

    private final EntityInfo entityInfo;
    private final String mainSql;
    private final int batchSize;
    private final boolean autocommit;
    private final boolean concurrent;

    public PgCopyBulkInsert(Class<T> clazz) {
        this(new Configuration<>(clazz));
    }

    public PgCopyBulkInsert(Configuration<T> config) {
        EntityInfo entityInfo = config.entityInfo;
        if (entityInfo == null) {
            JpaEntityInspector inspector = new JpaEntityInspector(config.clazz);
            entityInfo = inspector.inspect();
        }
        this.entityInfo = entityInfo;
        this.batchSize = config.batchSize;
        this.autocommit = config.autocommit;
        this.concurrent = config.concurrent;
        this.mainSql = new SqlBuilder(entityInfo).build();
        logger.debug("SQL Insert for {}: {}", entityInfo.getEntityClass().getName(), mainSql);
        logger.debug("Batch size: {} bytes", batchSize);
    }

    @Override
    public void insertAll(Connection conn, Stream<T> stream) throws JFleetException, SQLException {
        StdInContentBuilder contentBuilder = new StdInContentBuilder(entityInfo, batchSize, concurrent);
        CopyManager copyMng = getCopyManager(conn);
        try {
            TransactionPolicy txPolicy = TransactionPolicy.getTransactionPolicy(conn, autocommit);
            try {
                ContentWriter contentWriter = new PgCopyContentWriter(txPolicy, copyMng, mainSql);
                if (concurrent) {
                    contentWriter = new ParallelContentWriter(contentWriter);
                }
                Iterator<T> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    contentBuilder.add(iterator.next());
                    if (contentBuilder.isFilled()) {
                        logger.debug("Writing content");
                        contentWriter.writeContent(contentBuilder.getContent());
                        contentBuilder.reset();
                    }
                }
                logger.debug("Flushing content");
                contentWriter.writeContent(contentBuilder.getContent());
                contentWriter.waitForWrite();
                contentBuilder.reset();
            } finally {
                txPolicy.close();
            }
        } catch (WrappedException e) {
            e.rethrow();
        }
    }

    private CopyManager getCopyManager(Connection conn) throws SQLException {
        PgConnection unwrapped = conn.unwrap(PgConnection.class);
        return unwrapped.getCopyAPI();
    }

    public static class Configuration<T> {

        private Class<T> clazz;
        private EntityInfo entityInfo;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private boolean autocommit = true;
        private boolean concurrent = true;

        public Configuration(Class<T> clazz) {
            this.clazz = clazz;
        }

        public Configuration(EntityInfo entityInfo) {
            this.entityInfo = entityInfo;
        }

        public Configuration<T> batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Configuration<T> autocommit(boolean autocommit) {
            this.autocommit = autocommit;
            return this;
        }

        public Configuration<T> concurrent(boolean concurrent) {
            this.concurrent = concurrent;
            return this;
        }

    }

}
