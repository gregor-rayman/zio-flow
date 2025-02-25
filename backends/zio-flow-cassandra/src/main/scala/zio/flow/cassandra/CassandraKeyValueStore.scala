/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
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

package zio.flow.cassandra

import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.core.{CqlIdentifier, CqlSession}
import com.datastax.oss.driver.api.querybuilder.QueryBuilder.literal
import com.datastax.oss.driver.api.querybuilder.delete.DeleteSelection
import com.datastax.oss.driver.api.querybuilder.insert.InsertInto
import com.datastax.oss.driver.api.querybuilder.select.SelectFrom
import com.datastax.oss.driver.api.querybuilder.{Literal, QueryBuilder}
import zio.flow.cassandra.CassandraKeyValueStore._
import zio.stream.ZStream
import zio.{Chunk, IO, Task, URLayer, ZIO, ZLayer}

import java.io.IOException
import java.nio.ByteBuffer
import com.datastax.oss.driver.api.core.metadata.schema.ClusteringOrder
import zio.flow.runtime.{KeyValueStore, Timestamp}

import scala.jdk.CollectionConverters._

final class CassandraKeyValueStore(session: CqlSession) extends KeyValueStore {

  private val keyspace =
    session.getKeyspace
      .orElse(null: CqlIdentifier) // scalafix:ok DisableSyntax.null

  private val cqlSelect: SelectFrom =
    QueryBuilder.selectFrom(keyspace, table)

  private val cqlInsert: InsertInto =
    QueryBuilder.insertInto(keyspace, table)

  private val cqlDelete: DeleteSelection =
    QueryBuilder.deleteFrom(keyspace, table)

  override def put(
    namespace: String,
    key: Chunk[Byte],
    value: Chunk[Byte],
    timestamp: Timestamp
  ): IO[Throwable, Boolean] = {
    val insert = toInsert(namespace, key, timestamp, value)

    executeAsync(insert)
      .mapBoth(
        new IOException(s"Error putting key-value pair for <$namespace> namespace", _),
        _ => true
      )
  }

  private def getLatestImpl(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[Throwable, Option[(Chunk[Byte], Timestamp)]] = {
    val queryBuilder = cqlSelect
      .column(valueColumnName)
      .column(timestampColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(key)
      )
      .orderBy(
        Map(
          keyColumnName       -> ClusteringOrder.DESC,
          timestampColumnName -> ClusteringOrder.DESC
        ).asJava
      )

    val query = before
      .fold(queryBuilder)(timestamp =>
        queryBuilder
          .whereColumn(timestampColumnName)
          .isLessThanOrEqualTo(
            literal(timestamp.value)
          )
      )
      .limit(1)
      .build

    executeAsync(query).flatMap { result =>
      if (result.remaining > 0) {
        val element = result.one
        ZIO.attempt {
          Option((blobValueOf(valueColumnName, element), Timestamp(element.getLong(timestampColumnName))))
        }
      } else {
        ZIO.none
      }
    }.mapError(
      new IOException(s"Error retrieving or reading value for <$namespace> namespace", _)
    )
  }

  override def getLatest(
    namespace: String,
    key: Chunk[Byte],
    before: Option[Timestamp]
  ): IO[Throwable, Option[Chunk[Byte]]] =
    getLatestImpl(namespace, key, before).map(_.map(_._1))

  override def scanAll(namespace: String): ZStream[Any, Throwable, (Chunk[Byte], Chunk[Byte])] = {
    val query = cqlSelect
      .column(keyColumnName)
      .column(valueColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .build

    ZStream
      .paginateZIO(
        executeAsync(query)
      )(_.map { result =>
        val pairs =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapZIO { row =>
              ZIO.attempt {
                blobValueOf(keyColumnName, row) -> blobValueOf(valueColumnName, row)
              }
            }

        val nextPage =
          if (result.hasMorePages)
            Option(
              ZIO.fromCompletionStage(result.fetchNextPage())
            )
          else
            None

        (pairs, nextPage)
      })
      .mapError(
        new IOException(s"Error scanning all key-value pairs for <$namespace> namespace", _)
      )
      .flatten
  }

  override def scanAllKeys(namespace: String): ZStream[Any, Throwable, Chunk[Byte]] = {
    val query = cqlSelect
      .column(keyColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .build

    ZStream
      .paginateZIO(
        executeAsync(query)
      )(_.map { result =>
        val keys =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapZIO { row =>
              ZIO.attempt {
                blobValueOf(keyColumnName, row)
              }
            }

        val nextPage =
          if (result.hasMorePages)
            Option(
              ZIO.fromCompletionStage(result.fetchNextPage())
            )
          else
            None

        (keys, nextPage)
      })
      .mapError(
        new IOException(s"Error scanning all key-value pairs for <$namespace> namespace", _)
      )
      .flatten
  }

  override def getLatestTimestamp(
    namespace: String,
    key: zio.Chunk[Byte]
  ): zio.IO[Throwable, Option[Timestamp]] =
    getLatestImpl(namespace, key, before = None).map(_.map(_._2))

  override def getAllTimestamps(namespace: String, key: Chunk[Byte]): ZStream[Any, Throwable, Timestamp] = {
    val query = cqlSelect
      .column(timestampColumnName)
      .whereColumn(namespaceColumnName)
      .isEqualTo(
        literal(namespace)
      )
      .whereColumn(keyColumnName)
      .isEqualTo(
        byteBufferFrom(key)
      )
      .build()

    ZStream
      .paginateZIO(
        executeAsync(query)
      )(_.map { result =>
        val pairs =
          ZStream
            .fromJavaIterator(
              result.currentPage.iterator
            )
            .mapZIO { row =>
              ZIO.attempt(Timestamp(row.getLong(timestampColumnName)))
            }

        val nextPage =
          if (result.hasMorePages)
            Option(
              ZIO.fromCompletionStage(result.fetchNextPage())
            )
          else
            None

        (pairs, nextPage)
      })
      .mapError(
        new IOException(s"Error scanning all key-value pairs for <$namespace> namespace", _)
      )
      .flatten
  }

  override def delete(namespace: String, key: Chunk[Byte], marker: Option[Timestamp]): IO[Throwable, Unit] =
    for {
      timestamps <- getAllTimestamps(namespace, key).runCollect
      toDelete = marker match {
                   case Some(markerTimestamp) =>
                     val before = timestamps.takeWhile(_ <= markerTimestamp)
                     if (before.isEmpty) Chunk.empty else before.init
                   case None =>
                     timestamps
                 }
      delete = cqlDelete
                 .whereColumn(namespaceColumnName)
                 .isEqualTo(
                   literal(namespace)
                 )
                 .whereColumn(keyColumnName)
                 .isEqualTo(
                   byteBufferFrom(key)
                 )
                 .whereColumn(timestampColumnName)
                 .in(toDelete.map(t => literal(t.value)): _*)
                 .build()

      _ <- executeAsync(delete)
             .mapBoth(
               new IOException(s"Error deleting key-value pair from <$namespace> namespace", _),
               _ => ()
             )
    } yield ()

  private def toInsert(namespace: String, key: Chunk[Byte], timestamp: Timestamp, value: Chunk[Byte]): SimpleStatement =
    cqlInsert
      .value(
        namespaceColumnName,
        literal(namespace)
      )
      .value(
        keyColumnName,
        byteBufferFrom(key)
      )
      .value(
        timestampColumnName,
        literal(timestamp.value)
      )
      .value(
        valueColumnName,
        byteBufferFrom(value)
      )
      .build

  private def executeAsync(statement: Statement[_]): Task[AsyncResultSet] =
    ZIO.fromCompletionStage(
      session.executeAsync(statement)
    )
}

object CassandraKeyValueStore {

  val layer: URLayer[CqlSession, KeyValueStore] =
    ZLayer {
      ZIO
        .service[CqlSession]
        .map(new CassandraKeyValueStore(_))
    }

  private[cassandra] val tableName: String =
    withDoubleQuotes("_zflow_key_value_store")

  private[cassandra] val namespaceColumnName: String =
    withColumnPrefix("namespace")

  private[cassandra] val keyColumnName: String =
    withColumnPrefix("key")

  private[cassandra] val timestampColumnName: String =
    withColumnPrefix("timestamp")

  private[cassandra] val valueColumnName: String =
    withColumnPrefix("value")

  private val table: CqlIdentifier =
    CqlIdentifier.fromCql(tableName)

  private def byteBufferFrom(bytes: Chunk[Byte]): Literal =
    literal(
      ByteBuffer.wrap(bytes.toArray)
    )

  private def blobValueOf(columnName: String, row: Row): Chunk[Byte] =
    Chunk.fromArray(
      row
        .getByteBuffer(columnName)
        .array
    )

  private def withColumnPrefix(s: String) =
    withDoubleQuotes("zflow_kv_" + s)

  private def withDoubleQuotes(string: String) =
    "\"" + string + "\""
}
