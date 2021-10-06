
/*
 * Copyright 2020 Lenses.io
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

package io.lenses.streamreactor.connect.aws.s3.formats

import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.formats.parquet.ParquetOutputFile
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocation
import io.lenses.streamreactor.connect.aws.s3.model.{Offset, SinkData, Topic}
import io.lenses.streamreactor.connect.aws.s3.sink.conversion.ToAvroDataConverter
import io.lenses.streamreactor.connect.aws.s3.storage.stream.S3OutputStream
import org.apache.avro.Schema
import org.apache.kafka.connect.data.{Schema => ConnectSchema}
import org.apache.parquet.avro.AvroParquetWriter
import org.apache.parquet.hadoop.ParquetWriter
import org.apache.parquet.hadoop.ParquetWriter.{DEFAULT_BLOCK_SIZE, DEFAULT_PAGE_SIZE}

import scala.util.{Success, Try}

class ParquetFormatWriter(outputStreamFn: () => S3OutputStream) extends S3FormatWriter with LazyLogging {

  private var outputStream: S3OutputStream = _

  private var writer: ParquetWriter[AnyRef] = _

  override def write(keySinkData: Option[SinkData], valueSinkData: SinkData, topic: Topic): Either[Throwable, Unit] = {
    Try {


      logger.debug("AvroFormatWriter - write")

      val genericRecord: AnyRef = ToAvroDataConverter.convertToGenericRecord(valueSinkData)
      if (writer == null) {
        writer = init(valueSinkData.schema())
      }

      writer.write(genericRecord)
      outputStream.flush()
    }.toEither
  }

  private def init(connectSchema: Option[ConnectSchema]): ParquetWriter[AnyRef] = {
    val schema: Schema = ToAvroDataConverter.convertSchema(connectSchema)

    outputStream = outputStreamFn()
    val outputFile = new ParquetOutputFile(outputStream)

    AvroParquetWriter
      .builder[AnyRef](outputFile)
      .withRowGroupSize(DEFAULT_BLOCK_SIZE)
      .withPageSize(DEFAULT_PAGE_SIZE)
      .withSchema(schema)
      .build()

  }

  override def rolloverFileOnSchemaChange() = true

  override def close(newName: RemoteS3PathLocation, offset: Offset): Either[Throwable, Unit] = {
    for {
      _ <- Suppress(writer.close())
      _ <- Suppress(outputStream.flush())
      closed <- Try(outputStream.complete(newName, offset))
      _ <- Suppress(outputStream.close())
    } yield closed
  }.toEither

  override def getPointer: Long = outputStream.getPointer

  override def close(): Unit = Try(outputStream.close())

}
