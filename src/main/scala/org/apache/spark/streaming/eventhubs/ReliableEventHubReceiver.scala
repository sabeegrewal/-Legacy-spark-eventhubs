/*
 * Copyright (c) Microsoft. All rights reserved.
 * Licensed under the MIT license. See LICENSE file in the project root for full license information.
 */
package org.apache.spark.streaming.eventhubs

import java.util.concurrent.ConcurrentHashMap

import com.microsoft.azure.eventhubs.EventData
import org.apache.spark.SparkEnv
import org.apache.spark.storage.{StorageLevel, StreamBlockId}
import org.apache.spark.streaming.receiver.{BlockGenerator, BlockGeneratorListener}

import scala.collection.mutable.ArrayBuffer

/**
  * Enables ability to reliably store data into BlockManager without lost.
  * By default, the EventHubReceiver is used. To use this receiver, set
  * spark.streaming.receiver.writeAheadLog.enable to true.
  *
  * The difference between this receiver and the EventHubReceiver is that the
  * offset is updated in persistent store only after data is reliably stored as write-ahead log.
  */
class ReliableEventHubReceiver(
  eventHubParams: Map[String, String],
  partitionId: String,
  storageLevel: StorageLevel,
  offsetStore: OffsetStore,
  client: EventHubInstance
) extends EventHubReceiver(eventHubParams, partitionId, storageLevel, offsetStore, client) {

  /** Used to generate blocks to Spark Block Manager synchronously */
  private var blockGenerator: BlockGenerator = null

  /** Stores latest offset in current block for the current partition */
  private var latestOffsetCurBlock: String = null

  /** Stores the stream block id and the corresponding offset snapshot */
  private var blockOffsetMap: ConcurrentHashMap[StreamBlockId, String] = null

  override def onStop() : Unit = {
    super.onStop()

    if (blockGenerator != null) {
      blockGenerator.stop()
      blockGenerator = null
    }
    if (blockOffsetMap != null) {
      blockOffsetMap.clear()
      blockOffsetMap = null
    }
  }

  override def onStart(): Unit = {
    blockOffsetMap = new ConcurrentHashMap[StreamBlockId, String]

    blockGenerator = new BlockGenerator(new GeneratedBlockHandler, streamId, SparkEnv.get.conf)
    blockGenerator.start()

    super.onStart()
  }

  override def processReceivedMessage(events: Iterable[EventData]): Unit = {
    for (event <- events)
      blockGenerator.addDataWithCallback(event, event.getSystemProperties.getOffset)
  }

  /**
    * Store the block and commit the related offsets to OffsetStore. This is attempted
    * 3 times - if all attempts fail, then the receiver is stopped.
    */
  private def storeBlockAndCommitOffset(
      blockId: StreamBlockId,
      arrayBuffer: ArrayBuffer[_]): Unit = {
    var count = 0
    var pushed = false
    var exception: Exception = null
    while (!pushed && count <= 3) {
      try {
        store(arrayBuffer.asInstanceOf[ArrayBuffer[EventData]])
        pushed = true
      } catch {
        case e: Exception =>
          count += 1
          exception = e
      }
    }
    if (pushed) {
      /* put the latest offset in the block in offsetToSave, so that the offset is saved
         after the next checkpoint interval is reached. */
      offsetToSave = blockOffsetMap.get(blockId)
      blockOffsetMap.remove(blockId)
    } else {
      stop("Error while storing block into Spark", exception)
    }
  }

  /** This class handles the blocks generated by the block manager */
  private final class GeneratedBlockHandler extends BlockGeneratorListener {

    override def onAddData(data: Any, metadata: Any): Unit = {
      if (metadata != null)
        latestOffsetCurBlock = metadata.asInstanceOf[String]
    }

    override def onGenerateBlock(blockId: StreamBlockId): Unit = {
      blockOffsetMap.put(blockId, latestOffsetCurBlock)
    }

    override def onPushBlock(blockId: StreamBlockId, arrayBuffer: ArrayBuffer[_]): Unit = {
      storeBlockAndCommitOffset(blockId, arrayBuffer)
    }

    override def onError(message: String, throwable: Throwable): Unit = {
      reportError(message, throwable)
    }
  }
}
