/*
 * Copyright 2021 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.storage.server.leveldb;

import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.asColumnEntry;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.asOptionalColumnEntry;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.deserializeKey;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.getColumnKey;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.getKeyAfterColumn;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.getVariableKey;
import static tech.pegasys.teku.storage.server.leveldb.LevelDbUtils.isFromColumn;

import com.google.errorprone.annotations.MustBeClosed;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.plugin.services.metrics.Counter;
import org.hyperledger.besu.plugin.services.metrics.MetricCategory;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBException;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.WriteBatch;
import tech.pegasys.teku.storage.server.DatabaseStorageException;
import tech.pegasys.teku.storage.server.ShuttingDownException;
import tech.pegasys.teku.storage.server.rocksdb.core.ColumnEntry;
import tech.pegasys.teku.storage.server.rocksdb.core.RocksDbAccessor;
import tech.pegasys.teku.storage.server.rocksdb.schema.RocksDbColumn;
import tech.pegasys.teku.storage.server.rocksdb.schema.RocksDbVariable;

/**
 * Implements {@link RocksDbAccessor} but actually uses LevelDB to store the data.
 *
 * <p>The primary difference between RocksDB and LevelDB is that LevelDB doesn't support columns. To
 * support the same interface and generally make it easy to work with the database, columns are
 * implemented by prefixing all keys with the column ID. As a result, each column has a unique
 * keyspace and column entries are sorted together. Iterators can then be used to walk through the
 * column with the only caveat being that we need to manually check if the next entry is from a
 * different column and stop iterating.
 */
public class LevelDbInstance implements RocksDbAccessor {
  private static final Logger LOG = LogManager.getLogger();

  private final Set<LevelDbTransaction> openTransactions = new HashSet<>();
  private final Set<DBIterator> openIterators = new HashSet<>();
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final DB db;
  private final Counter openedTransactionsCounter;
  private final Counter closedTransactionsCounter;
  private final Counter openedIteratorsCounter;
  private final Counter closedIteratorsCounter;

  public LevelDbInstance(
      final DB db, final MetricsSystem metricsSystem, MetricCategory metricCategory) {
    this.db = db;
    openedTransactionsCounter =
        metricsSystem.createCounter(
            metricCategory, "opened_transactions_total", "Total number of opened transactions");
    closedTransactionsCounter =
        metricsSystem.createCounter(
            metricCategory, "closed_transactions_total", "Total number of closed transactions");
    openedIteratorsCounter =
        metricsSystem.createCounter(
            metricCategory, "opened_iterators_total", "Total number of opened iterators");
    closedIteratorsCounter =
        metricsSystem.createCounter(
            metricCategory, "closed_iterators_total", "Total number of closed iterators");
  }

  @Override
  public <T> Optional<T> get(final RocksDbVariable<T> variable) {
    assertOpen();
    return Optional.ofNullable(db.get(getVariableKey(variable)))
        .map(variable.getSerializer()::deserialize);
  }

  @Override
  public <K, V> Optional<V> get(final RocksDbColumn<K, V> column, final K key) {
    assertOpen();
    return Optional.ofNullable(db.get(getColumnKey(column, key)))
        .map(column.getValueSerializer()::deserialize);
  }

  @Override
  public <K, V> Map<K, V> getAll(final RocksDbColumn<K, V> column) {
    return withIterator(
        iterator -> {
          iterator.seek(column.getId().toArrayUnsafe());
          final Map<K, V> values = new HashMap<>();
          while (iterator.hasNext()) {
            final Map.Entry<byte[], byte[]> entry = iterator.next();
            if (!isFromColumn(column, entry.getKey())) {
              break;
            }
            values.put(
                deserializeKey(column, entry.getKey()),
                column.getValueSerializer().deserialize(entry.getValue()));
          }
          return values;
        });
  }

  @Override
  public <K, V> Optional<ColumnEntry<K, V>> getFloorEntry(
      final RocksDbColumn<K, V> column, final K key) {
    return withIterator(
        iterator -> {
          final byte[] matchingKey = getColumnKey(column, key);
          iterator.seek(matchingKey);
          if (!iterator.hasNext()) {
            return getLastDatabaseEntryIfFromColumn(column, iterator)
                .map(entry -> asColumnEntry(column, entry));
          }

          // Check if an exact match was found
          final Map.Entry<byte[], byte[]> next = iterator.peekNext();
          if (Arrays.equals(next.getKey(), matchingKey)) {
            return Optional.of(
                ColumnEntry.create(key, column.getValueSerializer().deserialize(next.getValue())));
          }

          // Otherwise check if the previous item is in our column.
          if (iterator.hasPrev()) {
            final Map.Entry<byte[], byte[]> prev = iterator.peekPrev();
            if (isFromColumn(column, prev.getKey())) {
              return asOptionalColumnEntry(column, prev);
            }
          }
          // No entry in this column at or prior to the specified key
          return Optional.empty();
        });
  }

  @Override
  public <K, V> Optional<ColumnEntry<K, V>> getFirstEntry(final RocksDbColumn<K, V> column) {
    return withIterator(
        iterator -> {
          iterator.seek(column.getId().toArrayUnsafe());
          if (iterator.hasNext()) {
            return Optional.of(iterator.peekNext())
                .filter(entry -> isFromColumn(column, entry.getKey()))
                .map(entry -> asColumnEntry(column, entry));
          }
          return Optional.empty();
        });
  }

  @Override
  public <K, V> Optional<K> getLastKey(final RocksDbColumn<K, V> column) {
    return withIterator(
        iterator -> {
          final byte[] keyAfterColumn = getKeyAfterColumn(column);
          iterator.seek(keyAfterColumn);
          if (!iterator.hasNext()) {
            return getLastDatabaseEntryIfFromColumn(column, iterator)
                .map(entry -> deserializeKey(column, entry.getKey()));
          }
          if (iterator.hasPrev()) {
            return Optional.of(iterator.peekPrev())
                .filter(entry -> isFromColumn(column, entry.getKey()))
                .map(entry -> deserializeKey(column, entry.getKey()));
          }
          return Optional.empty();
        });
  }

  /**
   * LevelDB iterators have a slightly odd property where if you seek to an item that is after the
   * last entry in the database, the iterator is invalid (no next or previous item).
   *
   * <p>We work around that by explicitly seeking the last item, and checking if it's part of the
   * column we're interested in.
   */
  private <K, V> Optional<Map.Entry<byte[], byte[]>> getLastDatabaseEntryIfFromColumn(
      final RocksDbColumn<K, V> column, final DBIterator iterator) {
    iterator.seekToLast();
    if (!iterator.hasNext()) {
      // Empty database
      return Optional.empty();
    }
    return Optional.of(iterator.peekNext()).filter(entry -> isFromColumn(column, entry.getKey()));
  }

  @Override
  @MustBeClosed
  public <K, V> Stream<ColumnEntry<K, V>> stream(final RocksDbColumn<K, V> column) {
    // Note that the "to" key is actually after the end of the column and iteration is inclusive.
    // Fortunately, we know that the "to" key can't exist because it is just the column ID with an
    // empty item key and empty item keys are not allowed.
    return stream(column, column.getId().toArrayUnsafe(), getKeyAfterColumn(column));
  }

  @Override
  @MustBeClosed
  public <K extends Comparable<K>, V> Stream<ColumnEntry<K, V>> stream(
      final RocksDbColumn<K, V> column, final K from, final K to) {
    final byte[] fromBytes = getColumnKey(column, from);
    final byte[] toBytes = getColumnKey(column, to);
    return stream(column, fromBytes, toBytes);
  }

  @MustBeClosed
  private <K, V> Stream<ColumnEntry<K, V>> stream(
      final RocksDbColumn<K, V> column, final byte[] fromBytes, final byte[] toBytes) {
    assertOpen();
    final DBIterator iterator = createIterator();
    iterator.seek(fromBytes);
    return new LevelDbIterator<>(this, iterator, column, toBytes)
        .toStream()
        .onClose(() -> closeIterator(iterator));
  }

  @Override
  @MustBeClosed
  public synchronized RocksDbTransaction startTransaction() {
    assertOpen();
    openedTransactionsCounter.inc();
    final WriteBatch writeBatch = db.createWriteBatch();
    final LevelDbTransaction transaction = new LevelDbTransaction(this, db, writeBatch);
    openTransactions.add(transaction);
    return transaction;
  }

  @Override
  public synchronized void close() throws Exception {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    new ArrayList<>(openIterators).forEach(this::closeIterator);
    new ArrayList<>(openTransactions).forEach(LevelDbTransaction::close);
    db.close();
  }

  private synchronized <T> T withIterator(final Function<DBIterator, T> action) {
    assertOpen();
    openedIteratorsCounter.inc();
    final DBIterator iterator = createIterator();
    try {
      return action.apply(iterator);
    } catch (final DBException e) {
      throw DatabaseStorageException.unrecoverable("Failed to create iterator", e);
    } finally {
      closeIterator(iterator);
    }
  }

  private synchronized void closeIterator(final DBIterator iterator) {
    if (!openIterators.remove(iterator)) {
      return;
    }
    closedIteratorsCounter.inc();
    try {
      iterator.close();
    } catch (final IOException e) {
      LOG.error("Failed to close leveldb iterator", e);
    }
  }

  synchronized void onTransactionClosed(final LevelDbTransaction transaction) {
    closedTransactionsCounter.inc();
    openTransactions.remove(transaction);
  }

  private DBIterator createIterator() {
    final DBIterator iterator = db.iterator(new ReadOptions().fillCache(false));
    openIterators.add(iterator);
    return iterator;
  }

  void assertOpen() {
    if (closed.get()) {
      throw new ShuttingDownException();
    }
  }
}
