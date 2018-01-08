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
package org.jfleet.mysql;

import static org.jfleet.mysql.MySqlTransactionPolicy.getTransactionPolicy;

import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.stream.Stream;

import org.jfleet.BulkInsert;
import org.jfleet.EntityInfo;
import org.jfleet.JFleetException;
import org.jfleet.JpaEntityInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mysql.jdbc.Statement;

public class LoadDataBulkInsert<T> implements BulkInsert<T> {

    private static Logger logger = LoggerFactory.getLogger(LoadDataBulkInsert.class);
    private final static long DEFAULT_BATCH_SIZE = 50 * 1_024 * 1_024;

    private final EntityInfo entityInfo;
    private final String mainSql;
    private final long batchSize;
    private final boolean longTransaction;
    private final boolean errorOnMissingRow;
    private final Charset encoding = Charset.forName("UTF-8");

    public LoadDataBulkInsert(Class<T> clazz) {
        this(clazz, DEFAULT_BATCH_SIZE, false, false);
    }

    public LoadDataBulkInsert(Class<T> clazz, long batchSize, boolean longTransaction, boolean errorOnMissingRow) {
        JpaEntityInspector inspector = new JpaEntityInspector(clazz);
        this.entityInfo = inspector.inspect();
        this.batchSize = batchSize;
        this.longTransaction = longTransaction;
        this.errorOnMissingRow = errorOnMissingRow;
        SqlBuilder sqlBuiler = new SqlBuilder(entityInfo);
        mainSql = sqlBuiler.build();
        logger.debug("SQL Insert for {}: {}", entityInfo.getEntityClass().getName(), mainSql);
        logger.debug("Batch size: {} bytes", batchSize);
    }

    @Override
    public void insertAll(Connection conn, Stream<T> stream) throws JFleetException, SQLException {
        FileContentBuilder contentBuilder = new FileContentBuilder(entityInfo);
        MySqlTransactionPolicy txPolicy = getTransactionPolicy(conn, longTransaction, errorOnMissingRow);
        try (Statement stmt = getStatementForLoadLocal(conn)) {
            LoadDataContentWriter contentWriter = new LoadDataContentWriter(stmt, txPolicy, mainSql, encoding);
            Iterator<T> iterator = stream.iterator();
            while (iterator.hasNext()) {
                contentBuilder.add(iterator.next());
                if (contentBuilder.getContentSize() > batchSize) {
                    logger.debug("Writing content");
                    contentWriter.writeContent(contentBuilder);
                }
            }
            logger.debug("Flushing content");
            contentWriter.writeContent(contentBuilder);
        } finally {
            txPolicy.close();
        }
    }

    private Statement getStatementForLoadLocal(Connection conn) throws SQLException {
        com.mysql.jdbc.Connection unwrapped = null;
        try {
            unwrapped = conn.unwrap(com.mysql.jdbc.Connection.class);
            unwrapped.setAllowLoadLocalInfile(true);
        } catch (SQLException e) {
            throw new RuntimeException("Incorrect Connection type. Expected com.mysql.jdbc.Connection");
        }
        return (Statement) unwrapped.createStatement();
    }

}
