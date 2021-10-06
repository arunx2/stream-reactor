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

import cats.implicits.catsSyntaxEitherId
import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.model._
import io.lenses.streamreactor.connect.aws.s3.model.location.RemoteS3PathLocation
import io.lenses.streamreactor.connect.aws.s3.storage.stream.S3OutputStream

import scala.util.{Success, Try}

class BytesFormatWriter(outputStreamFn: () => S3OutputStream, bytesWriteMode: BytesWriteMode) extends S3FormatWriter with LazyLogging {

  private val outputStream: S3OutputStream = outputStreamFn()

  override def write(keySinkData: Option[SinkData], valueSinkData: SinkData, topic: Topic): Either[Throwable, Unit] = {

    val writeKeys = bytesWriteMode.entryName.contains("Key")
    val writeValues = bytesWriteMode.entryName.contains("Value")
    val writeSizes = bytesWriteMode.entryName.contains("Size")

    var byteOutputRow = BytesOutputRow(
      None,
      None,
      Array.empty,
      Array.empty
    )

    if (writeKeys) {
      keySinkData.fold(throw FormatWriterException("No key supplied however requested to write key."))(keyStruct => {
        convertToBytes(keyStruct) match {
          case Left(exception) => return exception.asLeft
          case Right(keyDataBytes) => byteOutputRow = byteOutputRow.copy(
            keySize = if (writeSizes) Some(keyDataBytes.length.longValue()) else None,
            key = keyDataBytes
          )
        }
      })
    }

    if (writeValues) {
      convertToBytes(valueSinkData) match {
        case Left(exception) => return exception.asLeft
        case Right(valueDataBytes) => byteOutputRow = byteOutputRow.copy(
          valueSize = if (writeSizes) Some(valueDataBytes.length.longValue()) else None,
          value = valueDataBytes
        )
      }

    }

    outputStream.write(byteOutputRow.toByteArray)
    outputStream.flush()
    ().asRight
  }

  def convertToBytes(sinkData: SinkData): Either[Throwable, Array[Byte]] = {
    sinkData match {
      case ByteArraySinkData(array, _) => array.asRight
      case _ => new IllegalStateException("Non-binary content received.  Please check your configuration.  It may be advisable to ensure you are using org.apache.kafka.connect.converters.ByteArrayConverter\", exception)\n      case Success(value) => value").asLeft
    }
  }

  override def rolloverFileOnSchemaChange(): Boolean = false

  override def close(newName: RemoteS3PathLocation, offset: Offset): Either[Throwable,Unit] = {
    for {
      closed <- Try(outputStream.complete(newName, offset))
      _ <- Suppress(outputStream.flush())
      _ <- Suppress(outputStream.close())
    } yield closed
  }.toEither

  override def getPointer: Long = outputStream.getPointer

  override def close(): Unit = {
    Try(outputStream.close())
  }
}
