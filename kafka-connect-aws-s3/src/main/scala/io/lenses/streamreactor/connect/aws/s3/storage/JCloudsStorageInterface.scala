
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

package io.lenses.streamreactor.connect.aws.s3.storage

import com.typesafe.scalalogging.LazyLogging
import io.lenses.streamreactor.connect.aws.s3.model.location.{LocalPathLocation, RemoteS3PathLocation, RemoteS3RootLocation}
import org.jclouds.blobstore.BlobStoreContext
import org.jclouds.blobstore.domain.internal.MutableBlobMetadataImpl
import org.jclouds.blobstore.domain.{BlobMetadata, StorageType}
import org.jclouds.blobstore.options.{CopyOptions, ListContainerOptions, PutOptions}
import org.jclouds.io.payloads.{BaseMutableContentMetadata, InputStreamPayload}

import java.io.{ByteArrayInputStream, File, InputStream}
import java.util.UUID
import scala.collection.JavaConverters._
import scala.util.Try

class JCloudsStorageInterface(sinkName: String, blobStoreContext: BlobStoreContext) extends StorageInterface with LazyLogging {

  private val blobStore = blobStoreContext.getBlobStore
  private val awsMaxKeys = 1000

  override def uploadFile(initialName: LocalPathLocation, finalDestination: RemoteS3PathLocation): Unit = {
    logger.debug(s"[{}] Uploading file from local {} to s3 {}", sinkName, initialName, finalDestination)

    val file = new File(initialName.path)
    val length = file.length()
    require(length > 0L, "zero byte upload detected")
    val blob = blobStore
      .blobBuilder(finalDestination.path)
      .payload(file)
      .contentLength(length)
      .build()

    blobStore.putBlob(finalDestination.bucket, blob)

    logger.debug(s"[{}] Completed upload from local {} to s3 {}", sinkName, initialName, finalDestination)

  }

  private def buildBlobMetadata(bucketAndPath: RemoteS3PathLocation): BlobMetadata = {
    val blobMetadata = new MutableBlobMetadataImpl()
    blobMetadata.setId(UUID.randomUUID().toString)
    blobMetadata.setName(bucketAndPath.path)
    blobMetadata
  }

  override def close(): Unit = blobStoreContext.close()

  override def pathExists(bucketAndPath: RemoteS3PathLocation): Boolean =
    blobStore.list(bucketAndPath.bucket, ListContainerOptions.Builder.prefix(bucketAndPath.path)).size() > 0

  override def list(bucketAndPath: RemoteS3PathLocation): List[String] = {

    val options = ListContainerOptions.Builder.recursive().prefix(bucketAndPath.path).maxResults(awsMaxKeys)

    var pageSetStrings: List[String] = List()
    var nextMarker: Option[String] = None
    do {
      if (nextMarker.nonEmpty) {
        options.afterMarker(nextMarker.get)
      }
      val pageSet = blobStore.list(bucketAndPath.bucket, options)
      nextMarker = Option(pageSet.getNextMarker)
      pageSetStrings ++= pageSet
        .asScala
        .filter(_.getType == StorageType.BLOB)
        .map(
          storageMetadata => storageMetadata.getName
        )
        .toList

    } while (nextMarker.nonEmpty)
    pageSetStrings
  }

  override def getBlob(bucketAndPath: RemoteS3PathLocation): InputStream = {
    blobStore.getBlob(bucketAndPath.bucket, bucketAndPath.path).getPayload.openStream()
  }

  override def getBlobSize(bucketAndPath: RemoteS3PathLocation): Long = {
    blobStore.getBlob(bucketAndPath.bucket, bucketAndPath.path).getMetadata.getSize
  }

}
