/*
 * Copyright 2011 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.twitter.libkestrel

import com.twitter.util._
import java.io._
import java.nio.ByteBuffer
import org.specs.Specification

class JournalSpec extends Specification with TempFolder {
  "Journal" should {
    "find reader/writer files" in {
      withTempFolder {
        List(
          "test.901", "test.8000", "test.3leet", "test.read.client1", "test.read.client2",
          "test.readmenot", "test.1", "test.5005", "test.read.client1~~"
        ).foreach { name =>
          new File(folderName, name).createNewFile()
        }

        val j = new Journal(new File(folderName), "test", null, Duration.MaxValue)
        j.writerFiles().map { _.getName }.toSet mustEqual
          Set("test.901", "test.8000", "test.1", "test.5005")
        j.readerFiles().map { _.getName }.toSet mustEqual
          Set("test.read.client1", "test.read.client2")
      }
    }

    "fileForId" in {
      withTempFolder {
        List(
          ("test.901", 901),
          ("test.8000", 8000),
          ("test.1", 1),
          ("test.5005", 5005)
        ).foreach { case (name, id) =>
          val j = JournalFile.createWriter(new File(folderName, name), null, Duration.MaxValue)
          j.put(QueueItem(id, Time.now, None, new Array[Byte](1)))
          j.close()
        }

        val j = new Journal(new File(folderName), "test", null, Duration.MaxValue)
        j.fileForId(1) mustEqual Some(new File(folderName, "test.1"))
        j.fileForId(0) mustEqual None
        j.fileForId(555) mustEqual Some(new File(folderName, "test.1"))
        j.fileForId(900) mustEqual Some(new File(folderName, "test.1"))
        j.fileForId(901) mustEqual Some(new File(folderName, "test.901"))
        j.fileForId(902) mustEqual Some(new File(folderName, "test.901"))
        j.fileForId(6666) mustEqual Some(new File(folderName, "test.5005"))
        j.fileForId(9999) mustEqual Some(new File(folderName, "test.8000"))
      }
    }
  }

  "Journal#Reader" should {
    "write a checkpoint" in {
      withTempFolder {
        val file = new File(folderName, "a1")
        val j = new Journal(new File(folderName), "test", null, Duration.MaxValue)
        val reader = new j.Reader(file)
        reader.head = 123L
        reader.commit(125L)
        reader.commit(130L)
        reader.checkpoint()

        JournalFile.openReader(file, null, Duration.MaxValue).toList mustEqual List(
          JournalFile.Record.ReadHead(123L),
          JournalFile.Record.ReadDone(Array(125L, 130L))
        )
      }
    }

    "read a checkpoint" in {
      withTempFolder {
        val file = new File(folderName, "a1")
        val jf = JournalFile.createReader(file, null, Duration.MaxValue)
        jf.readHead(900L)
        jf.readDone(Array(902L, 903L))
        jf.close()

        val j = new Journal(new File(folderName), "test", null, Duration.MaxValue)
        val reader = new j.Reader(file)
        reader.readState()
        reader.head mustEqual 900L
        reader.doneSet.toList.sorted mustEqual List(902L, 903L)
      }
    }

    "track committed items" in {
      withTempFolder {
        val file = new File(folderName, "a1")
        val j = new Journal(new File(folderName), "test", null, Duration.MaxValue)
        val reader = new j.Reader(file)
        reader.head = 123L

        reader.commit(124L)
        reader.head mustEqual 124L
        reader.doneSet.toList.sorted mustEqual List()

        reader.commit(126L)
        reader.commit(127L)
        reader.commit(129L)
        reader.head mustEqual 124L
        reader.doneSet.toList.sorted mustEqual List(126L, 127L, 129L)

        reader.commit(125L)
        reader.head mustEqual 127L
        reader.doneSet.toList.sorted mustEqual List(129L)

        reader.commit(130L)
        reader.commit(128L)
        reader.head mustEqual 130L
        reader.doneSet.toList.sorted mustEqual List()
      }
    }
  }
}
